package client

import (
	"bufio"
	"bytes"
	"context"
	"net"
	"strings"
	"testing"
	"time"
)

func TestHelperReadyAndFirstPacketInSameRead(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	var wire bytes.Buffer
	wire.WriteString("{\"ready\":true}\n")
	p := packet(123)
	WriteFrame(&wire, p)
	go func() { writeAll(b, wire.Bytes()) }()
	r := bufio.NewReader(a)
	if _, e := ReadLine(r, 65536); e != nil {
		t.Fatal(e)
	}
	if r.Buffered() == 0 {
		t.Fatal("fixture did not buffer packet")
	}
	gateway, remote := net.Pipe()
	defer remote.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- Bridge(ctx, gateway, gateway, &BufferedConn{Conn: a, Reader: r}, time.Hour, &BridgeStats{})
	}()
	remote.SetReadDeadline(time.Now().Add(time.Second))
	got, e := ReadPacket(remote)
	if e != nil || !bytes.Equal(got, p) {
		t.Fatalf("first buffered packet lost: %v", e)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("shutdown hung")
	}
}
func TestHandshakeLineLimit(t *testing.T) {
	for _, size := range []int{64, 8192} {
		r := bufio.NewReader(strings.NewReader(strings.Repeat("x", size) + "\n"))
		if _, e := ReadLine(r, 32); e == nil {
			t.Fatal("unbounded line accepted")
		}
	}
	if _, e := ReadLine(bufio.NewReader(strings.NewReader("truncated")), 32); e == nil {
		t.Fatal("truncated line accepted")
	}
}
func TestDisabledServiceDoesNotReusePreviousName(t *testing.T) {
	run := func(...string) (string, error) {
		return "(1) Wi-Fi\n(Hardware Port: Wi-Fi, Device: en0)\n(*) Ethernet\n(Hardware Port: Ethernet, Device: en5)", nil
	}
	if _, e := ActiveService(run, "en5"); e == nil {
		t.Fatal("disabled service matched Wi-Fi")
	}
}
func TestInvalidHelperConfigNeverRunsCommands(t *testing.T) {
	calls := 0
	n := Network{Run: func(...string) (string, error) { calls++; return "", nil }}
	if e := n.Configure("utun9"); e == nil {
		t.Fatal("missing config accepted")
	}
	if calls != 0 {
		t.Fatal("invalid config executed commands")
	}
}
