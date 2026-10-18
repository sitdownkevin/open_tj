package client

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestOwnedRoutesRemovedBeforeDeviceClose(t *testing.T) {
	a, b := net.Pipe()
	d := newFakeDevice()
	done := make(chan error, 1)
	called := make(chan bool, 1)
	go func() {
		done <- helperPump(context.Background(), a, a, d, func() error { return nil }, func() {
			select {
			case <-d.closed:
				called <- false
			default:
				called <- true
			}
		})
	}()
	b.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cleanup hung")
	}
	if !<-called {
		t.Fatal("device closed before route cleanup")
	}
	select {
	case <-d.closed:
	default:
		t.Fatal("device not closed after cleanup")
	}
}
