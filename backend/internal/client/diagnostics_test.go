package client

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestProbeDoesNotFollowRedirectOrStoreBody(t *testing.T) {
	called := 0
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called++
		w.Header().Set("Location", "https://example.invalid/secret")
		w.WriteHeader(302)
		w.Write([]byte("private page"))
	}))
	defer s.Close()
	r := Probe(context.Background(), s.URL, net.DefaultResolver, net.DefaultResolver)
	if r.Status != 302 || r.Error != "" || called != 1 || r.Location != "https://example.invalid/secret" {
		t.Fatalf("%+v calls=%d", r, called)
	}
}
func TestProbeCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	r := Probe(ctx, "https://example.invalid", net.DefaultResolver, net.DefaultResolver)
	if r.Error == "" || time.Since(start) > time.Second {
		t.Fatalf("cancellation failed: %+v", r)
	}
}
func TestProbeRefusesUntrustedTLS(t *testing.T) {
	s := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("untrusted request sent") }))
	defer s.Close()
	r := Probe(context.Background(), s.URL, net.DefaultResolver, net.DefaultResolver)
	if r.Error == "" || r.Status != 0 {
		t.Fatal("untrusted TLS accepted")
	}
}
