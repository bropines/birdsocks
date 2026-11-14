//go:build linux

package main

// roottun_plan.go — what the root engine claims, decided from the app's
// config and from what the device looks like (pure: no root, no commands, so
// it is unit-tested on any Linux).
//
// The rules come in three tiers, told apart by how much of the device each
// one claims, because `ip rule` is evaluated in ascending priority and every
// rule below netd's band (10000+) runs before any rule another VPN app owns:
//
//   - T1, the overlay: NetBird's ranges and the VPN's resolver, table <t1>,
//     priority 90. Only packets this device sends to the overlay match, which
//     no sane VPN routes for itself, so T1 coexists with anything. The app's
//     own UIDs skip everything at 89 (the daemon runs as the app: its
//     WireGuard, relay and signal sockets must reach the real network, even
//     when a peer's endpoint sits in a carrier's CGNAT range).
//   - T2, the default-route capture: the networks NetBird selected and the
//     default route behind an exit node, table <t1+1>, priority 201, with the
//     local LAN handed back at 151 and the excluded apps at 191.
//   - T3, device-wide DNS: port 53 to the VPN's resolver.
//
// T2 and T3 have one owner per device. When another VPN client holds
// Android's VPN slot they are yielded: entirely when it tunnels every app,
// or narrowed to the apps it bypasses (netd publishes its members as uid
// ranges; the gaps between them are nobody's). "Take the device anyway" is
// the only way past that.

import (
	"fmt"
	"net/netip"
	"sort"
	"strconv"
	"strings"
)

const (
	prefOwn     = 89  // the app's own UIDs: everything goes on to netd
	prefOverlay = 90  // T1
	prefLAN     = 151 // T2: the networks this device sits on, back to netd
	prefExclude = 191 // T2: excluded apps, back to netd
	prefCapture = 201 // T2: the catch-all

	netdRuleMin = 10000
	netdRuleMax = 32000

	maxAndroidUID   = 99999
	gapResolveLimit = 64
)

// ourPrefs are every priority the helper writes; nothing else uses them (netd
// starts at 10000; the other root app this one grew beside uses 100-200).
var ourPrefs = []int{prefOwn, prefOverlay, prefLAN, prefExclude, prefCapture}

// uidRange is an inclusive uid range, as netd prints it.
type uidRange struct{ lo, hi int }

func (r uidRange) String() string { return fmt.Sprintf("%d-%d", r.lo, r.hi) }

// foreignVPN is the root-side probe's answer: another VPN client's tunnel
// with apps routed into it.
type foreignVPN struct {
	present bool
	reason  string
	ifaces  []string
	members []uidRange
	netIDs  []int
}

// shape is what the rules built from it depend on.
func (f foreignVPN) shape() string {
	if !f.present {
		return "none"
	}
	var rs []string
	for _, r := range f.members {
		rs = append(rs, r.String())
	}
	sort.Strings(rs)
	ids := append([]int{}, f.netIDs...)
	sort.Ints(ids)
	return strings.Join(rs, ",") + ";" + fmt.Sprint(ids)
}

// guardPrefix is a foreign interface's prefix inside the overlay.
type guardPrefix struct {
	prefix netip.Prefix
	iface  string
}

// rootEnv is what the device looks like, read by the helper as root.
type rootEnv struct {
	foreign      foreignVPN
	lan4, lan6   []netip.Prefix
	cgnat        []guardPrefix
	goto4, goto6 int    // the first rule in netd's band, where skipping traffic goes on
	phys4, phys6 string // the physical network's table, the fallback when goto cannot resolve
	ipv6         bool
}

func (e rootEnv) signature() string {
	var b strings.Builder
	b.WriteString(e.foreign.shape())
	for _, p := range e.lan4 {
		b.WriteString("|" + p.String())
	}
	for _, p := range e.lan6 {
		b.WriteString("|" + p.String())
	}
	for _, g := range e.cgnat {
		b.WriteString("|g" + g.prefix.String() + "@" + g.iface)
	}
	fmt.Fprintf(&b, "|%d|%d|%s|%s|%t", e.goto4, e.goto6, e.phys4, e.phys6, e.ipv6)
	return b.String()
}

