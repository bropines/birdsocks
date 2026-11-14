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
// daemon never leaves the device routing into nothing. A helper killed
// outright leaves its rules behind; the next one sweeps them before it starts.
//
// What it claims is decided in roottun_plan.go (three tiers, yielded to
// another VPN client); here it is installed:
//   - table <t1>: NetBird's ranges, the VPN's resolver, throw routes for
//     foreign prefixes inside the overlay (the CGNAT guard);
//   - table <t1+1>: the networks NetBird selected, a default route behind an
//     exit node, throw routes for the excluded ranges;
//   - rules 89/90/151/191/201 (roottun_plan.go);
//   - nat OUTPUT → <chain>_DNS: port 53 to the VPN's resolver, except the
//     daemon's own queries, the overlay and another tunnel's network; IPv6
//     port 53 likewise to the resolver's IPv6 address where the kernel has
//     IPv6 NAT, and left alone where it has not — never refused. Held while Android has not validated the network
//     (its probe resolves through the system resolver) and taken off while
//     the resolver does not answer;
//   - rp_filter loose while the catch-all is in (replies to the daemon's
//     sockets would fail a strict reverse-path check through it), restored after.
//
// Every few seconds it looks at the device again — another VPN coming or
// going, the LAN, a CGNAT address — and rewrites the rules when that changed.

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const (
	// protectMask is Android's PROTECT_FROM_VPN fwmark bit: sockets that must
	// not go through a VPN (validation probes, MMS, other VPN apps' own
	// sockets) keep off the catch-all.
	protectMask  = "0x0/0x20000"
	protectProbe = "0x20000"

	probeEvery  = 5 * time.Second
	healthEvery = 20 * time.Second
	healthFails = 3
	healthName  = "connectivitycheck.gstatic.com."
)

// rootConfig is one line from the app.
type rootConfig struct {
	Op    string   `json:"op"` // "" (apply) or "stop"
	Name  string   `json:"name"`
	MTU   int      `json:"mtu"`
	Addrs []string `json:"addrs"`
	// Overlay: NetBird's ranges (T1). Routes: the networks selected (T2).
	Overlay []string `json:"overlay"`
	Routes  []string `json:"routes"`
	// Default4/6: a default route into the interface (exit node, domain route, route all).
	Default4 bool `json:"default4"`
	Default6 bool `json:"default6"`
	// OwnUIDs: the app's packages, past every rule. ExcludeUIDs: the user's
	// excluded apps, past T2 (they still reach the overlay).
	OwnUIDs      []int    `json:"own_uids"`
	ExcludeUIDs  []int    `json:"exclude_uids"`
	ExcludeCIDRs []string `json:"exclude_cidrs"`
	// CarryUIDs: installed apps, not VPN clients, not ours — whom T2 may
	// take where another VPN bypasses them. VPNUIDs: apps with a VpnService.
	CarryUIDs []int `json:"carry_uids"`
	VPNUIDs   []int `json:"vpn_uids"`
	// KeepLAN: the networks this device is on stay local under T2.
	KeepLAN bool `json:"keep_lan"`
	// TakeDevice: T2 and T3 device-wide even beside another VPN.
	TakeDevice bool `json:"take_device"`
	// ForeignSeen: Android reports a VPN network to the app (one that carries it).
	ForeignSeen bool `json:"foreign_seen"`
	// DNSHold: Android has not validated the network yet; no redirect.
	DNSHold bool `json:"dns_hold"`
	// AppUID is the daemon's: its own DNS queries are not redirected.
	AppUID    int    `json:"app_uid"`
	AppDomain string `json:"app_domain"`
	Table     int    `json:"table"`
	DNS       string `json:"dns"`
	// DNS6: the same resolver's IPv6 address. Android's resolver may keep to an
	// IPv6 server for half an hour after its IPv4 ones failed, so refusing
	// port 53 over IPv6 can leave a phone with no DNS at all: it is redirected
	// there instead, or left alone where the kernel has no IPv6 NAT.
	DNS6   string `json:"dns6"`
	Socket string `json:"socket"`
	// Chain names the iptables chains (<chain>_DNS); StateDir keeps what
	// must outlive a killed helper (rp_filter's old value).
	Chain    string `json:"chain"`
	StateDir string `json:"state_dir"`
}

