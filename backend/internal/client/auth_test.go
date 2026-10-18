package client

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestRejectedCredentialsSubmittedOnceAndLoggedOut(t *testing.T) {
	var login, logout atomic.Int32
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/prx/000/http/localhost/login" {
			login.Add(1)
			http.SetCookie(w, &http.Cookie{Name: "ANsession", Value: "private"})
			w.WriteHeader(200)
		} else if r.URL.Path == "/prx/000/http/localhost/logout" {
			logout.Add(1)
			w.WriteHeader(302)
		} else {
			t.Error("unexpected request")
		}
	}))
	defer srv.Close()
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	g := Gateway{Host: "example.com", Address: srv.Listener.Addr().String(), TLS: &tls.Config{RootCAs: pool, ServerName: "example.com"}}
	if _, e := g.Login(context.Background(), Credentials{Username: "user", Password: "password"}); e == nil {
		t.Fatal("rejected credentials accepted")
	}
	if login.Load() != 1 || logout.Load() != 1 {
		t.Fatal(login.Load(), logout.Load())
	}
}
func TestCertificateVerificationCannotBeBypassed(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("untrusted request reached handler") }))
	defer srv.Close()
	g := Gateway{Host: "example.com", Address: srv.Listener.Addr().String()}
	if c, e := g.dial(context.Background()); e == nil {
		c.Close()
		t.Fatal("untrusted certificate accepted")
	}
}
