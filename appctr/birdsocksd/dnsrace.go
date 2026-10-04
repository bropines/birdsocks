package main

import (
	"context"
	"errors"
	"net"
	"os"
	"sync"
	"time"
)

// negativeGrace is how long a negative answer (NXDOMAIN, or no records) waits
// for another server to answer better: a network that mixes an internal
// resolver with a public one gets the internal name, not the public "no".
const negativeGrace = 250 * time.Millisecond

// dialRace returns a connection that sends each DNS query to every server at
// once and reads back the first useful answer. Go's resolver asks one server
// at a time and gives each five seconds: on weak Wi-Fi a single lost datagram
// cost a whole connect five seconds, and a server that never answers cost it
// on every lookup it was picked for.
func dialRace(ctx context.Context, servers []string) (net.Conn, error) {
	var d net.Dialer
	rc := &raceConn{done: make(chan struct{})}
	var errs []error
	for _, s := range servers {
		c, err := d.DialContext(ctx, "udp", s)
		if err != nil {
			// A link-local server whose zone is not known yet fails here, at once.
			errs = append(errs, err)
			continue
		}
		rc.conns = append(rc.conns, c)
	}
	if len(rc.conns) == 0 {
		return nil, errors.Join(errs...)
	}
	rc.pkts = make(chan []byte, 2*len(rc.conns))
	for _, c := range rc.conns {
		go rc.readLoop(c)
	}
	return rc, nil
}

// raceConn is a net.PacketConn, so Go's resolver frames DNS over it as UDP.
type raceConn struct {
	conns []net.Conn
	pkts  chan []byte // answers; nil when a server's socket failed
	done  chan struct{}
	once  sync.Once

	mu       sync.Mutex
	deadline time.Time

	// Read state, across the resolver's Read calls for one query.
	negative []byte // first NXDOMAIN or empty answer
	failure  []byte // first SERVFAIL, REFUSED or other error answer
	settled  int    // servers that answered without a positive answer, or failed
	grace    <-chan time.Time
}

func (rc *raceConn) readLoop(c net.Conn) {
	for {
		b := make([]byte, 2048)
		n, err := c.Read(b)
		var p []byte
		if err == nil {
			p = b[:n]
		}
		select {
		case rc.pkts <- p:
		case <-rc.done:
			return
		}
		if err != nil {
			return
		}
	}
}

func (rc *raceConn) Read(b []byte) (int, error) {
	var timeout <-chan time.Time
	rc.mu.Lock()
	d := rc.deadline
	rc.mu.Unlock()
	if !d.IsZero() {
		t := time.NewTimer(time.Until(d))
		defer t.Stop()
		timeout = t.C
	}
	for {
		select {
		case p := <-rc.pkts:
			switch {
			case p == nil:
				rc.settled++
			case len(p) < 12:
				continue
			case positive(p):
				return copy(b, p), nil
			case rcode(p) == 0 || rcode(p) == 3:
				if rc.negative == nil {
					rc.negative = p
					rc.grace = time.After(negativeGrace)
				}
				rc.settled++
			default:
				if rc.failure == nil {
					rc.failure = p
				}
				rc.settled++
			}
			if rc.settled >= len(rc.conns) {
				return rc.best(b)
			}
		case <-rc.grace:
			return rc.best(b)
		case <-timeout:
			if rc.negative != nil || rc.failure != nil {
				return rc.best(b)
			}
			return 0, os.ErrDeadlineExceeded
		case <-rc.done:
			return 0, net.ErrClosed
		}
	}
}

// best is the held answer: a negative one before a failure.
func (rc *raceConn) best(b []byte) (int, error) {
	p := rc.negative
	if p == nil {
		p = rc.failure
	}
	if p == nil {
		return 0, errors.New("no DNS server answered")
	}
	// Go may Read again after an answer it rejects; start over then.
	rc.negative, rc.failure, rc.settled, rc.grace = nil, nil, 0, nil
	return copy(b, p), nil
}

// rcode is the response code in a DNS header; positive, an answer with records.
func rcode(p []byte) int { return int(p[3] & 0x0f) }

func positive(p []byte) bool { return rcode(p) == 0 && (p[6] != 0 || p[7] != 0) }

func (rc *raceConn) Write(b []byte) (int, error) {
	var first error
	sent := false
	for _, c := range rc.conns {
		if _, err := c.Write(b); err != nil {
			if first == nil {
				first = err
			}
			continue
		}
		sent = true
	}
	if !sent {
		return 0, first
	}
	return len(b), nil
}

func (rc *raceConn) Close() error {
	rc.once.Do(func() {
		close(rc.done)
		for _, c := range rc.conns {
			c.Close()
		}
	})
	return nil
}

func (rc *raceConn) SetDeadline(t time.Time) error {
	rc.mu.Lock()
	rc.deadline = t
	rc.mu.Unlock()
	return nil
}
func (rc *raceConn) SetReadDeadline(t time.Time) error { return rc.SetDeadline(t) }
func (rc *raceConn) SetWriteDeadline(time.Time) error  { return nil }
func (rc *raceConn) LocalAddr() net.Addr               { return rc.conns[0].LocalAddr() }
func (rc *raceConn) RemoteAddr() net.Addr              { return rc.conns[0].RemoteAddr() }

func (rc *raceConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, err := rc.Read(b)
	return n, rc.RemoteAddr(), err
}
func (rc *raceConn) WriteTo(b []byte, _ net.Addr) (int, error) { return rc.Write(b) }
