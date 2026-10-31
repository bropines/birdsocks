//go:build linux

package main

// roottun.go — the root engine of VPN mode: a helper the app runs through
// `su` (`libnetbird.so -root-tun`). It creates a real TUN interface on the
// device's NetBird address, hands it to the daemon over its VPN socket
// (netstack/vpn.go, the same way the native engine hands over Android's VPN
// interface), and steers traffic into it with Linux policy routing — so
// VPN mode works without Android's VPN slot, beside any VPN app.
//
// The app writes one JSON config per line on stdin; a later line updates the
// routes. The helper lives as long as stdin: EOF, a signal, or the interface
// vanishing (the daemon died) takes every rule it made away again, so a dead
// daemon never leaves the device routing into nothing.
//
// What it installs, all of it owned by the helper and removed on exit:
//   - table <table>: the NetBird ranges, the routes NetBird selected, the
//     VPN's resolver, a default route behind an exit node; throw routes for
//     the excluded ranges;
//   - rule <pref>: locally generated traffic not protected from VPNs
//     (fwmark 0x20000) looks that table up first;
//   - rule <pref-1>: the app's own UIDs and the excluded apps skip it, going
//     on to netd's rules;
//   - nat OUTPUT → BIRDSOCKS_DNS: port 53 to the VPN's resolver, except the
//     daemon's own queries; IPv6 port 53 is refused where the kernel has no
//     IPv6 nat, so resolvers move on to an IPv4 server.

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const (
	rootDNSChain  = "BIRDSOCKS_DNS"
	rootDNS6Chain = "BIRDSOCKS_DNS6"
	// protectMark is Android's PROTECT_FROM_VPN fwmark bit: sockets that
	// must not go through a VPN (other VPN apps' own sockets) keep off ours.
	protectMark = "0x20000/0x20000"
)

// rootConfig is one line from the app.
type rootConfig struct {
	Op     string   `json:"op"` // "" (apply) or "stop"
	Name   string   `json:"name"`
	MTU    int      `json:"mtu"`
	Addrs  []string `json:"addrs"`
	Routes []string `json:"routes"`
	// Default4/6: a default route into the interface (exit node, domain route, route all).
	Default4     bool     `json:"default4"`
	Default6     bool     `json:"default6"`
	ExcludeUIDs  []int    `json:"exclude_uids"`
	ExcludeCIDRs []string `json:"exclude_cidrs"`
	// AppUID is the daemon's: its own DNS queries are not redirected.
	AppUID    int    `json:"app_uid"`
	AppDomain string `json:"app_domain"`
	Table     int    `json:"table"`
	Pref      int    `json:"pref"`
	DNS       string `json:"dns"`
	Socket    string `json:"socket"`
}

func (c rootConfig) validate() error {
	switch {
	case c.Name == "" || len(c.Name) > 15 || strings.ContainsAny(c.Name, " /\t\n"):
		return fmt.Errorf("interface name %q", c.Name)
	case c.MTU < 576 || c.MTU > 65535:
		return fmt.Errorf("MTU %d", c.MTU)
	case len(c.Addrs) == 0:
		return errors.New("no address")
	case c.Table < 1 || c.Table > 0x7fffffff || c.Table == 253 || c.Table == 254 || c.Table == 255:
		return fmt.Errorf("table %d", c.Table)
	case c.Pref < 2 || c.Pref >= 9000:
		return fmt.Errorf("rule priority %d", c.Pref)
	case c.Socket == "":
		return errors.New("no VPN socket")
	}
	for _, a := range c.Addrs {
		if _, err := netip.ParseAddr(a); err != nil {
			return fmt.Errorf("address %q", a)
		}
	}
	for _, r := range append(append([]string{}, c.Routes...), c.ExcludeCIDRs...) {
		if _, err := netip.ParsePrefix(r); err != nil {
			return fmt.Errorf("route %q", r)
		}
	}
	if c.DNS != "" {
		if _, err := netip.ParseAddr(c.DNS); err != nil {
			return fmt.Errorf("DNS %q", c.DNS)
		}
	}
	return nil
}

