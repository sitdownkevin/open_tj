package client

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type BridgeStats struct {
	UploadBytes   atomic.Int64
	DownloadBytes atomic.Int64
	Sent          atomic.Int64
	Received      atomic.Int64
	Heartbeats    atomic.Int64
}

// Bridge has independent readers and one bounded writer per connection. No
// read pump writes to its own peer while holding another connection's lock.
func Bridge(ctx context.Context, gateway net.Conn, reader io.Reader, helper net.Conn, interval time.Duration, stats *BridgeStats) error {
	if interval <= 0 {
		interval = 30 * time.Second
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	out := make(chan []byte, 256)
	local := make(chan []byte, 256)
	errs := make(chan error, 5)
	var wg sync.WaitGroup
	send := func(ch chan []byte, p []byte) bool {
		select {
		case ch <- p:
			return true
		case <-ctx.Done():
			return false
		}
	}
	run := func(fn func() error) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			e := fn()
			select {
			case errs <- e:
			case <-ctx.Done():
			}
		}()
	}
	run(func() error {
		for {
			select {
			case p := <-out:
				gateway.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if e := writeAll(gateway, p); e != nil {
					return e
				}
				stats.Sent.Add(1)
				if len(p) >= 20 && p[9] != 255 {
					stats.UploadBytes.Add(int64(len(p)))
				}
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	})
	run(func() error {
		for {
			select {
			case p := <-local:
				helper.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if e := WriteFrame(helper, p); e != nil {
					return e
				}
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	})
	run(func() error {
		lastReply := time.Time{}
		for {
			gateway.SetReadDeadline(time.Now().Add(4*interval + 10*time.Second))
			p, e := ReadPacket(reader)
			if e != nil {
				return e
			}
			stats.Received.Add(1)
			if p[9] != 255 {
				stats.DownloadBytes.Add(int64(len(p)))
			}
			if p[9] == 255 {
				cmd := binary.BigEndian.Uint16(p[12:])
				switch cmd {
				case 1:
					stats.Heartbeats.Add(1)
					if time.Since(lastReply) >= interval/2 {
						lastReply = time.Now()
						if !send(out, ATP(1, 0)) {
							return ctx.Err()
						}
					}
				case 2:
					return fmt.Errorf("网关终止会话，原因码 %d", binary.BigEndian.Uint32(p[16:]))
				case 4:
					if !send(out, ATP(5, 0)) {
						return ctx.Err()
					}
				}
			} else if !send(local, p) {
				return ctx.Err()
			}
		}
	})
	run(func() error {
		for {
			p, e := ReadFrame(helper)
			if e != nil {
				return e
			}
			if len(p) > 0 && !send(out, p) {
				return ctx.Err()
			}
		}
	})
	run(func() error {
		ping := time.NewTicker(interval)
		lease := time.NewTicker(3 * time.Second)
		defer ping.Stop()
		defer lease.Stop()
		for {
			select {
			case <-ping.C:
				if !send(out, ATP(1, 0)) {
					return ctx.Err()
				}
			case <-lease.C:
				if !send(local, nil) {
					return ctx.Err()
				}
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	})
	var err error
	select {
	case err = <-errs:
	case <-ctx.Done():
		err = ctx.Err()
	}
	cancel()
	gateway.Close()
	helper.Close()
	wg.Wait()
	return err
}