// Modes, as the app shows them.
const (
	modeOwn      = "own"      // nobody else: every tier device-wide
	modeShared   = "shared"   // another VPN bypasses some apps: T2 for those, T3 minus its network
	modeYield    = "yield"    // another VPN takes every app: T1 only
	modeOverride = "override" // another VPN, taken anyway
)

// rootPlan is what goes in.
type rootPlan struct {
	mode   string
	reason string
	// capture: T2 at all. captureUIDs nil = device-wide, else one rule per range.
	capture     bool
	captureUIDs []uidRange
	// dns: T3 at all (before the validation hold and the health gate);
	// dnsReturnNetIDs leave another tunnel's resolution alone.
	dns             bool
	dnsReturnNetIDs []int
}

func planRoot(cfg rootConfig, env rootEnv) rootPlan {
	t2 := len(cfg.Routes) > 0 || cfg.Default4 || cfg.Default6
	foreign := env.foreign.present || cfg.ForeignSeen
	p := rootPlan{dns: cfg.DNS != ""}
	switch {
	case !foreign:
		p.mode, p.reason = modeOwn, "no other VPN holds the device"
		p.capture = t2
	case cfg.TakeDevice:
		p.mode, p.reason = modeOverride, "another VPN holds the device; taken anyway, its apps lose their tunnel and their DNS"
		p.capture = t2
	default:
		gaps := carryableGaps(env.foreign.members, cfg)
		if !env.foreign.present || len(gaps) == 0 {
			// Android sees a VPN but the probe cannot say whom it bypasses, or it bypasses nobody.
			p.mode, p.reason = modeYield, "another VPN takes every app: only the overlay is routed"
			p.dns = false
			if env.foreign.reason != "" {
				p.reason += " (" + env.foreign.reason + ")"
			}
			return p
		}
		p.mode = modeShared
		p.reason = fmt.Sprintf("another VPN (%s) bypasses %d uid range(s): those follow NetBird", strings.Join(env.foreign.ifaces, ","), len(gaps))
		p.capture = t2
		p.captureUIDs = gaps
		p.dnsReturnNetIDs = append([]int{}, env.foreign.netIDs...)
	}
	return p
}

// carryableGaps: the uids the other tunnel does not claim, that we may carry —
// installed apps that are not VPN clients and not ours. A small gap is
// resolved app by app (nothing captured that no app owns: Android reserves an
// SDK sandbox uid beside every app); a large one — a client tunnelling only a
// few apps — is kept whole with the known VPN and own uids cut out, rather
// than a rule per app.
func carryableGaps(members []uidRange, cfg rootConfig) []uidRange {
	if len(members) == 0 {
		return nil
	}
	sorted := append([]uidRange{}, members...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].lo < sorted[j].lo })
	var raw []uidRange
	next := 0
	for _, r := range sorted {
		if r.lo > next {
			raw = append(raw, uidRange{next, r.lo - 1})
		}
		if r.hi+1 > next {
			next = r.hi + 1
		}
	}
	if next <= maxAndroidUID {
		raw = append(raw, uidRange{next, maxAndroidUID})
	}
	carry := map[int]bool{}
	for _, u := range cfg.CarryUIDs {
		carry[u] = true
	}
	never := map[int]bool{}
	for _, u := range append(append(append([]int{}, cfg.VPNUIDs...), cfg.OwnUIDs...), cfg.AppUID) {
		never[u] = true
	}
	for _, u := range cfg.OwnUIDs {
		delete(carry, u)
	}
	for _, u := range cfg.VPNUIDs {
		delete(carry, u)
	}
	var out []uidRange
	for _, g := range raw {
		if len(cfg.CarryUIDs) > 0 && g.hi-g.lo+1 <= gapResolveLimit {
			for u := g.lo; u <= g.hi; u++ {
				if carry[u] {
					out = append(out, uidRange{u, u})
				}
			}
			continue
		}
		out = append(out, splitAround(g, never)...)
	}
	return mergeRanges(out)
}

func splitAround(r uidRange, holes map[int]bool) []uidRange {
	var hs []int
	for h := range holes {
		if h >= r.lo && h <= r.hi {
			hs = append(hs, h)
		}
	}
	sort.Ints(hs)
	var out []uidRange
	start := r.lo
	for _, h := range hs {
		if start <= h-1 {
			out = append(out, uidRange{start, h - 1})
		}
		start = h + 1
	}
	if start <= r.hi {
		out = append(out, uidRange{start, r.hi})
	}
	return out
}

