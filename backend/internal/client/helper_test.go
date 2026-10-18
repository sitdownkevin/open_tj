package client

import (
	"context"
	"errors"
	"golang.zx2c4.com/wireguard/tun"
	"io"
	"net"
	"os"
	"sync"
	"testing"
	"time"
)

type fakeDevice struct {
	incoming, outgoing chan []byte
	closed             chan struct{}
	once               sync.Once
}

func newFakeDevice() *fakeDevice {
	return &fakeDevice{make(chan []byte, 8), make(chan []byte, 8), make(chan struct{}), sync.Once{}}
}
func (d *fakeDevice) File() *os.File { return nil }
func (d *fakeDevice) Read(b [][]byte, s []int, o int) (int, error) {
	if o < 4 {
		return 0, io.ErrShortBuffer
	}
	select {
	case p := <-d.outgoing:
		copy(b[0][o:], p)
		s[0] = len(p)
		return 1, nil
	case <-d.closed:
		return 0, io.EOF
	}
}
func (d *fakeDevice) Write(b [][]byte, o int) (int, error) {
	if o < 4 {
		return 0, io.ErrShortBuffer
	}
	p := append([]byte{}, b[0][o:]...)
	select {
	case d.incoming <- p:
		return 1, nil
	case <-d.closed:
		return 0, io.EOF
	}
}
func (d *fakeDevice) Close() error             { d.once.Do(func() { close(d.closed) }); return nil }
func (d *fakeDevice) MTU() (int, error)        { return 1280, nil }
func (d *fakeDevice) Name() (string, error)    { return "utun-test", nil }
func (d *fakeDevice) Events() <-chan tun.Event { return nil }
func (d *fakeDevice) BatchSize() int           { return 1 }
func TestHelperDuplexAndParentDeath(t *testing.T) {
	a, b := net.Pipe()
	d := newFakeDevice()
	done := make(chan error, 1)
	go func() { done <- helperPump(context.Background(), a, a, d, func() error { return nil }) }()
	p := packet(42)
	if e := WriteFrame(b, p); e != nil {
		t.Fatal(e)
	}
	select {
	case got := <-d.incoming:
		if len(got) != 1280 {
			t.Fatal(len(got))
		}
	case <-time.After(time.Second):
		t.Fatal("packet not delivered")
	}
	d.outgoing <- p
	if _, e := ReadFrame(b); e != nil {
		t.Fatal(e)
	}
	b.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("helper survived parent death")
	}
}
func TestHelperGuardFailureStopsForwarding(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	d := newFakeDevice()
	done := make(chan error, 1)
	go func() {
		done <- helperPump(context.Background(), a, a, d, func() error { return errors.New("injected IPv6 conflict") })
	}()
	select {
	case e := <-done:
		if e.Error() != "injected IPv6 conflict" {
			t.Fatal(e)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("guard failed to stop helper")
	}
}
func TestBridgeIdleKeepaliveNoEchoStorm(t *testing.T) {
	a, b := net.Pipe()
	c, d := net.Pipe()
	ctx, cancel := context.WithTimeout(context.Background(), 350*time.Millisecond)
	defer cancel()
	defer b.Close()
	defer d.Close()
	done := make(chan error, 1)
	stats := &BridgeStats{}
	go func() { done <- Bridge(ctx, a, a, c, 100*time.Millisecond, stats) }()
	go func() {
		for {
			p, e := ReadPacket(b)
			if e != nil {
				return
			}
			if p[9] == 255 {
				if writeAll(b, p) != nil {
					return
				}
			}
		}
	}()
	go io.Copy(io.Discard, d)
	<-done
	if n := stats.Heartbeats.Load(); n == 0 || n > 15 {
		t.Fatalf("heartbeat echo count %d", n)
	}
}