func (c rootConfig) table2() int { return c.Table + 1 }

func (c rootConfig) validate() error {
	bad := func(t int) bool { return t < 1 || t >= 0x7fffffff || t == 253 || t == 254 || t == 255 }
	switch {
	case c.Name == "" || len(c.Name) > 15 || strings.ContainsAny(c.Name, " /\t\n"):
		return fmt.Errorf("interface name %q", c.Name)
	case c.MTU < 576 || c.MTU > 65535:
		return fmt.Errorf("MTU %d", c.MTU)
	case len(c.Addrs) == 0:
		return errors.New("no address")
	case bad(c.Table) || bad(c.table2()):
		return fmt.Errorf("table %d", c.Table)
	case c.Socket == "":
		return errors.New("no VPN socket")
	case c.Chain == "" || len(c.Chain) > 20 || strings.ContainsAny(c.Chain, " \t\n-/"):
		return fmt.Errorf("chain %q", c.Chain)
	case c.StateDir != "" && (!filepath.IsAbs(c.StateDir) || !strings.HasPrefix(c.StateDir, "/data/adb/")):
		return fmt.Errorf("state directory %q", c.StateDir)
	}
	for _, a := range c.Addrs {
		if _, err := netip.ParseAddr(a); err != nil {
			return fmt.Errorf("address %q", a)
		}
	}
	for _, r := range append(append(append([]string{}, c.Overlay...), c.Routes...), c.ExcludeCIDRs...) {
		if _, err := netip.ParsePrefix(r); err != nil {
			return fmt.Errorf("route %q", r)
		}
	}
	if c.DNS != "" {
		if _, err := netip.ParseAddr(c.DNS); err != nil {
			return fmt.Errorf("DNS %q", c.DNS)
		}
	}
	if c.DNS6 != "" {
		if a, err := netip.ParseAddr(c.DNS6); err != nil || !a.Is6() {
			return fmt.Errorf("DNS6 %q", c.DNS6)
		}
	}
	return nil
}

// validateSweep: what a sweep needs — whose tables, chains and state these are.
func (c rootConfig) validateSweep() error {
	bad := func(t int) bool { return t < 1 || t >= 0x7fffffff || t == 253 || t == 254 || t == 255 }
	switch {
	case bad(c.Table) || bad(c.table2()):
		return fmt.Errorf("table %d", c.Table)
	case c.Chain == "" || len(c.Chain) > 20 || strings.ContainsAny(c.Chain, " \t\n-/"):
		return fmt.Errorf("chain %q", c.Chain)
	case c.StateDir != "" && (!filepath.IsAbs(c.StateDir) || !strings.HasPrefix(c.StateDir, "/data/adb/")):
		return fmt.Errorf("state directory %q", c.StateDir)
	}
	return nil
}

