//go:build linux

package main

// roottun_lock.go — one helper per state directory. Two helpers of one app
// share the routing tables, the chains and the hosts bind, so the second's
// install and the first's teardown would take each other's rules: the old
// helper of an app process that just died is still tearing down when the new
// process starts its own. A helper holds an exclusive flock on
// <StateDir>/helper.lock from its first config to its exit (the kernel lets go
// of it however the process ends); a new one waits for it, and a sweep never
// takes what a living helper holds.

import (
	"errors"
	"os"
	"path/filepath"
	"time"

	"golang.org/x/sys/unix"
)

// lockWait: how long a new helper waits for an old one's teardown.
const lockWait = 10 * time.Second

// sweepWait is shorter: the app gives a sweep 10 s in all.
const sweepWait = 7 * time.Second

var errHelperBusy = errors.New("another helper still holds this app's rules")

// heldLock is the lock file this process holds, kept open to its exit.
var heldLock *os.File

// takeHelperLock takes the state directory's lock, waiting up to wait for a
// helper that holds it. No state directory: nothing to lock.
func takeHelperLock(stateDir string, wait time.Duration) error {
	if heldLock != nil || stateDir == "" {
		return nil
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(filepath.Join(stateDir, "helper.lock"), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(wait)
	for {
		err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB)
		if err == nil {
			heldLock = f
			return nil
		}
		if !errors.Is(err, unix.EWOULDBLOCK) || time.Now().After(deadline) {
			_ = f.Close()
			if errors.Is(err, unix.EWOULDBLOCK) {
				return errHelperBusy
			}
			return err
		}
		time.Sleep(200 * time.Millisecond)
	}
}
