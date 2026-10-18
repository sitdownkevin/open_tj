package client

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync"
	"time"
)

type ProbeResult struct {
	nextLocation    string
	Location        string        `json:"redirect_location,omitempty"`
	Redirects       []ProbeResult `json:"redirects,omitempty"`
	URL             string        `json:"url"`
	SystemAddresses []string      `json:"system_addresses,omitempty"`
	SystemDNSError  string        `json:"system_dns_error,omitempty"`
	VPNAddresses    []string      `json:"vpn_addresses,omitempty"`
	VPNDNSError     string        `json:"vpn_dns_error,omitempty"`
	SystemDNSMS     int64         `json:"system_dns_ms"`
	VPNDNSMS        int64         `json:"vpn_dns_ms"`
	TCPMS           int64         `json:"tcp_ms"`
	TLSMS           int64         `json:"tls_ms"`
	FirstByteMS     int64         `json:"first_byte_ms"`
	TotalMS         int64         `json:"total_ms"`
	Status          int           `json:"http_status,omitempty"`
	Error           string        `json:"error,omitempty"`
}

// Probe compares the system resolver with a supplied tunnel resolver and times
// the real HTTPS path. No proxy, redirect following, credentials or page storage.
func Probe(ctx context.Context, target string, system, vpn *net.Resolver) ProbeResult {
	result := ProbeResult{URL: target}
	if u, err := url.Parse(target); err == nil {
		u.RawQuery = ""
		u.Fragment = ""
		result.URL = u.String()
	}
	req, e := http.NewRequestWithContext(ctx, "GET", target, nil)
	if e != nil {
		result.Error = e.Error()
		return result
	}
	lookup := func(resolver *net.Resolver) ([]string, int64, string) {
		start := time.Now()
		lookupCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		addresses, e := resolver.LookupHost(lookupCtx, req.URL.Hostname())
		message := ""
		if e != nil {
			message = e.Error()
		}
		return addresses, time.Since(start).Milliseconds(), message
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		result.SystemAddresses, result.SystemDNSMS, result.SystemDNSError = lookup(system)
	}()
	go func() { defer wg.Done(); result.VPNAddresses, result.VPNDNSMS, result.VPNDNSError = lookup(vpn) }()
	wg.Wait()
	dialer := &net.Dialer{Timeout: 5 * time.Second, Resolver: system}
	transport := &http.Transport{DialContext: dialer.DialContext, TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 8 * time.Second, DisableKeepAlives: true}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	start := time.Now()
	var mu sync.Mutex
	trace := &httptrace.ClientTrace{
		ConnectDone: func(network, addr string, err error) {
			mu.Lock()
			if err == nil {
				result.TCPMS = time.Since(start).Milliseconds()
			}
			mu.Unlock()
		},
		TLSHandshakeDone: func(_ tls.ConnectionState, err error) {
			mu.Lock()
			if err == nil {
				result.TLSMS = time.Since(start).Milliseconds()
			}
			mu.Unlock()
		},
		GotFirstResponseByte: func() { mu.Lock(); result.FirstByteMS = time.Since(start).Milliseconds(); mu.Unlock() },
	}
	req = req.WithContext(httptrace.WithClientTrace(ctx, trace))
	response, e := client.Do(req)
	if e != nil {
		result.Error = e.Error()
	} else {
		result.Status = response.StatusCode
		if location, err := response.Location(); err == nil && location.User == nil {
			result.nextLocation = location.String()
			location.RawQuery = ""
			location.Fragment = ""
			result.Location = location.String()
		}
		_, e = io.Copy(io.Discard, io.LimitReader(response.Body, 32768))
		response.Body.Close()
		if e != nil {
			result.Error = e.Error()
		}
	}
	mu.Lock()
	result.TotalMS = time.Since(start).Milliseconds()
	mu.Unlock()
	return result
}

func ProbeSites(ctx context.Context, dns string) []ProbeResult {
	resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(dns, "53"))
	}}
	targets := []string{"https://xlcp.tongji.edu.cn/user/web-v2", "https://ping0.cc"}
	results := make([]ProbeResult, len(targets))
	var wg sync.WaitGroup
	for i, target := range targets {
		wg.Add(1)
		go func(i int, target string) {
			defer wg.Done()
			results[i] = Probe(ctx, target, net.DefaultResolver, resolver)
			seen := map[string]bool{target: true}
			next := results[i].nextLocation
			for hops := 0; next != "" && hops < 5 && ctx.Err() == nil; hops++ {
				u, err := url.Parse(next)
				if err != nil || (u.Scheme != "https" && u.Scheme != "http") || (u.Hostname() != "xlcp.tongji.edu.cn" && !strings.HasSuffix(u.Hostname(), ".tongji.edu.cn") && u.Hostname() != "ping0.cc") {
					break
				}
				if seen[next] {
					results[i].Error = "检测到重定向循环"
					break
				}
				seen[next] = true
				hop := Probe(ctx, next, net.DefaultResolver, resolver)
				results[i].Redirects = append(results[i].Redirects, hop)
				next = hop.nextLocation
			}
		}(i, target)
	}
	wg.Wait()
	return results
}

func ProbeSummary(results []ProbeResult) string {
	message := "连接诊断："
	for _, r := range results {
		state := fmt.Sprintf("HTTP %d，%d ms", r.Status, r.TotalMS)
		if r.Error != "" {
			state = r.Error
		}
		message += fmt.Sprintf("\n%s：%s；系统 DNS %d ms，VPN DNS %d ms", r.URL, state, r.SystemDNSMS, r.VPNDNSMS)
		for _, hop := range r.Redirects {
			status := fmt.Sprintf("HTTP %d，%d ms", hop.Status, hop.TotalMS)
			if hop.Error != "" {
				status = hop.Error
			}
			message += fmt.Sprintf("\n  → %s：%s", hop.URL, status)
		}
	}
	return message
}
