package main

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// fakeDNS answers every A query after delay with ip, or with rcode when ip is
// empty; silent drops every query.
func fakeDNS(t *testing.T, delay time.Duration, ip string, rc dnsmessage.RCode, silent bool) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		b := make([]byte, 2048)
		for {
			n, addr, err := pc.ReadFrom(b)
			if err != nil {
				return
			}
			if silent {
				continue
			}
			var m dnsmessage.Message
			if m.Unpack(b[:n]) != nil {
				continue
			}
			m.Header.Response = true
			m.Header.RCode = rc
			if ip != "" && m.Questions[0].Type == dnsmessage.TypeA {
				a := net.ParseIP(ip).To4()
				m.Answers = []dnsmessage.Resource{{
					Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
					Body:   &dnsmessage.AResource{A: [4]byte(a)},
				}}
			}
			out, _ := m.Pack()
			go func(addr net.Addr) {
				time.Sleep(delay)
				pc.WriteTo(out, addr)
			}(addr)
		}
	}()
	return pc.LocalAddr().String()
}

func raceResolver(servers ...string) *net.Resolver {
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		return dialRace(ctx, servers)
	}}
}

func lookup(t *testing.T, r *net.Resolver) ([]net.IP, error, time.Duration) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	start := time.Now()
	ips, err := r.LookupIP(ctx, "ip4", "peer.example.")
	return ips, err, time.Since(start)
}

func TestSilentServerCostsNothing(t *testing.T) {
	silent := fakeDNS(t, 0, "", 0, true)
	good := fakeDNS(t, 20*time.Millisecond, "192.0.2.7", 0, false)
	ips, err, took := lookup(t, raceResolver(silent, good))
	if err != nil || len(ips) != 1 || !ips[0].Equal(net.ParseIP("192.0.2.7")) {
		t.Fatalf("got %v %v", ips, err)
	}
	if took > time.Second {
		t.Fatalf("took %v: the silent server was waited for", took)
	}
}

func TestPositiveBeatsQuickNXDOMAIN(t *testing.T) {
	public := fakeDNS(t, 0, "", dnsmessage.RCodeNameError, false)
	internal := fakeDNS(t, 80*time.Millisecond, "10.1.2.3", 0, false)
	ips, err, _ := lookup(t, raceResolver(public, internal))
	if err != nil || len(ips) != 1 || !ips[0].Equal(net.ParseIP("10.1.2.3")) {
		t.Fatalf("got %v %v, want the internal answer", ips, err)
	}
}

func TestNXDOMAINWhenEveryoneSaysSo(t *testing.T) {
	a := fakeDNS(t, 0, "", dnsmessage.RCodeNameError, false)
	b := fakeDNS(t, 10*time.Millisecond, "", dnsmessage.RCodeNameError, false)
	_, err, took := lookup(t, raceResolver(a, b))
	var dnsErr *net.DNSError
	if err == nil || !asDNSError(err, &dnsErr) || !dnsErr.IsNotFound {
		t.Fatalf("got %v, want not found", err)
	}
	if took > time.Second {
		t.Fatalf("took %v", took)
	}
}

func TestServfailFallsToAnswer(t *testing.T) {
	broken := fakeDNS(t, 0, "", dnsmessage.RCodeServerFailure, false)
	good := fakeDNS(t, 50*time.Millisecond, "192.0.2.9", 0, false)
	ips, err, _ := lookup(t, raceResolver(broken, good))
	if err != nil || len(ips) != 1 {
		t.Fatalf("got %v %v", ips, err)
	}
}

func TestAllSilentTimesOut(t *testing.T) {
	a := fakeDNS(t, 0, "", 0, true)
	r := raceResolver(a)
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	_, err := r.LookupIP(ctx, "ip4", "peer.example.")
	if err == nil {
		t.Fatal("an answer from nobody")
	}
}

func asDNSError(err error, target **net.DNSError) bool {
	e, ok := err.(*net.DNSError)
	if ok {
		*target = e
	}
	return ok
}
