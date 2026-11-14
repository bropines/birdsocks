//go:build linux

package main

import (
	"net/netip"
	"reflect"
	"strings"
	"testing"
)

// Measured on a phone with another VPN up (the other root app's research
// notes): netd's own rules, its VPN's member ranges and the physical
// network's local table, which spans every uid.
const foreignRules = `0:	from all lookup local
10000:	from all fwmark 0xc0000/0xd0000 lookup legacy_system
11000:	from all iif lo oif tun0 uidrange 0-99999 lookup tun0
12000:	from all iif tun0 lookup local_network
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 0-10353 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10355-10374 lookup tun0
13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10376-99999 lookup tun0
14000:	from all iif lo oif wlan0 uidrange 0-0 lookup wlan0
16000:	from all fwmark 0x10084/0x1ffff iif lo uidrange 0-10353 lookup tun0
16000:	from all fwmark 0x10084/0x1ffff iif lo uidrange 10355-10374 lookup tun0
17000:	from all iif lo oif wlan0_local uidrange 0-99999 lookup wlan0_local
21000:	from all fwmark 0x10073/0x1ffff iif lo lookup wlan0
32000:	from all unreachable`

func TestParseForeign(t *testing.T) {
	f := parseForeign(foreignRules, []string{"lo", "wlan0", "tun0", "birdsocks0"}, "birdsocks0", "wlan0")
	if !f.present {
		t.Fatalf("a VPN with members was not seen: %s", f.reason)
	}
	want := []uidRange{{0, 99999}, {0, 10353}, {10355, 10374}, {10376, 99999}}
	if !reflect.DeepEqual(f.members, want) {
		t.Errorf("members = %v, want %v", f.members, want)
	}
	if !reflect.DeepEqual(f.netIDs, []int{0x84}) {
		t.Errorf("netIDs = %v, want [0x84]", f.netIDs)
	}
	// Without the interface the rules are leftovers, not a VPN.
	if g := parseForeign(foreignRules, []string{"lo", "wlan0"}, "birdsocks0", "wlan0"); g.present {
		t.Errorf("rules without a tunnel interface counted as a VPN: %s", g.reason)
	}
	// Our own interface never counts, whatever its name.
	if g := parseForeign("13000:	from all iif lo uidrange 0-100 lookup tun1", []string{"tun1"}, "tun1"); g.present {
		t.Error("our own interface was taken for another VPN")
	}
}

func TestPlanYieldsAndShares(t *testing.T) {
	cfg := validRootConfig()
	cfg.Default4 = true
	cfg.AppUID = 10375
	cfg.OwnUIDs = []int{10375}

	if p := planRoot(cfg, rootEnv{}); p.mode != modeOwn || !p.capture || p.captureUIDs != nil || !p.dns {
		t.Errorf("alone: %+v", p)
	}

	// The 11000 rule spans every uid: a VPN that takes the whole device.
	full := parseForeign(foreignRules, []string{"tun0"}, "birdsocks0", "wlan0")
	if p := planRoot(cfg, rootEnv{foreign: full}); p.mode != modeYield || p.capture || p.dns {
		t.Errorf("full VPN: %+v", p)
	}

	// Without it, the gaps are the client itself (10354, a VPN app) and us (10375).
	partialRules := strings.Replace(foreignRules, "11000:	from all iif lo oif tun0 uidrange 0-99999 lookup tun0\n", "", 1)
	partial := parseForeign(partialRules, []string{"tun0"}, "birdsocks0", "wlan0")
	cfg.VPNUIDs = []int{10354}
	cfg.CarryUIDs = []int{10354, 10375, 10200}
	if p := planRoot(cfg, rootEnv{foreign: partial}); p.mode != modeYield {
		t.Errorf("a VPN that bypasses only itself and us left something to carry: %+v", p)
	}
	partialRules = strings.ReplaceAll(partialRules, "uidrange 0-10353", "uidrange 0-10349")
	partial = parseForeign(partialRules, []string{"tun0"}, "birdsocks0", "wlan0")
	cfg.CarryUIDs = []int{10350, 10352, 10375}
	p := planRoot(cfg, rootEnv{foreign: partial})
	if p.mode != modeShared || !reflect.DeepEqual(p.captureUIDs, []uidRange{{10350, 10350}, {10352, 10352}}) {
		t.Errorf("partial: %+v", p)
	}
	if !reflect.DeepEqual(p.dnsReturnNetIDs, []int{0x84}) {
		t.Errorf("partial DNS carve-out = %v", p.dnsReturnNetIDs)
	}

	cfg.TakeDevice = true
	if p := planRoot(cfg, rootEnv{foreign: full}); p.mode != modeOverride || !p.capture || p.captureUIDs != nil {
		t.Errorf("override: %+v", p)
	}

	// Android sees a VPN the probe cannot: nothing device-wide.
	cfg.TakeDevice = false
	cfg.ForeignSeen = true
	if p := planRoot(cfg, rootEnv{}); p.mode != modeYield {
		t.Errorf("Android's VPN: %+v", p)
	}
}

