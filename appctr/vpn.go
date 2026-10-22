package appctr

// vpn.go — the app's VPN mode: Android's VPN interface, established on the
// device's NetBird address, goes to the daemon over its VPN socket
// (netstack/vpn.go in the daemon), which then carries the apps' packets
// itself. A new interface replaces the old one there; no daemon restart.

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"
)

const vpnCallWait = 10 * time.Second

type vpnRequest struct {
	Op    string   `json:"op"`
	MTU   int      `json:"mtu,omitempty"`
	Addrs []string `json:"addrs,omitempty"`
}

type vpnReply struct {
	Error string `json:"error"`
}

// AttachVPN hands the daemon whose files are in dir the VPN interface fd,
// with its MTU and its addresses (comma-separated). fd is given away: the
// caller passes a detached duplicate, and it is closed here whatever
// happens — the daemon keeps its own copy.
func AttachVPN(dir string, fd int, mtu int, addrs string) error {
	defer syscall.Close(fd)
	var list []string
	for _, a := range strings.Split(addrs, ",") {
		if a = strings.TrimSpace(a); a != "" {
			list = append(list, a)
		}
	}
	return vpnCall(dir, vpnRequest{Op: "attach", MTU: mtu, Addrs: list}, fd)
}

// DetachVPN tells the daemon in dir the VPN interface is going: it stops using it.
func DetachVPN(dir string) error {
	return vpnCall(dir, vpnRequest{Op: "detach"}, -1)
}

func vpnCall(dir string, req vpnRequest, fd int) error {
	if dir == "" {
		return errors.New("no data directory")
	}
	c, err := net.DialUnix("unixpacket", nil, &net.UnixAddr{Name: vpnSocketPath(dir), Net: "unixpacket"})
	if err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(vpnCallWait))
	b, err := json.Marshal(req)
	if err != nil {
		return err
	}
	var oob []byte
	if fd >= 0 {
		oob = syscall.UnixRights(fd)
	}
	if _, _, err := c.WriteMsgUnix(b, oob, nil); err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	buf := make([]byte, 4096)
	n, err := c.Read(buf)
	if err != nil {
		return fmt.Errorf("VPN socket: %w", err)
	}
	var r vpnReply
	if err := json.Unmarshal(buf[:n], &r); err != nil {
		return fmt.Errorf("VPN socket reply: %w", err)
	}
	if r.Error != "" {
		return errors.New(r.Error)
	}
	return nil
}
