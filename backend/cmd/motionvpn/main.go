package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"open-tj.local/backend/internal/client"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"syscall"
	"time"
)

var outputMu sync.Mutex

func emit(event string, fields map[string]any) {
	outputMu.Lock()
	defer outputMu.Unlock()
	if fields == nil {
		fields = map[string]any{}
	}
	fields["event"] = event
	fields["timestamp"] = time.Now().UTC().Format(time.RFC3339Nano)
	if _, ok := fields["level"]; !ok {
		fields["level"] = "info"
		if event == "reconnecting" {
			fields["level"] = "warning"
		}
		if event == "failed" {
			fields["level"] = "error"
		}
	}
	json.NewEncoder(os.Stdout).Encode(fields)
	if event != "traffic" {
		if home, e := os.UserHomeDir(); e == nil {
			dir := filepath.Join(home, "Library/Logs/Open TJ")
			if os.MkdirAll(dir, 0700) == nil {
				path := filepath.Join(dir, "connections-go.jsonl")
				if info, e := os.Stat(path); e == nil && info.Size() > 2*1024*1024 {
					os.Rename(path, path+".previous")
				}
				if f, e := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600); e == nil {
					json.NewEncoder(f).Encode(fields)
					f.Close()
				}
			}
		}
	}
}
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\"'\"'") + "'" }
func apple(s string) string {
	return "\"" + strings.NewReplacer("\\", "\\\\", "\"", "\\\"").Replace(s) + "\""
}
func tunnel(ctx context.Context, s *client.Session) (site map[string]any) {
	site = map[string]any{"host_network_modified": false}
	fail := func(e error) { site["connection_error"] = e.Error(); site["startup_failed"] = true }
	ip, e := client.IPv4(s.Network["client_ipv4"])
	if e != nil {
		fail(e)
		return
	}
	var dnsItems []struct {
		IPv4 []json.RawMessage `json:"ipv4"`
	}
	if e = json.Unmarshal(s.Network["dns_servers"], &dnsItems); e != nil {
		fail(e)
		return
	}
	var dns []string
	for _, item := range dnsItems {
		for _, v := range item.IPv4 {
			a, e := client.IPv4(v)
			if e != nil {
				fail(e)
				return
			}
			dns = append(dns, a)
		}
	}
	if len(dns) > 4 {
		dns = dns[:4]
	}
	gateway, _, _ := net.SplitHostPort(s.Conn.RemoteAddr().String())
	p, e := client.SystemRun("/sbin/route", "-n", "get", gateway)
	if e != nil {
		fail(e)
		return
	}
	before := client.RouteFields(p)
	if !strings.HasPrefix(before["interface"], "en") {
		fail(fmt.Errorf("网关出口不是物理网络，请先停用其他全局 VPN"))
		return
	}
	service, e := client.ActiveService(client.SystemRun, before["interface"])
	if e != nil {
		fail(e)
		return
	}
	snapshot, e := client.TakeSnapshot(client.SystemRun, service)
	if e != nil {
		fail(e)
		return
	}
	emit("log", map[string]any{"component": "network", "message": fmt.Sprintf("网络预检完成 · 服务 %s · 出口 %s · VPN DNS %d 个", service, before["interface"], len(dns))})
	cfg := client.NetworkConfig{IP: ip, DNS: dns, Gateway: gateway, Router: before["gateway"], Interface: before["interface"], Before: snapshot}
	dir, e := os.MkdirTemp("", "motionvpn-")
	if e != nil {
		fail(e)
		return
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "control.sock")
	listener, e := net.Listen("unix", path)
	if e != nil {
		fail(e)
		return
	}
	defer listener.Close()
	var tokenBytes [32]byte
	rand.Read(tokenBytes[:])
	token := hex.EncodeToString(tokenBytes[:])
	exe, _ := os.Executable()
	command := quote(exe) + " --helper " + quote(path) + " " + quote(token)
	script := "do shell script " + apple(command) + " with administrator privileges"
	cmd := exec.Command("/usr/bin/osascript", "-e", script)
	var stderr strings.Builder
	cmd.Stderr = &stderr
	emit("progress", map[string]any{"message": "请在系统弹窗中授权创建 VPN 隧道"})
	if e = cmd.Start(); e != nil {
		fail(e)
		return
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	defer func() {
		select {
		case <-done:
		case <-time.After(45 * time.Second):
			cmd.Process.Kill()
			<-done
		}
		data, e := os.ReadFile(path + ".receipt")
		if e == nil {
			var receipt client.Receipt
			if json.Unmarshal(data, &receipt) == nil {
				site["cleanup_confirmed"] = receipt.Clean
				site["network_restore_failures"] = receipt.Failures
				if receipt.Error != "" {
					site["helper_error"] = receipt.Error
				}
			}
		}
		after, e := client.TakeSnapshot(client.SystemRun, service)
		site["parent_state_restored"] = e == nil && reflect.DeepEqual(after, snapshot)
		p, e := client.SystemRun("/sbin/route", "-n", "get", gateway)
		fields := client.RouteFields(p)
		site["outer_route_restored"] = e == nil && fields["gateway"] == before["gateway"] && fields["interface"] == before["interface"]
		level := "info"
		message := "网络恢复核对完成"
		if site["cleanup_confirmed"] != true || site["parent_state_restored"] != true || site["outer_route_restored"] != true {
			level = "warning"
			message = "网络恢复尚未全部确认，请检查连接结果"
		}
		emit("log", map[string]any{"level": level, "component": "cleanup", "message": message})
	}()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, e := listener.Accept()
		if e == nil {
			select {
			case accepted <- c:
			case <-ctx.Done():
				c.Close()
			}
		}
	}()
	var helper net.Conn
	select {
	case helper = <-accepted:
	case e = <-done:
		done <- e
		fail(fmt.Errorf("授权辅助程序未启动：%s", stderr.String()))
		return
	case <-ctx.Done():
		cmd.Process.Kill()
		fail(ctx.Err())
		return
	case <-time.After(90 * time.Second):
		cmd.Process.Kill()
		fail(fmt.Errorf("管理员授权超时"))
		return
	}
	defer helper.Close()
	cancelIO := context.AfterFunc(ctx, func() { helper.Close() })
	defer cancelIO()
	helper.SetDeadline(time.Now().Add(90 * time.Second))
	r := bufio.NewReader(helper)
	line, e := client.ReadLine(r, 256)
	if e != nil || !client.VerifyToken(strings.TrimSpace(string(line)), token) {
		fail(fmt.Errorf("helper authentication failed"))
		return
	}
	emit("log", map[string]any{"component": "helper", "message": "管理员 helper 已通过握手，开始配置 utun、DNS 和路由"})
	site["host_network_modified"] = true
	if e = json.NewEncoder(helper).Encode(cfg); e != nil {
		fail(e)
		return
	}
	line, e = client.ReadLine(r, 65536)
	if e != nil {
		fail(e)
		return
	}
	var receipt client.Receipt
	if e = json.Unmarshal([]byte(line), &receipt); e != nil || !receipt.Ready {
		fail(fmt.Errorf("网络配置失败：%s", receipt.Error))
		return
	}
	helper.SetDeadline(time.Time{})
	emit("connected", map[string]any{"interface": receipt.Interface})
	var interval int
	json.Unmarshal(s.Common["keepalive_interval"], &interval)
	stats := &client.BridgeStats{}
	telemetryCtx, stopTelemetry := context.WithCancel(ctx)
	telemetryDone := make(chan struct{})
	go func() {
		defer close(telemetryDone)
		meter := client.NewTrafficMeter(time.Now())
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case now := <-ticker.C:
				emit("traffic", map[string]any{"traffic": meter.Sample(now, stats)})
			case <-telemetryCtx.Done():
				return
			}
		}
	}()
	probeCtx, stopProbes := context.WithTimeout(ctx, 25*time.Second)
	probeDone := make(chan []client.ProbeResult, 1)
	go func() {
		var results []client.ProbeResult
		if len(dns) > 0 {
			results = client.ProbeSites(probeCtx, dns[0])
		}
		if probeCtx.Err() == nil {
			emit("progress", map[string]any{"message": client.ProbeSummary(results), "component": "diagnostics", "probes": results})
		}
		probeDone <- results
	}()
	started := time.Now()
	e = client.Bridge(ctx, s.Conn, s.Reader, &client.BufferedConn{Conn: helper, Reader: r}, time.Duration(interval)*time.Second, stats)
	stopTelemetry()
	<-telemetryDone
	emit("traffic", map[string]any{"traffic": client.TrafficSample{UploadBytes: stats.UploadBytes.Load(), DownloadBytes: stats.DownloadBytes.Load()}})
	emit("log", map[string]any{"message": "数据转发已停止，正在等待管理员 helper 恢复网络", "component": "network"})
	stopProbes()
	site["connectivity_probes"] = <-probeDone
	site["diagnostics"] = map[string]any{"download_bytes": stats.DownloadBytes.Load(), "upload_bytes": stats.UploadBytes.Load(), "connected_seconds": time.Since(started).Seconds(), "packets_sent": stats.Sent.Load(), "packets_received": stats.Received.Load(), "heartbeats_received": stats.Heartbeats.Load()}
	if e != nil && !errors.Is(e, context.Canceled) {
		site["connection_error"] = e.Error()
	}
	return
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if len(os.Args) == 3 && os.Args[1] == "--diagnose" {
		probeCtx, stop := context.WithTimeout(ctx, 25*time.Second)
		defer stop()
		json.NewEncoder(os.Stdout).Encode(client.ProbeSites(probeCtx, os.Args[2]))
		return
	}
	if len(os.Args) == 4 && os.Args[1] == "--helper" {
		if e := client.RunHelper(ctx, os.Args[2], os.Args[3]); e != nil {
			fmt.Fprintln(os.Stderr, e)
			os.Exit(1)
		}
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		return
	}
	var cred client.Credentials
	if json.Unmarshal(scanner.Bytes(), &cred) != nil || cred.Username == "" || cred.Password == "" {
		emit("failed", map[string]any{"message": "请输入账号和密码"})
		return
	}
	var mu sync.Mutex
	awake := true
	var sessionCancel context.CancelFunc
	wake := make(chan struct{}, 1)
	go func() {
		for scanner.Scan() {
			mu.Lock()
			switch scanner.Text() {
			case "stop":
				cancel()
			case "sleep":
				awake = false
				if sessionCancel != nil {
					sessionCancel()
				}
			case "wake":
				awake = true
				select {
				case wake <- struct{}{}:
				default:
				}
			}
			mu.Unlock()
		}
		cancel()
	}()
	attempt := 0
	for ctx.Err() == nil {
		mu.Lock()
		a := awake
		mu.Unlock()
		if !a {
			emit("sleeping", nil)
			select {
			case <-wake:
			case <-ctx.Done():
				return
			}
			continue
		}
		connectionCtx, stop := context.WithCancel(ctx)
		mu.Lock()
		sessionCancel = stop
		if !awake {
			stop()
		}
		mu.Unlock()
		emit("connecting", nil)
		s, e := (client.Gateway{Host: "vpn.tongji.cn"}).Login(connectionCtx, cred)
		result := map[string]any{}
		retry := false
		if e != nil {
			result["error"] = e.Error()
		} else {
			emit("log", map[string]any{"message": "认证成功，TLS 隧道配置已读取", "component": "session"})
			result["login"] = "welcome"
			site := tunnel(connectionCtx, s)
			result["site_test"] = site
			s.Conn.Close()
			if e = s.Gateway.Logout(s.Cookie); e != nil {
				result["logout_error"] = e.Error()
			}
			retry = site["startup_failed"] != true && site["cleanup_confirmed"] == true && site["parent_state_restored"] == true && site["outer_route_restored"] == true
		}
		stop()
		mu.Lock()
		sleeping := !awake
		mu.Unlock()
		retry = retryPolicy(result, sleeping, ctx.Err() != nil)
		level := "info"
		if result["error"] != nil {
			level = "error"
		}
		if site, ok := result["site_test"].(map[string]any); ok && (site["connection_error"] != nil || site["host_network_modified"] == true && site["cleanup_confirmed"] != true) {
			level = "error"
		}
		emit("result", map[string]any{"result": result, "level": level})
		if !retry || ctx.Err() != nil {
			return
		}
		if sleeping {
			continue
		}
		attempt++
		seconds := min(30, 1<<min(attempt, 5))
		emit("reconnecting", map[string]any{"seconds": seconds})
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Duration(seconds) * time.Second):
		}
	}
}
