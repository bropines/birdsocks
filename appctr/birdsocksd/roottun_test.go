//go:build linux

package main

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"golang.org/x/sys/unix"
)

func validRootConfig() rootConfig {
	return rootConfig{
		Name:     "birdsocks0",
		MTU:      1280,
		Addrs:    []string{"100.92.1.5", "fd00::5"},
		Overlay:  []string{"100.92.0.0/16"},
		Routes:   []string{"10.20.0.0/24"},
		Table:    25203,
		DNS:      "198.18.0.2",
		Socket:   "/data/user/0/io.github.bropines.birdsocks/files/vpn.sock",
		Chain:    "BIRDSOCKS",
		StateDir: "/data/adb/birdsocks",
	}
}

func TestRootConfigValidate(t *testing.T) {
	if err := validRootConfig().validate(); err != nil {
		t.Fatalf("a valid config was refused: %v", err)
	}
	bad := map[string]func(*rootConfig){
		"interface name": func(c *rootConfig) { c.Name = "a name with spaces" },
		"long name":      func(c *rootConfig) { c.Name = "birdsocks-is-too-long" },
		"MTU":            func(c *rootConfig) { c.MTU = 100 },
		"no address":     func(c *rootConfig) { c.Addrs = nil },
		"bad address":    func(c *rootConfig) { c.Addrs = []string{"not-an-ip"} },
		"main table":     func(c *rootConfig) { c.Table = 254 },
		"second table":   func(c *rootConfig) { c.Table = 252 },
		"bad chain":      func(c *rootConfig) { c.Chain = "A-B" },
		"no chain":       func(c *rootConfig) { c.Chain = "" },
		"state dir":      func(c *rootConfig) { c.StateDir = "/data/local/tmp" },
		"bad overlay":    func(c *rootConfig) { c.Overlay = []string{"100.92.0.0"} },
		"bad route":      func(c *rootConfig) { c.Routes = []string{"100.92.0.0/40"} },
		"bad exclusion":  func(c *rootConfig) { c.ExcludeCIDRs = []string{"lan"} },
		"bad DNS":        func(c *rootConfig) { c.DNS = "dns.example" },
		"no socket":      func(c *rootConfig) { c.Socket = "" },
	}
	for name, mutate := range bad {
		c := validRootConfig()
		mutate(&c)
		if err := c.validate(); err == nil {
			t.Errorf("%s: an invalid config was accepted", name)
		}
	}
}

func TestRootConfigSameLink(t *testing.T) {
	a, b := validRootConfig(), validRootConfig()
	b.Routes = append(b.Routes, "0.0.0.0/0")
	b.Default4 = true
	if !a.sameLink(b) {
		t.Error("a route change rebuilt the interface")
	}
	b.Addrs = []string{"100.92.1.6"}
	if a.sameLink(b) {
		t.Error("a new address kept the old interface")
	}
}

func TestRootConfigFamilies(t *testing.T) {
	c := validRootConfig()
	if v4, ok := c.v4(); !ok || v4.String() != "100.92.1.5" {
		t.Errorf("v4 = %v %v", v4, ok)
	}
	if v6, ok := c.v6(); !ok || v6.String() != "fd00::5" {
		t.Errorf("v6 = %v %v", v6, ok)
	}
	c.Addrs = []string{"100.92.1.5"}
	if _, ok := c.v6(); ok {
		t.Error("an IPv4-only config has an IPv6 address")
	}
}

func TestSelinuxType(t *testing.T) {
	cases := map[string]string{
		"u:r:untrusted_app:s0:c104,c257,c512,c768": "untrusted_app",
		"u:r:magisk:s0": "magisk",
		"u:r:su:s0":     "su",
		"garbage":       "",
	}
	for ctx, want := range cases {
		if got := selinuxType(ctx); got != want {
			t.Errorf("selinuxType(%q) = %q, want %q", ctx, got, want)
		}
	}
}

func TestUniqueUIDs(t *testing.T) {
	got := uniqueUIDs([]int{10360, 10200, 10200, 0, -1}, 10360)
	if !reflect.DeepEqual(got, []int{10360, 10200}) {
		t.Errorf("uniqueUIDs = %v, want [10360 10200]: the app first, no duplicates, no root or negatives", got)
	}
}

func TestHelperLockKeepsASecondHelperOut(t *testing.T) {
	dir := t.TempDir()
	defer func() {
		if heldLock != nil {
			_ = heldLock.Close()
			heldLock = nil
		}
	}()
	if err := takeHelperLock(dir, 0); err != nil {
		t.Fatalf("first lock: %v", err)
	}
	// Another helper: its own open file description of the same lock.
	f, err := os.OpenFile(filepath.Join(dir, "helper.lock"), os.O_RDWR, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB); !errors.Is(err, unix.EWOULDBLOCK) {
		t.Fatalf("second helper got the lock: %v", err)
	}
	_ = heldLock.Close()
	heldLock = nil
	if err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		t.Fatalf("lock not free after the first helper let go: %v", err)
	}
}