// sameLink: two configs share the interface itself; only routes differ.
func (c rootConfig) sameLink(o rootConfig) bool {
	return c.Name == o.Name && c.MTU == o.MTU && strings.Join(c.Addrs, ",") == strings.Join(o.Addrs, ",") &&
		c.Socket == o.Socket && c.Table == o.Table && c.Pref == o.Pref
}

func (c rootConfig) v4() (netip.Addr, bool) {
	for _, a := range c.Addrs {
		if ip := netip.MustParseAddr(a); ip.Is4() {
			return ip, true
		}
	}
	return netip.Addr{}, false
}

func (c rootConfig) v6() (netip.Addr, bool) {
	for _, a := range c.Addrs {
		if ip := netip.MustParseAddr(a); ip.Is6() {
			return ip, true
		}
	}
	return netip.Addr{}, false
}

// rootTun is the helper's state.
type rootTun struct {
	mu  sync.Mutex
	cur *rootConfig
	// installed remembers what to take away.
	rules4, rules6 [][]string
	dns4, dns6     bool
}

func say(format string, args ...any) { fmt.Fprintf(os.Stdout, format+"\n", args...) }

// runRootTun is the helper's main: it returns the process exit code.
func runRootTun() int {
	if os.Geteuid() != 0 {
		say("error: the root engine needs root")
		return 2
	}
	r := &rootTun{}
	lines := make(chan string)
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
	}()
	sigs := make(chan os.Signal, 2)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case line, ok := <-lines:
			if !ok {
				r.teardown("the app went away")
				return 0
			}
			if strings.TrimSpace(line) == "" {
				continue
			}
			var cfg rootConfig
			if err := json.Unmarshal([]byte(line), &cfg); err != nil {
				say("error: config: %v", err)
				continue
			}
			if cfg.Op == "stop" {
				r.teardown("stopped")
				return 0
			}
			if err := r.apply(cfg); err != nil {
				say("error: %v", err)
				r.teardown("setup failed")
				return 1
			}
		case s := <-sigs:
			r.teardown("signal " + s.String())
			return 0
		case <-tick.C:
			if r.linkGone() {
				r.teardown("the interface went away (the daemon let go of it)")
				return 3
			}
		}
	}
}

func (r *rootTun) linkGone() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil {
		return false
	}
	_, err := os.Stat("/sys/class/net/" + r.cur.Name)
	return errors.Is(err, os.ErrNotExist)
}

func (r *rootTun) apply(cfg rootConfig) error {
	if err := cfg.validate(); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil || !r.cur.sameLink(cfg) {
		if r.cur != nil {
			r.teardownLocked("the interface changes")
		}
		if err := createLink(cfg); err != nil {
			return err
		}
	}
	if err := r.routes(cfg); err != nil {
		return err
	}
	if err := r.dns(cfg); err != nil {
		say("warn: DNS redirect: %v", err)
	}
	r.cur = &cfg
	say("ready: %s %v, %d routes, default4=%t default6=%t", cfg.Name, cfg.Addrs, len(cfg.Routes), cfg.Default4, cfg.Default6)
	return nil
}

// createLink opens the TUN, configures it and hands it to the daemon.
func createLink(cfg rootConfig) error {
	fd, err := openTun(cfg.Name)
	if err != nil {
		return err
	}
	defer unix.Close(fd)
	if err := ip("link", "set", "dev", cfg.Name, "mtu", strconv.Itoa(cfg.MTU), "up"); err != nil {
		return err
	}
	if a, ok := cfg.v4(); ok {
		if err := ip("addr", "replace", a.String()+"/32", "dev", cfg.Name); err != nil {
			return err
		}
	}
	if a, ok := cfg.v6(); ok {
		if err := ip("-6", "addr", "replace", a.String()+"/128", "dev", cfg.Name, "nodad"); err != nil {
			say("warn: IPv6 address: %v", err)
		}
	}
	err = attach(cfg, fd)
	if err != nil && strings.Contains(err.Error(), "one descriptor expected, got 0") {
		// SELinux dropped the descriptor on the way: the app's domain may not
		// use a descriptor from root's. Allow that one thing, then retry.
		if perr := allowFdUse(cfg.AppDomain); perr != nil {
			return fmt.Errorf("%v; SELinux: %v", err, perr)
		}
		err = attach(cfg, fd)
	}
	return err
}