// sameLink: two configs share the interface itself; only routes differ.
func (c rootConfig) sameLink(o rootConfig) bool {
	return c.Name == o.Name && c.MTU == o.MTU && strings.Join(c.Addrs, ",") == strings.Join(o.Addrs, ",") &&
		c.Socket == o.Socket && c.Table == o.Table && c.Chain == o.Chain
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

func (c rootConfig) overlayPrefixes() []netip.Prefix {
	var out []netip.Prefix
	for _, o := range c.Overlay {
		out = append(out, netip.MustParsePrefix(o).Masked())
	}
	return out
}

func (c rootConfig) dnsChain() string  { return c.Chain + "_DNS" }
func (c rootConfig) dns6Chain() string { return c.Chain + "_DNS6" }

// rootTun is the helper's state.
type rootTun struct {
	mu  sync.Mutex
	cur *rootConfig
	env rootEnv
	sig string
	// what is installed now
	plan        rootPlan
	dnsHooked   bool
	rpLoosened  bool
	dnsFailures int
	dnsSick     bool // taken off by the health gate
	lastState   string
}

// pipeGone is closed when stdout stops taking lines: the app that reads
// them died. Without SIGPIPE handled, that first failed write would kill
// the helper outright and leave every rule behind (seen on an APK reinstall).
var (
	pipeGone     = make(chan struct{})
	pipeGoneOnce sync.Once
)

func say(format string, args ...any) {
	// One line per message: the app reads lines, and a tool's error may span several.
	msg := strings.ReplaceAll(strings.TrimSpace(fmt.Sprintf(format, args...)), "\n", " · ")
	if _, err := fmt.Fprintln(os.Stdout, msg); err != nil {
		pipeGoneOnce.Do(func() { close(pipeGone) })
	}
}

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
		sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
		for sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
	}()
	sigs := make(chan os.Signal, 2)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	// Handled, SIGPIPE turns a write to the dead app's pipe into an EPIPE
	// (say notices it) instead of ending the process before its teardown.
	signal.Notify(make(chan os.Signal, 1), syscall.SIGPIPE)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	lastProbe, lastHealth := time.Now(), time.Now()
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
			if cfg.Op == "sweep" {
				// The app found a helper's marker with no helper: take what it left.
				if err := cfg.validateSweep(); err != nil {
					say("error: %v", err)
					return 1
				}
				sweep(cfg)
				say("down: swept what an earlier helper left")
				return 0
			}
			if err := r.apply(cfg); err != nil {
				say("error: %v", err)
				r.teardown("setup failed")
				return 1
			}
			lastProbe = time.Now()
		case s := <-sigs:
			r.teardown("signal " + s.String())
			return 0
		case <-pipeGone:
			r.teardown("the app went away (its pipe closed)")
			return 0
		case <-tick.C:
			if r.linkGone() {
				r.teardown("the interface went away (the daemon let go of it)")
				return 3
			}
			if time.Since(lastProbe) >= probeEvery {
				lastProbe = time.Now()
				r.reprobe()
			}
			if time.Since(lastHealth) >= healthEvery {
				lastHealth = time.Now()
				r.checkDNS()
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
	if r.cur == nil {
		// A helper killed outright left its rules, and the daemon still holds
		// its interface; take both first, or the name is busy.
		sweep(cfg)
		releaseStale(cfg)
	}
	if r.cur == nil || !r.cur.sameLink(cfg) {
		if r.cur != nil {
			r.teardownLocked("the interface changes")
		}
		if err := createLink(cfg); err != nil {
			return err
		}
	}
	r.cur = &cfg
	r.env = probeEnv(cfg)
	r.sig = r.env.signature()
	if err := r.install(); err != nil {
		return err
	}
	say("ready: %s %v, %d overlay, %d routes, default4=%t default6=%t", cfg.Name, cfg.Addrs, len(cfg.Overlay), len(cfg.Routes), cfg.Default4, cfg.Default6)
	r.report(true)
	return nil
}

// reprobe looks at the device again and rewrites the rules when it changed.
func (r *rootTun) reprobe() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil {
		return
	}
	env := probeEnv(*r.cur)
	sig := env.signature()
	if sig == r.sig {
		return
	}
	r.env, r.sig = env, sig
	say("info: the device changed, rules rewritten")
	if err := r.install(); err != nil {
		say("warn: %v", err)
	}
	r.report(false)
}

