package client

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func packet(i int) []byte {
	p := make([]byte, 1280)
	p[0] = 0x45
	p[9] = 6
	binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
	binary.BigEndian.PutUint32(p[20:], uint32(i))
	return p
}
func TestPacketAndFrameFragmentation(t *testing.T) {
	p := packet(7)
	var b bytes.Buffer
	if e := WriteFrame(&b, p); e != nil {
		t.Fatal(e)
	}
	got, e := ReadFrame(&b)
	if e != nil || !bytes.Equal(got, p) {
		t.Fatal(e)
	}
	for _, n := range []int{0, 19, 65536} {
		var h [4]byte
		binary.BigEndian.PutUint32(h[:], uint32(n))
		_, e := ReadFrame(bytes.NewReader(h[:]))
		if n != 0 && e == nil {
			t.Fatal("invalid frame accepted")
		}
	}
	p[2] = 0
	p[3] = 10
	if _, e = ReadPacket(bytes.NewReader(p)); e == nil {
		t.Fatal("bad IP length accepted")
	}
}
func TestJournalOwnershipAndRetry(t *testing.T) {
	fail := true
	var undone []string
	j := Journal{Run: func(a ...string) (string, error) {
		if a[0] == "bad" || a[0] == "undo2" && fail {
			return "", fmt.Errorf("injected")
		}
		if strings.HasPrefix(a[0], "undo") {
			undone = append(undone, a[0])
		}
		return "", nil
	}}
	j.Apply([]string{"one"}, []string{"undo1"})
	j.Apply([]string{"two"}, []string{"undo2"})
	j.Apply([]string{"bad"}, []string{"must-not-delete"})
	if len(j.Rollback()) != 1 {
		t.Fatal("lost rollback failure")
	}
	fail = false
	if len(j.Rollback()) != 0 || strings.Join(undone, ",") != "undo1,undo2" {
		t.Fatal(undone)
	}
}
func TestIPv6LocalAndExternalPaths(t *testing.T) {
	if len(IPv6Conflicts("fd07::/64 link#25 UC bridge100\n240a::1 aa UHL lo0\nfe80::%en0/64 link#11 UC en0")) != 0 {
		t.Fatal("local routes blocked")
	}
	for _, row := range []string{"2000::/3 fe80::1 UG utun8", "fd07::/64 link#25 UC utun8", "fd07::/64 fe80::1 UG bridge100"} {
		if len(IPv6Conflicts(row)) != 1 {
			t.Fatal(row)
		}
	}
}
func TestBridgeConcurrentSaturatedTraffic(t *testing.T) {
	a, b := net.Pipe()
	c, d := net.Pipe()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	stats := &BridgeStats{}
	done := make(chan error, 1)
	go func() { done <- Bridge(ctx, a, a, c, 100*time.Millisecond, stats) }()
	const count = 2048
	var wg sync.WaitGroup
	errs := make(chan error, 4)
	worker := func(fn func() error) { wg.Add(1); go func() { defer wg.Done(); errs <- fn() }() }
	worker(func() error {
		for i := 0; i < count; i++ {
			if e := WriteFrame(d, packet(i)); e != nil {
				return e
			}
		}
		return nil
	})
	worker(func() error {
		for i := 0; i < count; i++ {
			if e := writeAll(b, packet(i)); e != nil {
				return e
			}
		}
		return nil
	})
	worker(func() error {
		for i := 0; i < count; {
			p, e := ReadPacket(b)
			if e != nil {
				return e
			}
			if p[9] == 255 {
				continue
			}
			if binary.BigEndian.Uint32(p[20:]) != uint32(i) {
				return fmt.Errorf("upstream corruption")
			}
			i++
		}
		return nil
	})
	worker(func() error {
		for i := 0; i < count; {
			p, e := ReadFrame(d)
			if e != nil {
				return e
			}
			if len(p) == 0 {
				continue
			}
			if binary.BigEndian.Uint32(p[20:]) != uint32(i) {
				return fmt.Errorf("downstream corruption")
			}
			if i%100 == 0 {
				time.Sleep(time.Millisecond)
			}
			i++
		}
		return nil
	})
	wg.Wait()
	for i := 0; i < 4; i++ {
		if e := <-errs; e != nil {
			t.Fatal(e)
		}
	}
	cancel()
	b.Close()
	d.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("shutdown hung")
	}
	t.Logf("bidirectional transfer: %d bytes, packets sent=%d received=%d", 2*count*1280, stats.Sent.Load(), stats.Received.Load())
}
func TestBridgeSilentGatewayCancellation(t *testing.T) {
	a, b := net.Pipe()
	c, d := net.Pipe()
	defer b.Close()
	defer d.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Bridge(ctx, a, a, c, time.Hour, &BridgeStats{}) }()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("idle cancellation blocked")
	}
}
func TestMockGatewayLoginConfigAndLogout(t *testing.T) {
	var mu sync.Mutex
	logout := 0
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/prx/000/http/localhost/login":
			r.ParseForm()
			if r.Form.Get("uname") != "user" || r.Form.Get("pwd") != "p &+密码" || r.Form.Get("method") != "LDAP3" {
				t.Error("form encoding mismatch")
			}
			http.SetCookie(w, &http.Cookie{Name: "ANsession", Value: "secret"})
			w.Header().Set("Location", "/prx/000/http/localhost/welcome")
			w.WriteHeader(302)
		case "/prx/000/http/localhost/logout":
			mu.Lock()
			logout++
			mu.Unlock()
			w.WriteHeader(302)
		case "/prx/000/http/localhost/vpntunnel":
			if !strings.Contains(r.Header.Get("Cookie"), "ANsession=secret") {
				t.Error("missing cookie")
			}
			c, rw, e := w.(http.Hijacker).Hijack()
			if e != nil {
				t.Error(e)
				return
			}
			defer c.Close()
			rw.WriteString("HTTP/1.1 201 Created\r\n\r\n")
			rw.Flush()
			for i, kind := range []byte{0x51, 0x55} {
				request := make([]byte, 16)
				if _, e = io.ReadFull(rw, request); e != nil {
					return
				}
				n := binary.LittleEndian.Uint32(request[12:])
				io.CopyN(io.Discard, rw, int64(n))
				data := []byte(`{"keepalive_interval":30}`)
				if i == 1 {
					data = []byte(`{"client_ipv4":16777226,"dns_servers":[{"ipv4":[16777226]}]}`)
				}
				response := configRequest(kind, data)
				for _, v := range response {
					if _, e = c.Write([]byte{v}); e != nil {
						return
					}
				}
			}
			io.Copy(io.Discard, rw)
		}
	}))
	defer server.Close()
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	g := Gateway{Host: "example.com", Address: server.Listener.Addr().String(), TLS: &tls.Config{RootCAs: pool, ServerName: "example.com"}} // httptest certificate includes example.com
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, e := g.Login(ctx, Credentials{Username: "user", Password: "p &+密码", Method: "LDAP3"})
	if e != nil {
		t.Fatal(e)
	}
	ip, e := IPv4(s.Network["client_ipv4"])
	if e != nil || ip != "10.0.0.1" {
		t.Fatal(ip, e)
	}
	s.Conn.Close()
	if e = g.Logout(s.Cookie); e != nil {
		t.Fatal(e)
	}
	mu.Lock()
	defer mu.Unlock()
	if logout != 1 {
		t.Fatal(logout)
	}
}
func TestBridgeGatewayKillReason(t *testing.T) {
	a, b := net.Pipe()
	c, d := net.Pipe()
	defer b.Close()
	defer d.Close()
	done := make(chan error, 1)
	go func() { done <- Bridge(context.Background(), a, bufio.NewReader(a), c, time.Hour, &BridgeStats{}) }()
	writeAll(b, ATP(2, 3))
	select {
	case e := <-done:
		if !strings.Contains(e.Error(), "3") {
			t.Fatal(e)
		}
	case <-time.After(time.Second):
		t.Fatal("kill ignored")
	}
}
func TestIPv4JSON(t *testing.T) {
	for _, raw := range []string{`16777226`, `"10.0.0.1"`} {
		ip, e := IPv4(json.RawMessage(raw))
		if e != nil || ip != "10.0.0.1" {
			t.Fatal(ip, e)
		}
	}
}