func openTun(name string) (int, error) {
	var fd int
	var err error
	for _, dev := range []string{"/dev/net/tun", "/dev/tun"} {
		fd, err = unix.Open(dev, unix.O_RDWR|unix.O_CLOEXEC, 0)
		if err == nil {
			break
		}
	}
	if err != nil {
		return -1, fmt.Errorf("open TUN: %w", err)
	}
	ifr, err := unix.NewIfreq(name)
	if err != nil {
		unix.Close(fd)
		return -1, err
	}
	ifr.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err := unix.IoctlIfreq(fd, unix.TUNSETIFF, ifr); err != nil {
		unix.Close(fd)
		return -1, fmt.Errorf("TUNSETIFF %s: %w", name, err)
	}
	return fd, nil
}

type vpnMessage struct {
	Op    string   `json:"op"`
	MTU   int      `json:"mtu,omitempty"`
	Addrs []string `json:"addrs,omitempty"`
}

// attach hands the interface to the daemon (netstack/vpn.go), or, with fd
// -1, tells it to let go of it.
func attach(cfg rootConfig, fd int) error {
	msg := vpnMessage{Op: "detach"}
	var oob []byte
	if fd >= 0 {
		msg = vpnMessage{Op: "attach", MTU: cfg.MTU, Addrs: cfg.Addrs}
		oob = unix.UnixRights(fd)
	}
	c, err := net.DialUnix("unixpacket", nil, &net.UnixAddr{Name: cfg.Socket, Net: "unixpacket"})
	if err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	b, _ := json.Marshal(msg)
	if _, _, err := c.WriteMsgUnix(b, oob, nil); err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	buf := make([]byte, 4096)
	n, err := c.Read(buf)
	if err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	var reply struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(buf[:n], &reply); err != nil {
		return fmt.Errorf("VPN socket reply: %w", err)
	}
	if reply.Error != "" {
		return errors.New(reply.Error)
	}
	return nil
}

// allowFdUse lets the app's SELinux domain use a descriptor root opened,
// with whichever policy tool the root solution ships (Magisk, APatch,
// KernelSU, SuperSU). The rule lasts until reboot.
func allowFdUse(appDomain string) error {
	app := selinuxType(appDomain)
	self, _ := os.ReadFile("/proc/self/attr/current")
	me := selinuxType(strings.TrimRight(string(self), "\x00\n"))
	if app == "" || me == "" {
		return fmt.Errorf("domains unknown (app %q, root %q)", appDomain, me)
	}
	rule := fmt.Sprintf("allow %s %s fd use", app, me)
	tools := [][]string{
		{"magiskpolicy", "--live", rule},
		{"/data/adb/ap/bin/magiskpolicy", "--live", rule},
		{"/data/adb/magisk/magiskpolicy", "--live", rule},
		{"/data/adb/ksud", "sepolicy", "patch", rule},
		{"supolicy", "--live", rule},
	}
	var errs []string
	for _, t := range tools {
		if out, err := exec.Command(t[0], t[1:]...).CombinedOutput(); err == nil {
			say("info: SELinux: %s (%s)", rule, t[0])
			return nil
		} else {
			errs = append(errs, fmt.Sprintf("%s: %v %s", t[0], err, strings.TrimSpace(string(out))))
		}
	}
	return errors.New(strings.Join(errs, "; "))
}

// selinuxType: "u:r:untrusted_app:s0:c1,c2" → "untrusted_app".
func selinuxType(ctx string) string {
	parts := strings.Split(ctx, ":")
	if len(parts) < 3 {
		return ""
	}
	return parts[2]
}