// report tells the app the mode, on every change.
func (r *rootTun) report(force bool) {
	dns := "off"
	switch {
	case r.cur.DNS == "" || !r.plan.dns:
		dns = "off"
	case r.cur.DNSHold:
		dns = "hold"
	case r.dnsSick:
		dns = "unhealthy"
	case r.dnsHooked:
		dns = "on"
	}
	state := fmt.Sprintf("mode=%s dns=%s", r.plan.mode, dns)
	if !force && state == r.lastState {
		return
	}
	r.lastState = state
	say("state: %s capture=%t (%s)", state, r.plan.capture, r.plan.reason)
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

// probeEnv reads what the device looks like: another VPN, the LAN, CGNAT
// addresses, netd's first rule and the physical tables.
func probeEnv(cfg rootConfig) rootEnv {
	var env rootEnv
	env.ipv6 = ipv6Enabled()
	rules4 := output("/system/bin/ip", "-4", "rule", "show")
	rules6 := ""
	if env.ipv6 {
		rules6 = output("/system/bin/ip", "-6", "rule", "show")
	}
	env.goto4 = firstNetdRule(rules4)
	env.goto6 = firstNetdRule(rules6)
	env.phys4 = parseRouteTable(output("/system/bin/ip", "-4", "route", "get", "1.1.1.1", "mark", protectProbe))
	if env.ipv6 {
		env.phys6 = parseRouteTable(output("/system/bin/ip", "-6", "route", "get", "2001:4860:4860::8888", "mark", protectProbe))
	}
	var names []string
	if ents, err := os.ReadDir("/sys/class/net"); err == nil {
		for _, e := range ents {
			names = append(names, e.Name())
		}
	}
	env.foreign = parseForeign(rules4+"\n"+rules6, names, cfg.Name, env.phys4, env.phys6)
	addrs := parseAddrs(output("/system/bin/ip", "-o", "addr", "show"))
	overlay := cfg.overlayPrefixes()
	env.lan4, env.lan6 = lanPrefixes(addrs, overlay)
	var refused []guardPrefix
	env.cgnat, refused = guardPrefixes(addrs, overlay, cfg.Name)
	for _, g := range refused {
		say("warn: CGNAT guard: %s on %s covers the whole overlay; left captured", g.prefix, g.iface)
	}
	return env
}

func ipv6Enabled() bool {
	b, err := os.ReadFile("/proc/sys/net/ipv6/conf/all/disable_ipv6")
	return err == nil && strings.TrimSpace(string(b)) == "0"
}

// install rewrites every rule for r.cur and r.env, under r.mu.
func (r *rootTun) install() error {
	cfg, env := *r.cur, r.env
	plan := planRoot(cfg, env)
	r.plan = plan
	say("info: %s: %s; other VPN: %s", plan.mode, plan.reason, env.foreign.reason)

	if err := r.tables(cfg, env, plan); err != nil {
		return err
	}
	removeRules(cfg)
	families := []string{"-4"}
	if env.ipv6 {
		families = append(families, "-6")
	}
	for _, fam := range families {
		if err := r.rules(fam, cfg, env, plan); err != nil {
			return err
		}
	}
	if plan.capture {
		r.loosenRPFilter(cfg)
	} else {
		r.restoreRPFilter(cfg)
	}
	r.hookDNS()
	return nil
}

// tables rewrites both of our tables (they are ours alone: netd's are
// 1000 + ifindex, nowhere near).
func (r *rootTun) tables(cfg rootConfig, env rootEnv, plan rootPlan) error {
	t1, t2 := strconv.Itoa(cfg.Table), strconv.Itoa(cfg.table2())
	for _, t := range []string{t1, t2} {
		_ = ip("-4", "route", "flush", "table", t)
		_ = ip("-6", "route", "flush", "table", t)
	}
	v4, has4 := cfg.v4()
	v6, has6 := cfg.v6()
	add := func(table, prefix string) {
		pfx := netip.MustParsePrefix(prefix).Masked()
		fam, src, hasSrc := "-4", v4, has4
		if pfx.Addr().Is6() {
			fam, src, hasSrc = "-6", v6, has6
			if !env.ipv6 {
				return
			}
		}
		args := []string{fam, "route", "replace", pfx.String(), "dev", cfg.Name, "table", table}
		if hasSrc {
			args = append(args, "src", src.String())
		}
		if err := ip(args...); err != nil {
			say("warn: route %s: %v", pfx, err)
		}
	}
	// T1
	for _, p := range cfg.Overlay {
		add(t1, p)
	}
	for _, d := range []string{cfg.DNS, cfg.DNS6} {
		if d != "" {
			add(t1, netip.PrefixFrom(netip.MustParseAddr(d), netip.MustParseAddr(d).BitLen()).String())
		}
	}
	for _, g := range env.cgnat {
		if err := ip("-4", "route", "replace", "throw", g.prefix.String(), "table", t1); err == nil {
			say("info: CGNAT guard: %s on %s sits inside the overlay; it stays on its own network", g.prefix, g.iface)
		}
	}
	// T2, only where it is captured at all.
	if !plan.capture {
		return nil
	}
	for _, p := range cfg.Routes {
		add(t2, p)
	}
	if cfg.Default4 {
		add(t2, "0.0.0.0/0")
	}
	if cfg.Default6 {
		add(t2, "::/0")
	}
	for _, c := range cfg.ExcludeCIDRs {
		pfx := netip.MustParsePrefix(c).Masked()
		fam := "-4"
		if pfx.Addr().Is6() {
			fam = "-6"
		}
		_ = ip(fam, "route", "replace", "throw", pfx.String(), "table", t2)
	}
	return nil
}

// rules installs one family's rules.
func (r *rootTun) rules(fam string, cfg rootConfig, env rootEnv, plan rootPlan) error {
	target, phys := env.goto4, env.phys4
	if fam == "-6" {
		target, phys = env.goto6, env.phys6
	}
	t1, t2 := strconv.Itoa(cfg.Table), strconv.Itoa(cfg.table2())
	// back hands traffic to the platform's rules: a goto into netd's band,
	// read back since the kernel accepts a goto it cannot resolve and then
	// skips it; the physical table when even that fails.
	back := func(pref int, sel ...string) bool {
		p := strconv.Itoa(pref)
		if target > 0 {
			args := append(append([]string{fam, "rule", "add", "pref", p}, sel...), "goto", strconv.Itoa(target))
			if ip(args...) == nil {
				if !ruleDetached(fam, p, sel) {
					return true
				}
				del := append([]string{}, args...)
				del[2] = "del"
				_ = ip(del...)
			}
		}
		if phys != "" {
			args := append(append([]string{fam, "rule", "add", "pref", p}, sel...), "lookup", phys)
			if ip(args...) == nil {
				return true
			}
		}
		say("warn: %s rule %d %v: neither netd's rules nor the physical table can be reached", fam, pref, sel)
		return false
	}
	// The app's own packages first: without this the daemon's sockets loop.
	ownOK := true
	for _, uid := range uniqueUIDs(cfg.OwnUIDs, cfg.AppUID) {
		if !back(prefOwn, "uidrange", fmt.Sprintf("%d-%d", uid, uid)) && uid == cfg.AppUID {
			ownOK = false
		}
	}
	if !ownOK {
		// Better no tunnel than a device whose NetBird traffic loops into it.
		return fmt.Errorf("%s: the daemon's own traffic cannot be kept off the tunnel", fam)
	}
	// T1: by destination; the resolver too, since Android's resolver marks
	// its sockets protected from VPNs.
	for _, o := range cfg.Overlay {
		pfx := netip.MustParsePrefix(o).Masked()
		if (fam == "-6") != pfx.Addr().Is6() {
			continue
		}
		if err := ip(fam, "rule", "add", "pref", strconv.Itoa(prefOverlay), "to", pfx.String(), "iif", "lo", "lookup", t1); err != nil {
			return fmt.Errorf("overlay rule: %w", err)
		}
	}
	if fam == "-4" && cfg.DNS != "" {
		if err := ip(fam, "rule", "add", "pref", strconv.Itoa(prefOverlay), "to", cfg.DNS+"/32", "iif", "lo", "lookup", t1); err != nil {
			say("warn: DNS rule: %v", err)
		}
	}
	if fam == "-6" && cfg.DNS6 != "" {
		if err := ip(fam, "rule", "add", "pref", strconv.Itoa(prefOverlay), "to", cfg.DNS6+"/128", "iif", "lo", "lookup", t1); err != nil {
			say("warn: IPv6 DNS rule: %v", err)
		}
	}
	if !plan.capture {
		return nil
	}
	// T2's softeners before the catch-all, so it never captures without them.
	if cfg.KeepLAN {
		lan := env.lan4
		if fam == "-6" {
			lan = env.lan6
		}
		for _, p := range lan {
			back(prefLAN, "to", p.String(), "iif", "lo")
		}
	}
	for _, uid := range uniqueUIDs(cfg.ExcludeUIDs, 0) {
		back(prefExclude, "uidrange", fmt.Sprintf("%d-%d", uid, uid))
	}
	base := []string{fam, "rule", "add", "pref", strconv.Itoa(prefCapture), "fwmark", protectMask, "iif", "lo"}
	if plan.captureUIDs == nil {
		if err := ip(append(base, "lookup", t2)...); err != nil {
			return fmt.Errorf("catch-all: %w", err)
		}
	} else {
		for _, g := range plan.captureUIDs {
			if err := ip(append(append([]string{}, base...), "uidrange", g.String(), "lookup", t2)...); err != nil {
				say("warn: catch-all for %s: %v", g, err)
			}
		}
	}
	// An `ip` that dropped the mask would leave a match-everything rule.
	if out := output("/system/bin/ip", fam, "rule", "show"); strings.Contains(out, fmt.Sprintf("%d:\tfrom all iif lo lookup %s", prefCapture, t2)) {
		_ = ip(fam, "rule", "del", "pref", strconv.Itoa(prefCapture))
		return errors.New("the catch-all lost its mark match; removed")
	}
	return nil
}

// ruleDetached: a rule at pref is one the kernel will skip (its goto
// target does not resolve). All of a pref's gotos share the target.
func ruleDetached(fam, pref string, _ []string) bool {
	for _, line := range strings.Split(output("/system/bin/ip", fam, "rule", "show"), "\n") {
		if strings.HasPrefix(line, pref+":") && (strings.Contains(line, "[detached]") || strings.Contains(line, "unresolved")) {
			return true
		}
	}
	return false
}

// removeRules deletes every rule at our priorities, both families.
func removeRules(rootConfig) {
	for _, fam := range []string{"-4", "-6"} {
		for _, p := range ourPrefs {
			for i := 0; i < 4096 && ip(fam, "rule", "del", "pref", strconv.Itoa(p)) == nil; i++ {
			}
		}
	}
}

// hookDNS puts the port-53 redirect in or takes it out, as the plan, the
// validation hold and the health gate say.
func (r *rootTun) hookDNS() {
	cfg := *r.cur
	want := cfg.DNS != "" && r.plan.dns && !cfg.DNSHold && !r.dnsSick
	removeDNS(cfg)
	r.dnsHooked = false
	if !want {
		return
	}
	chain := cfg.dnsChain()
	var steps [][]string
	steps = append(steps, []string{"-t", "nat", "-N", chain})
	for _, uid := range uniqueUIDs(cfg.OwnUIDs, cfg.AppUID) {
		steps = append(steps, []string{"-t", "nat", "-A", chain, "-m", "owner", "--uid-owner", strconv.Itoa(uid), "-j", "RETURN"})
	}
	for _, id := range r.plan.dnsReturnNetIDs {
		steps = append(steps, []string{"-t", "nat", "-A", chain, "-m", "mark", "--mark", fmt.Sprintf("0x%x/0xffff", id), "-j", "RETURN"})
	}
	for _, o := range cfg.overlayPrefixes() {
		if o.Addr().Is4() {
			steps = append(steps, []string{"-t", "nat", "-A", chain, "-d", o.String(), "-j", "RETURN"})
		}
	}
	steps = append(steps,
		[]string{"-t", "nat", "-A", chain, "-p", "udp", "--dport", "53", "-j", "DNAT", "--to-destination", cfg.DNS + ":53"},
		[]string{"-t", "nat", "-A", chain, "-p", "tcp", "--dport", "53", "-j", "DNAT", "--to-destination", cfg.DNS + ":53"},
		[]string{"-t", "nat", "-I", "OUTPUT", "1", "-j", chain},
	)
	for _, s := range steps {
		if err := iptables("iptables", s...); err != nil {
			say("warn: DNS redirect: %v", err)
			removeDNS(cfg)
			return
		}
	}
	// Excluded apps leave the chain too — only those that resolve for
	// themselves: Android's resolver sends an ordinary app's queries under
	// its own uid. A missing xt_owner costs only this.
	for _, uid := range uniqueUIDs(cfg.ExcludeUIDs, 0) {
		_ = iptables("iptables", "-t", "nat", "-I", chain, "1", "-m", "owner", "--uid-owner", strconv.Itoa(uid), "-j", "RETURN")
	}
	r.dnsHooked = true
	if !r.env.ipv6 {
		return
	}
	if cfg.DNS6 == "" {
		return
	}
	// IPv6 port 53 to the resolver's IPv6 address. Never a REJECT: Android's
	// resolver, with its IPv4 servers marked bad by recent failures, keeps to
	// an IPv6 one, and a refusal there left the phone with no DNS at all.
	// Without IPv6 NAT in the kernel, IPv6 DNS is left alone.
	chain6 := cfg.dns6Chain()
	steps6 := [][]string{{"-t", "nat", "-N", chain6}}
	for _, uid := range uniqueUIDs(cfg.OwnUIDs, cfg.AppUID) {
		steps6 = append(steps6, []string{"-t", "nat", "-A", chain6, "-m", "owner", "--uid-owner", strconv.Itoa(uid), "-j", "RETURN"})
	}
	for _, id := range r.plan.dnsReturnNetIDs {
		steps6 = append(steps6, []string{"-t", "nat", "-A", chain6, "-m", "mark", "--mark", fmt.Sprintf("0x%x/0xffff", id), "-j", "RETURN"})
	}
	for _, o := range cfg.overlayPrefixes() {
		if o.Addr().Is6() {
			steps6 = append(steps6, []string{"-t", "nat", "-A", chain6, "-d", o.String(), "-j", "RETURN"})
		}
	}
	to := "[" + cfg.DNS6 + "]:53"
	steps6 = append(steps6,
		[]string{"-t", "nat", "-A", chain6, "-p", "udp", "--dport", "53", "-j", "DNAT", "--to-destination", to},
		[]string{"-t", "nat", "-A", chain6, "-p", "tcp", "--dport", "53", "-j", "DNAT", "--to-destination", to},
		[]string{"-t", "nat", "-I", "OUTPUT", "1", "-j", chain6},
	)
	for _, s := range steps6 {
		if err := iptables("ip6tables", s...); err != nil {
			say("warn: IPv6 DNS left alone (no IPv6 NAT here?): %v", err)
			removeDNS6(cfg)
			return
		}
	}
	for _, uid := range uniqueUIDs(cfg.ExcludeUIDs, 0) {
		_ = iptables("ip6tables", "-t", "nat", "-I", chain6, "1", "-m", "owner", "--uid-owner", strconv.Itoa(uid), "-j", "RETURN")
	}
}

func removeDNS(cfg rootConfig) {
	chain := cfg.dnsChain()
	for i := 0; i < 64 && iptables("iptables", "-t", "nat", "-D", "OUTPUT", "-j", chain) == nil; i++ {
	}
	_ = iptables("iptables", "-t", "nat", "-F", chain)
	_ = iptables("iptables", "-t", "nat", "-X", chain)
	removeDNS6(cfg)
}

func removeDNS6(cfg rootConfig) {
	chain := cfg.dns6Chain()
	// nat now; filter is where builds before it put a REJECT chain.
	for _, table := range []string{"nat", "filter"} {
		for i := 0; i < 64 && iptables("ip6tables", "-t", table, "-D", "OUTPUT", "-j", chain) == nil; i++ {
		}
		_ = iptables("ip6tables", "-t", table, "-F", chain)
		_ = iptables("ip6tables", "-t", table, "-X", chain)
	}
}

// checkDNS asks the VPN's resolver for an outside name; after three misses
// in a row the redirect steps aside (the phone resolves as it would without
// BirdSocks), and comes back with the first answer.
func (r *rootTun) checkDNS() {
	r.mu.Lock()
	if r.cur == nil || r.cur.DNS == "" || !r.plan.dns || r.cur.DNSHold {
		r.mu.Unlock()
		return
	}
	server := net.JoinHostPort(r.cur.DNS, "53")
	r.mu.Unlock()

	res := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "udp", server)
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	// A fresh name each time: a cached one answers while upstream is dead.
	name := fmt.Sprintf("bs-%d.%s", time.Now().UnixNano(), healthName)
	_, err := res.LookupNetIP(ctx, "ip4", name)
	cancel()
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
		err = nil // NXDOMAIN came from upstream: the resolver works
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil {
		return
	}
	if err == nil {
		r.dnsFailures = 0
		if r.dnsSick {
			r.dnsSick = false
			say("info: the resolver answers again; the DNS redirect is back")
			r.hookDNS()
			r.report(false)
		}
		return
	}
	r.dnsFailures++
	if r.dnsFailures >= healthFails && !r.dnsSick {
		r.dnsSick = true
		say("warn: the resolver did not answer %d times (%v); the DNS redirect steps aside", r.dnsFailures, err)
		r.hookDNS()
		r.report(false)
	}
}

