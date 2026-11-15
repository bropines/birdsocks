//go:build linux

package main

// roottun_hosts.go — NetBird names in /etc/hosts, so they resolve whatever
// DNS server Android's resolver picks. The port-53 redirect cannot cover
// every case: on a kernel without IPv6 NAT, Android's resolver may keep to
// the router's IPv6 server (fe80::1) for half an hour after its IPv4 servers
// failed, and a query there never reaches NetBird. The resolver reads hosts
// before any server, IPv4 or IPv6, validated network or not.
//
// A copy of the visible hosts file plus our names is written to the state
// directory and bind-mounted over /system/etc/hosts. Only our own bind is
// ever unmounted — recognised by the marker line, or by the mount's source —
// so another module's hosts (systemless hosts, an ad blocker) stays the base.

import (
	"bytes"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"golang.org/x/sys/unix"
)

const systemHosts = "/system/etc/hosts"

// hostEntry is one address and the names it answers to.
type hostEntry struct {
	IP    string   `json:"ip"`
	Names []string `json:"names"`
}

var hostName = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*\.?$`)

// reservedNames are never published: a peer calling itself one of these would
// take a name the device relies on.
var reservedNames = map[string]bool{"localhost": true, "localhost.localdomain": true, "ip6-localhost": true, "ip6-loopback": true, "wpad": true, "broadcasthost": true}

// hostsLines: the entries as hosts lines, checked and sorted, so equal input
// writes an equal file.
func hostsLines(entries []hostEntry) []string {
	var out []string
	for _, e := range entries {
		a, err := netip.ParseAddr(strings.TrimSpace(e.IP))
		if err != nil {
			continue
		}
		var names []string
		seen := map[string]bool{}
		for _, n := range e.Names {
			n = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(n), "."))
			if n == "" || reservedNames[n] || !hostName.MatchString(n) || seen[n] {
				continue
			}
			seen[n] = true
			names = append(names, n)
		}
		if len(names) == 0 {
			continue
		}
		out = append(out, a.Unmap().String()+" "+strings.Join(names, " "))
	}
	sort.Strings(out)
	return out
}

func hostsFile(cfg rootConfig) string {
	if cfg.StateDir == "" {
		return ""
	}
	return filepath.Join(cfg.StateDir, "hosts")
}

// hostsMarker names our copy, so the release app and the debug build never
// take each other's for their own.
func hostsMarker(file string) string { return "# BirdSocks NetBird names, " + file }

// publishHosts makes the visible hosts file our names plus whatever was
// there; with no names, takes ours away. lines is what the last call wrote.
func (r *rootTun) publishHosts(cfg rootConfig) {
	file := hostsFile(cfg)
	if file == "" {
		return
	}
	lines := hostsLines(cfg.Hosts)
	sig := strings.Join(lines, "\n")
	if sig == r.hostsSig && (len(lines) == 0 || r.hostsBound) {
		return
	}
	unbindHosts(file)
	r.hostsBound, r.hostsSig = false, sig
	if len(lines) == 0 {
		return
	}
	base, err := os.ReadFile(systemHosts)
	if err != nil {
		base = []byte("127.0.0.1 localhost\n::1 ip6-localhost\n")
	}
	var b bytes.Buffer
	b.Write(base)
	if len(base) > 0 && base[len(base)-1] != '\n' {
		b.WriteByte('\n')
	}
	b.WriteString(hostsMarker(file) + "\n")
	for _, l := range lines {
		b.WriteString(l + "\n")
	}
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		say("warn: hosts: %v", err)
		return
	}
	tmp := file + ".tmp"
	if err := os.WriteFile(tmp, b.Bytes(), 0o644); err != nil {
		say("warn: hosts: %v", err)
		return
	}
	_ = os.Chmod(tmp, 0o644)
	_ = os.Rename(tmp, file)
	// Apps read it through the system's label; the state directory's would be denied.
	_ = run("/system/bin/chcon", "u:object_r:system_file:s0", file)
	if err := unix.Mount(file, systemHosts, "", unix.MS_BIND, ""); err != nil {
		say("warn: hosts: bind over %s: %v", systemHosts, err)
		return
	}
	r.hostsBound = true
	say("info: %d NetBird name(s) in %s", len(lines), systemHosts)
}

// unbindHosts unmounts what is bound over /system/etc/hosts while it is ours,
// a few stacked layers at most, and nothing else.
func unbindHosts(file string) {
	if file == "" {
		return
	}
	marker := []byte(hostsMarker(file))
	for i := 0; i < 4; i++ {
		ours := topHostsMountIs(file)
		if !ours {
			if b, err := os.ReadFile(systemHosts); err != nil || !bytes.Contains(b, marker) {
				return
			}
		}
		if err := unix.Unmount(systemHosts, 0); err != nil {
			return
		}
	}
}

// topHostsMountIs: the topmost mount on /system/etc/hosts has file as its
// source (mountinfo's root field: the path inside its filesystem, /data cut off).
func topHostsMountIs(file string) bool {
	b, err := os.ReadFile("/proc/self/mountinfo")
	if err != nil {
		return false
	}
	top := ""
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) > 4 && f[4] == systemHosts {
			top = f[3]
		}
	}
	return top != "" && (top == file || top == strings.TrimPrefix(file, "/data"))
}