func mergeRanges(rs []uidRange) []uidRange {
	if len(rs) == 0 {
		return nil
	}
	sort.Slice(rs, func(i, j int) bool { return rs[i].lo < rs[j].lo })
	out := []uidRange{rs[0]}
	for _, r := range rs[1:] {
		last := &out[len(out)-1]
		if r.lo <= last.hi+1 {
			if r.hi > last.hi {
				last.hi = r.hi
			}
			continue
		}
		out = append(out, r)
	}
	return out
}

// parseForeign reads `ip rule show` (both families, concatenated), the
// interface names and the physical tables into the probe's answer. Both
// traces are needed: a tunnel interface that is not ours, and per-uid rules
// in netd's band that point at it — netd names a VPN network's table after
// its interface. Rules into the physical *local* table carry a uidrange over
// every uid; counting those would leave no gaps.
func parseForeign(rules string, ifaces []string, self string, phys ...string) foreignVPN {
	tunnels := map[string]bool{}
	var tnames []string
	for _, n := range ifaces {
		if n == self || !isTunnelName(n) {
			continue
		}
		if !tunnels[n] {
			tunnels[n] = true
			tnames = append(tnames, n)
		}
	}
	sort.Strings(tnames)
	var members []uidRange
	seenRange := map[uidRange]bool{}
	netIDs := map[int]bool{}
	var hits []string
	sawForeignRule := false
	for _, line := range strings.Split(rules, "\n") {
		pstr, rest, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		pref, err := strconv.Atoi(strings.TrimSpace(pstr))
		if err != nil || pref < netdRuleMin || pref > netdRuleMax {
			continue
		}
		f := strings.Fields(rest)
		table, uids, mark := field(f, "lookup"), field(f, "uidrange"), field(f, "fwmark")
		if table == "" || uids == "" {
			continue
		}
		switch table {
		case "main", "local", "default":
			continue
		}
		skip := false
		for _, p := range phys {
			if p != "" && table == p {
				skip = true
			}
		}
		if skip {
			continue
		}
		if isTunnelName(table) {
			sawForeignRule = true
		}
		if !tunnels[table] {
			continue
		}
		if h := fmt.Sprintf("%d %s", pref, table); len(hits) < 4 && !contains(hits, h) {
			hits = append(hits, h)
		}
		if lo, hi, ok := strings.Cut(uids, "-"); ok {
			l, e1 := strconv.Atoi(lo)
			h, e2 := strconv.Atoi(hi)
			if e1 == nil && e2 == nil && l <= h {
				r := uidRange{l, h}
				if !seenRange[r] {
					seenRange[r] = true
					members = append(members, r)
				}
			}
		}
		if mark != "" {
			v := strings.TrimPrefix(strings.TrimPrefix(strings.SplitN(mark, "/", 2)[0], "0x"), "0X")
			if n, err := strconv.ParseUint(v, 16, 32); err == nil && n&0xffff != 0 {
				netIDs[int(n&0xffff)] = true
			}
		}
	}
	out := foreignVPN{ifaces: tnames, members: members}
	for id := range netIDs {
		out.netIDs = append(out.netIDs, id)
	}
	sort.Ints(out.netIDs)
	switch {
	case len(tnames) > 0 && len(hits) > 0:
		out.present = true
		out.reason = fmt.Sprintf("%s is up and apps are routed to it (%s)", strings.Join(tnames, ","), strings.Join(hits, "; "))
	case len(tnames) > 0:
		out.reason = strings.Join(tnames, ",") + " exists but no app is routed into it"
	case sawForeignRule:
		out.reason = "per-app rules point at a table with no tunnel behind it"
	default:
		out.reason = "no other tunnel"
	}
	return out
}

func isTunnelName(n string) bool {
	for _, p := range []string{"tun", "ppp", "wg", "ipsec"} {
		if strings.HasPrefix(n, p) && len(n) > len(p) {
			if _, err := strconv.Atoi(n[len(p):]); err == nil {
				return true
			}
		}
	}
	return false
}