// rp_filter: with the catch-all in, replies to the daemon's sockets are
// reverse-path checked through it and a strict filter drops them. The
// kernel takes max(all, <iface>), so loosening `all` is enough; its old
// value is kept in the state directory, restored after.
func rpOrigPath(cfg rootConfig) string {
	if cfg.StateDir == "" {
		return ""
	}
	return filepath.Join(cfg.StateDir, "rp_filter.orig")
}

func (r *rootTun) loosenRPFilter(cfg rootConfig) {
	strict := false
	if m, _ := filepath.Glob("/proc/sys/net/ipv4/conf/*/rp_filter"); m != nil {
		for _, f := range m {
			if b, err := os.ReadFile(f); err == nil && strings.TrimSpace(string(b)) == "1" {
				strict = true
				break
			}
		}
	}
	if !strict {
		return
	}
	const all = "/proc/sys/net/ipv4/conf/all/rp_filter"
	cur, err := os.ReadFile(all)
	if err != nil {
		return
	}
	if orig := rpOrigPath(cfg); orig != "" {
		if _, err := os.Stat(orig); errors.Is(err, os.ErrNotExist) {
			_ = os.MkdirAll(cfg.StateDir, 0o700)
			_ = os.WriteFile(orig+".tmp", cur, 0o600)
			_ = os.Rename(orig+".tmp", orig)
		}
	}
	if err := os.WriteFile(all, []byte("2"), 0o644); err == nil {
		r.rpLoosened = true
		say("info: rp_filter loose while the catch-all is in")
	}
}

