# Root mode

Root mode is VPN mode without Android's VPN slot. With root (Magisk, KernelSU,
APatch), BirdSocks creates a real network interface on this device's NetBird
address and steers traffic into it with Linux policy routing, so another VPN or
an ad blocker can keep the slot.

Pick it in **Settings → Tunnel mode → Root**. The root manager asks once.

## How it works

The NetBird daemon runs as the app, as in every mode. A small helper — the
daemon's own binary run as `libnetbird.so -root-tun` through `su` — creates the
interface (`birdsocks0`, `birdsocksd0` for a debug build), hands it to the
daemon over a unix socket and installs the routing. The helper lives exactly as
long as the app's pipe to it: when BirdSocks stops, when the app dies, when the
daemon lets go of the interface, or on a signal, it removes every rule it made.
A helper that was killed outright leaves its rules behind; the app removes them
the next time it starts, and a new helper does before it installs its own.

## What it claims: three tiers

`ip rule` is evaluated in ascending priority, and every rule below netd's own
(10000 and up) runs before any rule another VPN app's apps get. So the routing
comes in tiers, by how much of the device each one claims:

| Tier | Rules | Claims |
|---|---|---|
| **T1 — NetBird** | priority 90: `to <NetBird range> iif lo lookup 25203`, and the same for the resolver `198.18.0.2` | only packets this device sends to NetBird addresses |
| **T2 — all traffic** | priority 201: `fwmark 0x0/0x20000 iif lo lookup 25204`; the local network back to Android at 151, excluded apps at 191 | everything, while an exit node, a network or *Route all traffic* is selected |
| **T3 — DNS** | the `BIRDSOCKS_DNS` nat chain: port 53 to `198.18.0.2`; IPv6 port 53 to `fd00:bd:53::2` where the kernel has IPv6 NAT, untouched where not | every DNS query on the device |

The app's own packages skip all of it at priority 89: the daemon's WireGuard,
relay and signal connections must reach the real network.

- **The CGNAT guard.** NetBird's default range, 100.64.0.0/10, is also carrier
  NAT space. A prefix inside it held by an interface that is not ours — a
  mobile carrier's address, another tunnel — gets a `throw` route in table
  25203, so its traffic stays on its own network.
- **NetBird names in hosts.** Every peer's name, and this device's, by its full
  name and its first label, goes into a copy of `/etc/hosts` bound over
  `/system/etc/hosts` while Root mode runs (the copy lives in
  `/data/adb/birdsocks/hosts`). Android reads hosts before any DNS server, so
  the names resolve even when its resolver keeps to the router's IPv6 server —
  which the redirect cannot reach on a kernel without IPv6 NAT — and while T3 is
  yielded to another VPN. Only BirdSocks' own bind is ever taken off; another
  module's hosts stays underneath. An app with a resolver of its own (Chrome's,
  DoH) does not read hosts.
- **The local network.** With **Keep the local network** on (the default), the
  networks this phone sits on go back to Android while the tunnel takes all
  traffic, even when a peer routes the same subnet. **Excluded ranges** are
  `throw` routes in table 25204, so a more specific NetBird route still wins.
- **Excluded apps** skip T2 and keep NetBird addresses (T1). For DNS, an
  exclusion only covers apps that resolve names themselves (browsers with
  their own resolver, Termux, DoH apps): Android's resolver sends an ordinary
  app's queries under its own uid.
- **rp_filter** is set to loose while T2 is in (replies to the daemon would fail
  a strict reverse-path check through it) and restored afterwards; the old
  value is kept in `/data/adb/birdsocks/rp_filter.orig` until then.
- **DNS waits and steps aside.** The redirect waits until Android has validated
  the network (its check resolves through the system resolver), and comes off
  after three failed checks of NetBird's resolver, until it answers again.

## Beside another VPN

T1 coexists with anything: no sane VPN routes NetBird's range for itself. T2
and T3 have one owner per device. Every five seconds the helper looks for
another VPN — a `tun`/`ppp`/`wg`/`ipsec` interface with Android's per-app rules
pointing at it — and:

| The other VPN | BirdSocks installs | Main card |
|---|---|---|
| none | T1 + T2 + T3 | Root |
| takes every app | T1 only: NetBird addresses work from every app; no exit node, networks or device-wide DNS | Root · NetBird only |
| bypasses some apps | T1, T2 for the apps it bypasses, T3 except its own network's queries | Root · shared |
| any, with **Take the device anyway** on | T1 + T2 + T3 | Root |

The other VPN's apps keep their tunnel and their DNS unless you take the device.
The SOCKS5 proxy works in every case: point an app or the other VPN's outbound
at it to reach NetBird and the exit node through it.

In the other VPN, it helps to exclude BirdSocks from its tunnel and to keep its
own addresses out of NetBird's range.

## Checking it

```sh
su -c 'ip rule show'                    # 89, 90 and, with an exit node, 151/191/201
su -c 'ip route show table 25203'       # NetBird's range, the resolver, guard throws
su -c 'ip route show table 25204'       # selected networks, the default route
su -c 'ip route get 8.8.8.8'            # → birdsocks0 with an exit node
su -c 'ip route get 192.168.1.1'        # the LAN → wlan0
su -c 'iptables -t nat -S BIRDSOCKS_DNS'
```

After BirdSocks stops, none of these priorities, tables or chains should be
left. The helper's lines are in **Diagnostics → Logs** (category CORE, "Root VPN").

Priorities 89/90/151/191/201 and tables 25203/25204 (25213/25214 for a debug
build) are BirdSocks' alone.
