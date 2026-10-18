package client

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
)

func writeAll(w io.Writer, p []byte) error {
	for len(p) > 0 {
		n, e := w.Write(p)
		if e != nil {
			return e
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		p = p[n:]
	}
	return nil
}
func ATP(command uint16, parameter uint32) []byte {
	p := make([]byte, 20)
	p[0] = 0x45
	p[9] = 255
	binary.BigEndian.PutUint16(p[2:], 20)
	binary.BigEndian.PutUint16(p[12:], command)
	binary.BigEndian.PutUint32(p[16:], parameter)
	return p
}
func ReadPacket(r io.Reader) ([]byte, error) {
	h := make([]byte, 20)
	if _, e := io.ReadFull(r, h); e != nil {
		return nil, e
	}
	if h[0]>>4 != 4 || int(h[0]&15)*4 < 20 {
		return nil, fmt.Errorf("invalid IPv4 header")
	}
	n := int(binary.BigEndian.Uint16(h[2:]))
	if n < int(h[0]&15)*4 {
		return nil, fmt.Errorf("invalid IPv4 length")
	}
	p := make([]byte, n)
	copy(p, h)
	_, e := io.ReadFull(r, p[20:])
	return p, e
}
func WriteFrame(w io.Writer, p []byte) error {
	if len(p) > 65535 {
		return fmt.Errorf("frame too large")
	}
	h := make([]byte, 4)
	binary.BigEndian.PutUint32(h, uint32(len(p)))
	if e := writeAll(w, h); e != nil {
		return e
	}
	return writeAll(w, p)
}
func ReadFrame(r io.Reader) ([]byte, error) {
	var h [4]byte
	if _, e := io.ReadFull(r, h[:]); e != nil {
		return nil, e
	}
	n := binary.BigEndian.Uint32(h[:])
	if n > 65535 || (n > 0 && n < 20) {
		return nil, fmt.Errorf("invalid frame length %d", n)
	}
	p := make([]byte, n)
	_, e := io.ReadFull(r, p)
	return p, e
}
func readConfig(r io.Reader, kind byte) (map[string]json.RawMessage, error) {
	h := make([]byte, 16)
	if _, e := io.ReadFull(r, h); e != nil {
		return nil, e
	}
	n := binary.LittleEndian.Uint32(h[12:])
	if h[0] != kind || n > 1024*1024 {
		return nil, fmt.Errorf("invalid config header")
	}
	p := make([]byte, n)
	if _, e := io.ReadFull(r, p); e != nil {
		return nil, e
	}
	for len(p) > 0 && p[len(p)-1] == 0 {
		p = p[:len(p)-1]
	}
	var m map[string]json.RawMessage
	e := json.Unmarshal(p, &m)
	return m, e
}
func configRequest(kind byte, payload []byte) []byte {
	p := make([]byte, 16+len(payload))
	p[0] = kind
	binary.LittleEndian.PutUint32(p[12:], uint32(len(payload)))
	copy(p[16:], payload)
	return p
}
func IPv4(raw json.RawMessage) (string, error) {
	var s string
	if json.Unmarshal(raw, &s) == nil && net.ParseIP(s).To4() != nil {
		return s, nil
	}
	var n uint32
	if e := json.Unmarshal(raw, &n); e != nil {
		return "", e
	}
	p := make(net.IP, 4)
	binary.LittleEndian.PutUint32(p, n)
	return p.String(), nil
}