// field: the word after key in an `ip rule` line.
func field(f []string, key string) string {
	for i := 0; i+1 < len(f); i++ {
		if f[i] == key {
			return f[i+1]
		}
	}
	return ""
}

// firstNetdRule: the lowest priority at or above netd's band, the target of
// every rule that hands traffic back to the platform.
func firstNetdRule(rules string) int {
	best := 0
	for _, line := range strings.Split(rules, "\n") {
		pstr, _, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		n, err := strconv.Atoi(strings.TrimSpace(pstr))
		if err != nil || n < netdRuleMin {
			continue
		}
		if best == 0 || n < best {
			best = n
		}
	}
	return best
}

// parseRouteTable: the table in an `ip route get` answer.
func parseRouteTable(out string) string {
	return field(strings.Fields(out), "table")
}

// ifaceAddr is one line of `ip -o addr show`.
type ifaceAddr struct {
	iface  string
	prefix netip.Prefix // the address with its length, unmasked
}

// parseAddrs reads `ip -o addr show`: "2: wlan0    inet 192.168.1.20/24 brd … scope global wlan0".
func parseAddrs(out string) []ifaceAddr {
	var res []ifaceAddr
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) < 4 || (f[2] != "inet" && f[2] != "inet6") {
			continue
		}
		name, _, _ := strings.Cut(f[1], "@")
		p, err := netip.ParsePrefix(f[3])
		if err != nil {
			continue
		}
		if s := field(f, "scope"); s == "host" || s == "link" {
			continue
		}
		res = append(res, ifaceAddr{iface: name, prefix: p})
	}
	return res
}

var nonLANPrefixes = []string{"lo", "tun", "ppp", "wg", "ipsec", "tailscale", "birdsocks", "docker", "veth", "clat", "v4-", "dummy"}

func isLANIface(n string) bool {
	for _, p := range nonLANPrefixes {
		if strings.HasPrefix(n, p) {
			return false
		}
	}
	return true
}

var (
	cgnat     = netip.MustParsePrefix("100.64.0.0/10")
	linkLocal = netip.MustParsePrefix("169.254.0.0/16")
)

// lanPrefixes: the networks this device sits on, as masked prefixes; the
// overlay's own and CGNAT space are the guard's business, not the LAN's.
func lanPrefixes(addrs []ifaceAddr, overlay []netip.Prefix) (v4, v6 []netip.Prefix) {
	seen := map[netip.Prefix]bool{}
	for _, a := range addrs {
		if !isLANIface(a.iface) {
			continue
		}
		p := a.prefix.Masked()
		addr := p.Addr()
		if addr.IsLoopback() || addr.IsLinkLocalUnicast() || linkLocal.Contains(addr) || cgnat.Contains(addr) {
			continue
		}
		if p.Bits() == 0 {
			continue
		}
		inOverlay := false
		for _, o := range overlay {
			if o.Overlaps(p) {
				inOverlay = true
			}
		}
		if inOverlay || seen[p] {
			continue
		}
		seen[p] = true
		if addr.Is4() {
			v4 = append(v4, p)
		} else {
			v6 = append(v6, p)
		}
	}
	return v4, v6
}

// guardPrefixes: prefixes inside the overlay held by an interface that is
// not ours — a carrier's CGNAT address, another tunnel — which T1 would
// otherwise capture with no way out. A prefix as wide as the overlay itself
// cannot be thrown without throwing NetBird away; refused ones come back
// separately to be named in the log.
func guardPrefixes(addrs []ifaceAddr, overlay []netip.Prefix, self string) (guard []guardPrefix, refused []guardPrefix) {
	seen := map[netip.Prefix]bool{}
	for _, a := range addrs {
		if a.iface == self {
			continue
		}
		p := a.prefix.Masked()
		for _, o := range overlay {
			if o.Addr().Is4() != p.Addr().Is4() || !o.Contains(p.Addr()) {
				continue
			}
			if seen[p] {
				continue
			}
			seen[p] = true
			if p.Bits() <= o.Bits() {
				refused = append(refused, guardPrefix{p, a.iface})
			} else {
				guard = append(guard, guardPrefix{p, a.iface})
			}
		}
	}
	return guard, refused
}

func contains(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}