// routes rewrites the table and the rules for cfg.
func (r *rootTun) routes(cfg rootConfig) error {
	table := strconv.Itoa(cfg.Table)
	_ = ip("route", "flush", "table", table)
	_ = ip("-6", "route", "flush", "table", table)
	v4, has4 := cfg.v4()
	v6, has6 := cfg.v6()
	add := func(family, prefix string, src netip.Addr, hasSrc bool) {
		args := []string{family, "route", "replace", prefix, "dev", cfg.Name, "table", table}
		if hasSrc {
			args = append(args, "src", src.String())
		}
		if err := ip(args...); err != nil {
			say("warn: route %s: %v", prefix, err)
		}
	}
	routes := append([]string{}, cfg.Routes...)
	if cfg.DNS != "" {
		routes = append(routes, netip.PrefixFrom(netip.MustParseAddr(cfg.DNS), 32).String())
	}
	for _, p := range routes {
		pfx := netip.MustParsePrefix(p)
		if pfx.Addr().Is4() {
			add("-4", pfx.Masked().String(), v4, has4)
		} else {
			add("-6", pfx.Masked().String(), v6, has6)
		}
	}
	if cfg.Default4 {
		add("-4", "default", v4, has4)
	}
	if cfg.Default6 {
		add("-6", "default", v6, has6)
	}
	for _, c := range cfg.ExcludeCIDRs {
		pfx := netip.MustParsePrefix(c).Masked()
		family := "-4"
		if pfx.Addr().Is6() {
			family = "-6"
		}
		_ = ip(family, "route", "replace", "throw", pfx.String(), "table", table)
	}
	r.removeRules()
	pref := strconv.Itoa(cfg.Pref)
	skip := strconv.Itoa(cfg.Pref - 1)
	for _, family := range []string{"-4", "-6"} {
		target := netdFirstRule(family, cfg.Pref)
		var made [][]string
		if target > 0 {
			for _, uid := range uniqueUIDs(cfg.ExcludeUIDs, cfg.AppUID) {
				rule := []string{family, "rule", "add", "pref", skip, "uidrange", fmt.Sprintf("%d-%d", uid, uid), "goto", strconv.Itoa(target)}
				if err := ip(rule...); err == nil {
					made = append(made, rule)
				} else {
					say("warn: rule %v: %v", rule, err)
				}
			}
		} else {
			say("warn: no netd rule after %d (%s): the app's own traffic would loop, not routing", cfg.Pref, family)
			continue
		}
		rule := []string{family, "rule", "add", "pref", pref, "fwmark", "0x0/0x20000", "iif", "lo", "lookup", table}
		if err := ip(rule...); err != nil {
			return fmt.Errorf("rule: %w", err)
		}
		made = append(made, rule)
		// Android's resolver marks its sockets protected from VPNs, so the
		// rule above skips the queries the DNS redirect rewrote; the
		// resolver's address gets a rule of its own.
		if family == "-4" && cfg.DNS != "" {
			dnsRule := []string{family, "rule", "add", "pref", pref, "to", cfg.DNS + "/32", "iif", "lo", "lookup", table}
			if err := ip(dnsRule...); err != nil {
				say("warn: DNS rule: %v", err)
			} else {
				made = append(made, dnsRule)
			}
		}
		if family == "-4" {
			r.rules4 = made
		} else {
			r.rules6 = made
		}
	}
	return nil
}

func uniqueUIDs(uids []int, app int) []int {
	seen := map[int]bool{}
	var out []int
	for _, u := range append([]int{app}, uids...) {
		if u > 0 && !seen[u] {
			seen[u] = true
			out = append(out, u)
		}
	}
	return out
}

// netdFirstRule is the priority of the first rule after ours: where the
// excluded UIDs go on (netd's own rules). 0 when there is none.
func netdFirstRule(family string, after int) int {
	out, err := exec.Command("/system/bin/ip", family, "rule", "show").Output()
	if err != nil {
		return 0
	}
	best := 0
	for _, line := range strings.Split(string(out), "\n") {
		p, _, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		n, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || n <= after {
			continue
		}
		if best == 0 || n < best {
			best = n
		}
	}
	return best
}

