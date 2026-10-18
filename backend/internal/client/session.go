package client

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Credentials struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Method   string `json:"method"`
}
type Gateway struct {
	Host, Address string
	TLS           *tls.Config
}
type Session struct {
	Conn            net.Conn
	Reader          *bufio.Reader
	Common, Network map[string]json.RawMessage
	Cookie          string
	Gateway         Gateway
}

func (g Gateway) dial(ctx context.Context) (net.Conn, error) {
	address := g.Address
	if address == "" {
		address = net.JoinHostPort(g.Host, "443")
	}
	c, e := (&net.Dialer{Timeout: 15 * time.Second}).DialContext(ctx, "tcp", address)
	if e != nil {
		return nil, e
	}
	cfg := g.TLS
	if cfg == nil {
		cfg = &tls.Config{ServerName: g.Host, MinVersion: tls.VersionTLS12, CipherSuites: []uint16{tls.TLS_RSA_WITH_AES_128_CBC_SHA, tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384}}
	}
	t := tls.Client(c, cfg)
	if e = t.HandshakeContext(ctx); e != nil {
		c.Close()
		return nil, e
	}
	return t, nil
}
func (g Gateway) request(ctx context.Context, path, cookie string, body url.Values) (*http.Response, []byte, error) {
	c, e := g.dial(ctx)
	if e != nil {
		return nil, nil, e
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	method := "GET"
	var reader io.Reader
	if body != nil {
		method = "POST"
		reader = strings.NewReader(body.Encode())
	}
	req, _ := http.NewRequest(method, "https://"+g.Host+path, reader)
	req.Header.Set("Cookie", "ANStandalone=true;"+cookie)
	req.Header.Set("appid", "SSPVPN")
	if body != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	if e = req.Write(c); e != nil {
		return nil, nil, e
	}
	res, e := http.ReadResponse(bufio.NewReader(c), req)
	if e != nil {
		return nil, nil, e
	}
	defer res.Body.Close()
	p, e := io.ReadAll(io.LimitReader(res.Body, 1024*1024))
	return res, p, e
}
func (g Gateway) Login(ctx context.Context, cred Credentials) (s *Session, err error) {
	if cred.Method == "" {
		cred.Method = "LDAP3"
	}
	res, _, e := g.request(ctx, "/prx/000/http/localhost/login", "", url.Values{"method": {cred.Method}, "uname": {cred.Username}, "pwd": {cred.Password}, "pwd1": {""}, "pwd2": {""}, "deviceid": {""}, "device_name": {"MotionVPN"}, "hardwareid": {""}})
	if e != nil {
		return nil, e
	}
	var cookies []string
	for _, c := range res.Cookies() {
		cookies = append(cookies, c.Name+"="+c.Value)
	}
	cookie := strings.Join(cookies, "; ")
	ok := false
	defer func() {
		if !ok && cookie != "" {
			g.Logout(cookie)
		}
	}()
	if !strings.Contains(res.Header.Get("Location"), "/welcome") {
		return nil, fmt.Errorf("认证失败（%s，HTTP %d），请核对账号、密码和认证方式", cred.Method, res.StatusCode)
	}
	c, e := g.dial(ctx)
	if e != nil {
		return nil, e
	}
	defer func() {
		if !ok {
			c.Close()
		}
	}()
	cancel := context.AfterFunc(ctx, func() { c.Close() })
	defer cancel()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	var id [16]byte
	rand.Read(id[:])
	req, _ := http.NewRequest("GET", "https://"+g.Host+"/prx/000/http/localhost/vpntunnel", nil)
	for k, v := range map[string]string{"Cookie": "ANStandalone=true;" + cookie, "x-devtype": "3", "appid": "SSPVPN", "clientid": hex.EncodeToString(id[:]), "cpuid": "motionvpn-go", "hostname": "MotionVPN", "PAYLOAD-IP-VERSION": "6"} {
		req.Header.Set(k, v)
	}
	if e = req.Write(c); e != nil {
		return nil, e
	}
	r := bufio.NewReaderSize(c, 65536)
	line, e := r.ReadString('\n')
	if e != nil {
		return nil, e
	}
	if !strings.Contains(line, " 201 ") {
		return nil, fmt.Errorf("不支持的隧道响应：%s", strings.TrimSpace(line))
	}
	total := len(line)
	for {
		line, e = r.ReadString('\n')
		total += len(line)
		if e != nil {
			return nil, e
		}
		if total > 65536 {
			return nil, fmt.Errorf("oversized tunnel header")
		}
		if line == "\r\n" {
			break
		}
	}
	if e = writeAll(c, configRequest(0x50, nil)); e != nil {
		return nil, e
	}
	common, e := readConfig(r, 0x51)
	if e != nil {
		return nil, e
	}
	if e = writeAll(c, configRequest(0x54, make([]byte, 32))); e != nil {
		return nil, e
	}
	network, e := readConfig(r, 0x55)
	if e != nil {
		return nil, e
	}
	c.SetDeadline(time.Time{})
	ok = true
	return &Session{c, r, common, network, cookie, g}, nil
}
func (g Gateway) Logout(cookie string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, _, e := g.request(ctx, "/prx/000/http/localhost/logout", cookie, nil)
	return e
}
