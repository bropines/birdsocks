package main

// resolver.go — name resolution for a daemon that is a Linux binary on
// Android. It is built for GOOS=linux, not android: NetBird's android code
// paths belong to its own app's library (a TUN fd, DNS and network callbacks
// from Java) and fail in a daemon, while its Linux netstack mode is the
// supported way to run without privileges. The price is Go's pure resolver,
// which reads /etc/resolv.conf, and Android has none: it would ask
// 127.0.0.1:53 and get nothing. So the app writes the network's DNS servers
// into a file, one address per line, and every lookup that has no tunnel
// resolver of its own asks them.

import (
	"bufio"
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/netbirdio/netbird/client/iface/netstack"
)

// fallbackDNS is asked when the app has written no servers yet.
var fallbackDNS = []string{"8.8.8.8:53", "1.1.1.1:53"}

type dnsFile struct {
	path string

	mu      sync.Mutex
	mtime   time.Time
	servers []string
}

// current is the server list, re-read when the app has rewritten the file.
func (f *dnsFile) current() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	st, err := os.Stat(f.path)
	if err != nil {
		return fallbackDNS
	}
	if !st.ModTime().Equal(f.mtime) || f.servers == nil {
		f.servers = readDNSServers(f.path)
		f.mtime = st.ModTime()
	}
	if len(f.servers) == 0 {
		return fallbackDNS
	}
	return f.servers
}

func readDNSServers(path string) []string {
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	servers := []string{}
	sc := bufio.NewScanner(file)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if ap, err := netip.ParseAddrPort(line); err == nil {
			servers = append(servers, ap.String())
		} else if ip, err := netip.ParseAddr(line); err == nil {
			// A link-local server needs its zone, which the app writes as fe80::1%wlan0.
			servers = append(servers, netip.AddrPortFrom(ip, 53).String())
		}
	}
	return servers
}

// installResolver points Go's resolver, for this whole process, at the
// servers in path, and hands the same servers to the DNS proxy as its
// fallback. A query over UDP goes to all of them at once (dnsrace.go); the
// TCP retry of a truncated answer tries them in turn.
func installResolver(path string) {
	f := &dnsFile{path: path}
	netstack.SetFallbackDNS(f.current)
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			servers := f.current()
			if strings.HasPrefix(network, "udp") {
				return dialRace(ctx, servers)
			}
			var d net.Dialer
			var errs []error
			for _, s := range servers {
				conn, err := d.DialContext(ctx, network, s)
				if err == nil {
					return conn, nil
				}
				errs = append(errs, err)
			}
			return nil, errors.Join(errs...)
		},
	}
}
