package client

import (
	"bufio"
	"fmt"
	"io"
	"net"
)

// BufferedConn keeps bytes read ahead during the helper's JSON handshake.
// All connection lifecycle and deadline operations still use the underlying conn.
type BufferedConn struct {
	net.Conn
	Reader *bufio.Reader
}

func (c *BufferedConn) Read(p []byte) (int, error) { return c.Reader.Read(p) }

// ReadLine bounds handshake memory without discarding subsequent packet frames.
func ReadLine(r *bufio.Reader, limit int) ([]byte, error) {
	var line []byte
	for {
		part, e := r.ReadSlice('\n')
		if len(line)+len(part) > limit {
			return nil, fmt.Errorf("helper handshake exceeds %d bytes", limit)
		}
		line = append(line, part...)
		if e == bufio.ErrBufferFull {
			continue
		}
		if e != nil {
			return nil, e
		}
		if len(line) == 0 {
			return nil, io.ErrUnexpectedEOF
		}
		return line, nil
	}
}
