package client

import (
	"context"
	"io"
	"net"
	"os"
	"testing"
	"time"
)

func TestSoakBeyondPreviousDisconnect(t *testing.T) {
	if os.Getenv("MOTIONVPN_SOAK") != "1" {
		t.Skip("set MOTIONVPN_SOAK=1 for 110 second soak")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 110*time.Second)
	defer cancel()
	a, b := net.Pipe()
	c, d := net.Pipe()
	defer b.Close()
	defer d.Close()
	stats := &BridgeStats{}
	done := make(chan error, 1)
	go func() { done <- Bridge(ctx, a, a, c, 30*time.Second, stats) }()
	go func() {
		for {
			p, e := ReadPacket(b)
			if e != nil {
				return
			}
			if writeAll(b, p) != nil {
				return
			}
		}
	}()
	go func() {
		timer := time.NewTicker(5 * time.Millisecond)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				if WriteFrame(d, packet(1)) != nil {
					return
				}
			}
		}
	}()
	go io.Copy(io.Discard, d)
	<-done
	if stats.Received.Load() < 10000 {
		t.Fatalf("insufficient sustained traffic: %d", stats.Received.Load())
	}
	t.Logf("110-second soak passed: sent=%d received=%d heartbeats=%d", stats.Sent.Load(), stats.Received.Load(), stats.Heartbeats.Load())
}
