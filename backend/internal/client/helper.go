package client

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"golang.zx2c4.com/wireguard/tun"
	"io"
	"net"
	"os"
	"sync"
	"time"
)

type Receipt struct {
	Ready     bool     `json:"ready,omitempty"`
	Interface string   `json:"interface,omitempty"`
	Error     string   `json:"error,omitempty"`
	Clean     bool     `json:"cleanup_confirmed"`
	Failures  []string `json:"network_restore_failures,omitempty"`
}

func RunHelper(ctx context.Context, path, token string) (resultErr error) {
	if os.Geteuid() != 0 {
		return fmt.Errorf("需要管理员授权")
	}
	c, e := net.Dial("unix", path)
	if e != nil {
		return e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	fmt.Fprintln(c, token)
	r := bufio.NewReader(c)
	line, e := ReadLine(r, 65536)
	if e != nil {
		return e
	}
	var cfg NetworkConfig
	if e = json.Unmarshal(line, &cfg); e != nil {
		return e
	}
	c.SetDeadline(time.Time{})
	device, e := tun.CreateTUN("utun", 1280)
	if e != nil {
		json.NewEncoder(c).Encode(Receipt{Error: e.Error(), Clean: true})
		return e
	}
	defer device.Close()
	name, e := device.Name()
	if e != nil {
		return e
	}
	network := Network{Run: SystemRun, Config: cfg}
	defer func() {
		failures := network.Cleanup()
		receipt := Receipt{Clean: len(failures) == 0, Failures: failures}
		if resultErr != nil {
			receipt.Error = resultErr.Error()
		}
		data, _ := json.Marshal(receipt)
		os.WriteFile(path+".receipt", data, 0644)
	}()
	if e = network.Configure(name); e != nil {
		json.NewEncoder(c).Encode(Receipt{Error: e.Error()})
		return e
	}
	if e = json.NewEncoder(c).Encode(Receipt{Ready: true, Interface: name}); e != nil {
		return e
	}
	err := helperPump(ctx, c, r, device, network.Verify, func() {
		// Remove owned routes while their utun interface still exists.
		network.Routes.Rollback()
	})
	if err == io.EOF || err == context.Canceled {
		return nil
	}
	return err
}
func helperPump(ctx context.Context, c net.Conn, r io.Reader, d tun.Device, verify func() error, beforeClose ...func()) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	errs := make(chan error, 3)
	var wg sync.WaitGroup
	run := func(fn func() error) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case errs <- fn():
			case <-ctx.Done():
			}
		}()
	}
	run(func() error {
		for {
			c.SetReadDeadline(time.Now().Add(15 * time.Second))
			p, e := ReadFrame(r)
			if e != nil {
				return e
			}
			if len(p) > 0 {
				framed := make([]byte, 4+len(p))
				copy(framed[4:], p)
				if _, e = d.Write([][]byte{framed}, 4); e != nil {
					return e
				}
			}
		}
	})
	run(func() error {
		buf := [][]byte{make([]byte, 65575)}
		sizes := make([]int, 1)
		for {
			n, e := d.Read(buf, sizes, 4)
			if e != nil {
				return e
			}
			for i := 0; i < n; i++ {
				c.SetWriteDeadline(time.Now().Add(15 * time.Second))
				if e = WriteFrame(c, buf[i][4:4+sizes[i]]); e != nil {
					return e
				}
			}
		}
	})
	run(func() error {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-t.C:
				if e := verify(); e != nil {
					return e
				}
			}
		}
	})
	var e error
	select {
	case e = <-errs:
	case <-ctx.Done():
		e = ctx.Err()
	}
	cancel()
	c.SetDeadline(time.Now())
	for _, cleanup := range beforeClose {
		cleanup()
	}
	d.Close()
	c.SetDeadline(time.Now())
	wg.Wait()
	c.SetDeadline(time.Time{})
	return e
}
func VerifyToken(got, want string) bool {
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}