func (r *rootTun) restoreRPFilter(cfg rootConfig) {
	restoreRPFilter(cfg)
	r.rpLoosened = false
}

func restoreRPFilter(cfg rootConfig) {
	orig := rpOrigPath(cfg)
	if orig == "" {
		return
	}
	b, err := os.ReadFile(orig)
	if err != nil {
		return
	}
	switch v := strings.TrimSpace(string(b)); v {
	case "0", "1", "2":
		_ = os.WriteFile("/proc/sys/net/ipv4/conf/all/rp_filter", []byte(v), 0o644)
	}
	_ = os.Remove(orig)
}

// releaseStale asks the daemon to let go of an interface of ours a dead
// helper handed it, and waits for it to vanish.
func releaseStale(cfg rootConfig) {
	if _, err := os.Stat("/sys/class/net/" + cfg.Name); err != nil {
		return
	}
	say("info: %s is left from an earlier helper; the daemon lets go of it", cfg.Name)
	if err := attach(cfg, -1); err != nil {
		say("warn: detach: %v", err)
	}
	for i := 0; i < 20; i++ {
		if _, err := os.Stat("/sys/class/net/" + cfg.Name); err != nil {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = ip("link", "del", cfg.Name)
}

// sweep removes what a helper killed outright left behind.
func sweep(cfg rootConfig) {
	removeDNS(cfg)
	removeRules(cfg)
	for _, t := range []string{strconv.Itoa(cfg.Table), strconv.Itoa(cfg.table2())} {
		_ = ip("-4", "route", "flush", "table", t)
		_ = ip("-6", "route", "flush", "table", t)
	}
	restoreRPFilter(cfg)
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

func (r *rootTun) teardown(why string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.teardownLocked(why)
}

// teardownLocked takes the interface from the daemon and removes every rule.
func (r *rootTun) teardownLocked(why string) {
	if r.cur != nil {
		sweep(*r.cur)
		// A vanished interface means the daemon already let go of it (or died).
		if _, err := os.Stat("/sys/class/net/" + r.cur.Name); err == nil {
			if err := attach(*r.cur, -1); err != nil {
				say("warn: detach: %v", err)
			}
			_ = ip("link", "del", r.cur.Name)
		}
	}
	r.cur = nil
	r.dnsHooked, r.dnsSick, r.dnsFailures, r.rpLoosened = false, false, 0, false
	r.lastState = ""
	say("down: %s", why)
}

func output(bin string, args ...string) string {
	out, _ := exec.Command(bin, args...).Output()
	return string(out)
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