func TestCarryableGapsLargeGapKeptWhole(t *testing.T) {
	cfg := rootConfig{AppUID: 10375, OwnUIDs: []int{10375}, VPNUIDs: []int{10400}, CarryUIDs: []int{10375, 10401}}
	got := carryableGaps([]uidRange{{0, 10299}}, cfg)
	want := []uidRange{{10300, 10374}, {10376, 10399}, {10401, 99999}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("gaps = %v, want %v", got, want)
	}
}

const addrs = `1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
1: lo    inet6 ::1/128 scope host \       valid_lft forever preferred_lft forever
25: rmnet_data2    inet 100.71.4.9/30 scope global rmnet_data2\       valid_lft forever preferred_lft forever
30: wlan0    inet 192.168.1.20/24 brd 192.168.1.255 scope global wlan0\       valid_lft forever preferred_lft forever
30: wlan0    inet6 fe80::1/64 scope link \       valid_lft forever preferred_lft forever
30: wlan0    inet6 2a02:1:2:3::5/64 scope global dynamic \       valid_lft 1 preferred_lft 1
40: tun0    inet 172.19.0.1/30 scope global tun0\       valid_lft forever preferred_lft forever
41: tailscale0    inet 100.92.7.7/32 scope global tailscale0\       valid_lft forever preferred_lft forever
50: birdsocks0    inet 100.92.1.5/32 scope global birdsocks0\       valid_lft forever preferred_lft forever
51: v4-rmnet_data2    inet 192.0.0.4/32 scope global v4-rmnet_data2\       valid_lft forever preferred_lft forever`

func TestLANAndGuard(t *testing.T) {
	a := parseAddrs(addrs)
	overlay := []netip.Prefix{netip.MustParsePrefix("100.64.0.0/10")}
	v4, v6 := lanPrefixes(a, overlay)
	if !reflect.DeepEqual(v4, []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}) {
		t.Errorf("LAN v4 = %v", v4)
	}
	if !reflect.DeepEqual(v6, []netip.Prefix{netip.MustParsePrefix("2a02:1:2:3::/64")}) {
		t.Errorf("LAN v6 = %v", v6)
	}
	guard, refused := guardPrefixes(a, overlay, "birdsocks0")
	var got []string
	for _, g := range guard {
		got = append(got, g.prefix.String()+"@"+g.iface)
	}
	want := []string{"100.71.4.8/30@rmnet_data2", "100.92.7.7/32@tailscale0"}
	if !reflect.DeepEqual(got, want) || len(refused) != 0 {
		t.Errorf("guard = %v refused = %v, want %v", got, refused, want)
	}
	if _, refused := guardPrefixes([]ifaceAddr{{"rmnet0", netip.MustParsePrefix("100.70.0.1/10")}}, overlay, "x"); len(refused) != 1 {
		t.Error("a prefix as wide as the overlay was not refused")
	}
}

func TestFirstNetdRuleAndTable(t *testing.T) {
	if got := firstNetdRule(foreignRules); got != 10000 {
		t.Errorf("firstNetdRule = %d", got)
	}
	if got := parseRouteTable("1.1.1.1 via 192.168.1.1 dev wlan0 table wlan0 src 192.168.1.20 uid 0 \n    cache "); got != "wlan0" {
		t.Errorf("table = %q", got)
	}
}

func TestHostsLines(t *testing.T) {
	got := hostsLines([]hostEntry{
		{IP: "100.104.212.85", Names: []string{"MacBook-Air.netbird.selfhosted.", "macbook-air", "macbook-air"}},
		{IP: "100.104.9.9", Names: []string{"localhost", "wpad", "bad name", "x;rm -rf"}},
		{IP: "not-an-ip", Names: []string{"ghost.netbird.selfhosted"}},
		{IP: "fd61::5", Names: []string{"v6.netbird.selfhosted"}},
	})
	want := []string{"100.104.212.85 macbook-air.netbird.selfhosted macbook-air", "fd61::5 v6.netbird.selfhosted"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("hostsLines = %q, want %q", got, want)
	}
}
