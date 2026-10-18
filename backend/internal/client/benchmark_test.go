package client

import (
	"context"
	"net"
	"testing"
	"time"
)

func BenchmarkBridgeRoundTrip(b *testing.B) {
	a, g := net.Pipe()
	c, h := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Bridge(ctx, a, a, c, time.Hour, &BridgeStats{}) }()
	go func() {
		for {
			p, e := ReadPacket(g)
			if e != nil {
				return
			}
			if writeAll(g, p) != nil {
				return
			}
		}
	}()
	p := packet(1)
	b.SetBytes(int64(len(p) * 2))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if e := WriteFrame(h, p); e != nil {
			b.Fatal(e)
		}
		if _, e := ReadFrame(h); e != nil {
			b.Fatal(e)
		}
	}
	b.StopTimer()
	cancel()
	g.Close()
	h.Close()
	<-done
}