func (r *rootTun) removeRules() {
	for _, rule := range append(append([][]string{}, r.rules4...), r.rules6...) {
		del := append([]string{}, rule...)
		del[2] = "del"
		_ = ip(del...)
	}
	r.rules4, r.rules6 = nil, nil
}

// dns sends port 53 to the VPN's resolver.
func (r *rootTun) dns(cfg rootConfig) error {
	r.removeDNS()
	if cfg.DNS == "" {
		return nil
	}
	owner := []string{"-m", "owner", "--uid-owner", strconv.Itoa(cfg.AppUID), "-j", "RETURN"}
	steps := [][]string{
		{"-t", "nat", "-N", rootDNSChain},
		{"-t", "nat", "-A", rootDNSChain},
		{"-t", "nat", "-A", rootDNSChain, "-p", "udp", "--dport", "53", "-j", "DNAT", "--to-destination", cfg.DNS + ":53"},
		{"-t", "nat", "-A", rootDNSChain, "-p", "tcp", "--dport", "53", "-j", "DNAT", "--to-destination", cfg.DNS + ":53"},
		{"-t", "nat", "-I", "OUTPUT", "1", "-j", rootDNSChain},
	}
	steps[1] = append(steps[1], owner...)
	for _, s := range steps {
		if err := iptables("iptables", s...); err != nil {
			r.removeDNS()
			return err
		}
	}
	r.dns4 = true
	// No IPv6 resolver in the tunnel: refuse port 53 over IPv6, so a
	// resolver moves on to an IPv4 server instead of bypassing NetBird.
	steps6 := [][]string{
		{"-N", rootDNS6Chain},
		append([]string{"-A", rootDNS6Chain}, owner...),
		{"-A", rootDNS6Chain, "-p", "udp", "--dport", "53", "-j", "REJECT"},
		{"-A", rootDNS6Chain, "-p", "tcp", "--dport", "53", "-j", "REJECT", "--reject-with", "tcp-reset"},
		{"-I", "OUTPUT", "1", "-j", rootDNS6Chain},
	}
	for _, s := range steps6 {
		if err := iptables("ip6tables", s...); err != nil {
			say("warn: IPv6 DNS: %v", err)
			r.removeDNS6()
			return nil
		}
	}
	r.dns6 = true
	return nil
}

func (r *rootTun) removeDNS() {
	for iptables("iptables", "-t", "nat", "-D", "OUTPUT", "-j", rootDNSChain) == nil {
	}
	_ = iptables("iptables", "-t", "nat", "-F", rootDNSChain)
	_ = iptables("iptables", "-t", "nat", "-X", rootDNSChain)
	r.dns4 = false
	r.removeDNS6()
}

func (r *rootTun) removeDNS6() {
	for iptables("ip6tables", "-D", "OUTPUT", "-j", rootDNS6Chain) == nil {
	}
	_ = iptables("ip6tables", "-F", rootDNS6Chain)
	_ = iptables("ip6tables", "-X", rootDNS6Chain)
	r.dns6 = false
}

func (r *rootTun) teardown(why string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.teardownLocked(why)
}

// teardownLocked takes the interface from the daemon and removes every rule.
func (r *rootTun) teardownLocked(why string) {
	r.removeDNS()
	r.removeRules()
	if r.cur != nil {
		table := strconv.Itoa(r.cur.Table)
		_ = ip("route", "flush", "table", table)
		_ = ip("-6", "route", "flush", "table", table)
		// A vanished interface means the daemon already let go of it (or died).
		if _, err := os.Stat("/sys/class/net/" + r.cur.Name); err == nil {
			if err := attach(*r.cur, -1); err != nil {
				say("warn: detach: %v", err)
			}
			_ = ip("link", "del", r.cur.Name)
		}
	}
	r.cur = nil
	say("down: %s", why)
}

func ip(args ...string) error { return run("/system/bin/ip", args...) }

func iptables(bin string, args ...string) error {
	return run("/system/bin/"+bin, append([]string{"-w"}, args...)...)
}

func run(bin string, args ...string) error {
	out, err := exec.Command(bin, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s: %v: %s", bin, strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}
