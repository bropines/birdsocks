# BirdSocks native TUN: design

**Status (2026-10-04):** a design, not started. The plain TUN mode
(VpnService → hev-socks5-tunnel → BirdSocks' SOCKS5 proxy, as TailSocks'
default TUN) is being restored separately; this document is the *native*
engine, where NetBird's WireGuard device runs on the VpnService fd with no
SOCKS hop — TailSocks' opt-in engine of 4.2.0.

The NetBird daemon stays `GOOS=linux`, CGO off, a child process
(`appctr/core.go:155`). In TUN mode its WireGuard device runs on a
`VpnService` fd instead of a netstack. Proxy mode must stay byte-for-byte
unchanged: the new mode is keyed on an env var, never on `GOOS`.

Paths: `TS/` = `~/projects/tailsocks`, `BS/` = `~/projects/birdsocks`,
`NB/` = `BS/appctr/netbird_src` (NetBird v0.80.0 plus patches 01–07).

---

## 1. How TailSocks' native TUN works (shipped 4.2.0, opt-in)

| Step | Where |
|---|---|
| Switch | `tun_engine=native` (`TS/.../core/GlobalSettings.kt:287`, UI `SettingsActivity.kt:1306`). hev is still the default. |
| Builder | `TunVpnService.startNativeTunInternal` `TS/.../core/TunVpnService.kt:410-466`: `setMtu(1280)` (`:55`); `addAddress` for each node IP as /32 or /128 (`:424-426`); DNS `100.100.100.100` plus `fd7a:115c:a1e0::53` (`:427-428`); routes `100.64.0.0/10` + `fd7a:115c:a1e0::/48`, or `0.0.0.0/0` (`::/0` if the IPv6 setting is on) behind an exit node (`:429-436`); disallows every `io.github.bropines.tailscaled*` package plus the user's list (`:345-370`); `excludeRoute` on API 33+, only behind an exit node (`:379-397`). |
| Node IPs | Come from the bridge `GetSelfIPs` (`appctr/nativetun.go:151-182`). They are cached per profile in `native_tun_self_ips` (`TunVpnService.kt:73-80, 460-463`), with up to 8 s of waiting (`:479-490`). A cold start establishes **before** the daemon runs, from the cache (`TailscaledService.kt:1859-1874`). |
| fd hand-off | `fd.dup().detachFd()` goes to `Appctr.setNativeTun` (`TunVpnService.kt:448-449`). Kotlin keeps the original PFD in `tunFd`. The bridge stores an `*os.File` (`nativetun.go:47-77`) and relaunches the daemon. `daemon.go:77-81` picks `--tun=android-vpn`; `daemon.go:173-176` passes `ExtraFiles` = fd 3 and `TS_TUN_FD=3`. Crash restarts reuse the bridge's copy (`nativetun.go:3-7`). |
| Daemon | Patch 18 `cmd/tailscaled/android_vpn.go`: `CreateUnmonitoredTUNFromFD(TS_TUN_FD)`, plus a pointer-typed **no-op router** that only logs the routes it would have set. Patch 08 hunks in `tailscaled.go` `tryEngine`: `androidVPNMode`, netns off, `newTUN` swap, router swap, and `UseNetstackForIP` for peers and quad-100. Patch 19 `netstack.go`: `CheckLocalTransportEndpoints=true`, `NetstackDialTCP` in `androidVPNMode`; `ProcessLocalIPs` stays false. Probe patch 17 plus `appctr/tunprobe.go` (verified on Android 16 only). |
| Routes / DNS | All of it is set by the **Builder**; tailscaled sets nothing. quad-100 is answered inside the daemon (MagicDNS, split DNS). The base resolver comes from `TS_DNS_FALLBACK` (patch 11 `noop.go`), and tailnet resolvers are dialed via netstack (patch 14). `dnsHasFallback()=false` for native (`GlobalSettings.kt:296-297`). |
| MTU | 1280 on both sides. The device reads it back via SIOCGIFMTU. |
| Coexistence with SOCKS | The daemon keeps `--socks5-server`. The app's UID, and so the daemon's, is excluded from the VPN. The proxy and the daemon's own peer dials therefore go through **netstack** (patches 08/19), and apps outside the VPN and LAN clients still have a working proxy. Leaving TUN: `ClearNativeTun(relaunch)` goes back to `userspace-networking` (`nativetun.go:126-145`). |
| Reconnects | The device is rebuilt only when the default route appears or disappears, the engine changes, the netmap IPs change, or the Builder inputs change (`TunVpnService.kt:203-221`). `ReleaseNativeTunForSwap` stops the daemon (`nativetun.go:85-96`) and `SetNativeTun` starts it once on the new fd. Switching exit node A to B keeps the device. Prefs are mirrored before a relaunch (`refreshOptionsFromDaemon` `:101-121`). The relaunch-vs-dying-daemon race is closed by `waitProcessGone` (`appctr/appctr.go:689,766`). `onRevoke` leads to a stop (`TunVpnService.kt:171-180`). |
| Loop prevention | Exclusion by UID only: no `protect()`, no `SO_MARK` (`docs/NATIVE_TUN_PLAN.md:85-88`). |
| Still owed | Per `NATIVE_TUN_PLAN.md:12-16`, steps 3-5 at `:116-126`, §5 at `:128-150`, and `agents.md:14,188`: an fd swap without a relaunch (exit node on/off costs 3–5 s); `CallbackRouter` instead of the no-op router; `SCM_RIGHTS`; **subnet routes without an exit node** (the Builder only has 100.64/10); Always-on / lockdown; Wi-Fi↔LTE and doze; Android 7/8; what happens when the VpnService owner dies; making it the default and deleting hev. |

What BirdSocks can take from this: UID exclusion works for a child process
(verified, shipped), and the `ExtraFiles` fd works in a child. What it should
not copy: daemon relaunch per route change, a no-op router, and Kotlin
guessing the route set. NetBird already has the swap and the route callback
that TailSocks is missing (§2).

---

## 2. How NetBird's Android client builds the device

**Flow.** `client/android/client.go:156` `NewClient(tunAdapter, iFaceDiscover, listener)` sets
`net.SetAndroidProtectSocketFn` (`:159`). `Run` then calls `RunOnAndroid` (`client.go:222`;
`internal/connect.go:125-148`), which wraps the listener in a `tunnelnotifier`
(an async queue, `internal/tunnelnotifier/notifier.go:41-100`) and calls `c.run(MobileDependency{...})`.

1. `engine.go:2207` `newWgIface`: when `GOOS==android` (`:2223-2228`), it sets
   `MobileArgs.TunAdapter`. Then `iface_new_android.go:23-27` calls `device.NewTunDevice(...)`.
2. `engine.go:2238` `wgInterfaceCreate`: when android (`:2240-2241`), it calls
   `CreateOnAndroid(routeManager.CurrentRouteRange(), dnsServer.DnsIP(), dnsServer.SearchDomains())`.
   This runs inside `Engine.Start` **under `syncMsgMux`** (`engine.go:545-547`).
3. `iface/device/device_android.go:53-103`: `TunAdapter.ConfigureInterface(addr/32,
   addr6, mtu, dns, "a;b", "r1;r2")` returns an **fd**. Then `tun.CreateUnmonitoredTUNFromFD`,
   `RenewableTUN.AddDevice`, `newDeviceFilter`, `device.NewDevice(…, iceBind)`, and
   `NewUSPConfigurer` (with a UAPI socket).
4. Route changes: the route manager's notifier (`routemanager/notifier/notifier_android.go:41-71`,
   with a diff gate) calls `listener.OnNetworkChanged("")`. So do search-domain changes (`dns/notifier.go:47-54`).
   Java then calls `Client.GetTunSettings` (`client.go:287-306`, which uses `engine_tunsettings.go:3-20`),
   re-establishes, and calls `Client.RenewTun(fd)` (`client.go:273-285`), which goes to `engine.go:2550-2562`
   and then `device_android.go:119-134`. `RenewableTUN.AddDevice` swaps the device and closes the old one
   (`renewable_tun.go:261-286`), followed by `rebindOverlayListeners` (`engine.go:2582`).
   The route list is `CurrentRouteRange` (`routemanager/manager.go:472-483`): the overlay v4/v6
   networks, the **selected** non-dynamic client routes (exit node → `0.0.0.0/0`), and fake-IP blocks.
5. DNS: when android (`engine.go:2259-2271`), the engine uses `NewDefaultServerPermanentUpstream`
   (`dns/server.go:240-259`). This is the memory service: the IP is the last IP of the overlay
   network (`dns/service_memory.go:29-41`), and it hooks UDP/TCP 53 on the FilteredDevice
   (`:101-160`). Replies are written to the device directly. The root zone comes from host DNS
   (`hostsDNSHolder`, `OnUpdatedHostDNSServer` `server.go:555`, `registerFallback` `:794-831`).
   The host manager is a no-op (`host_android.go`). Upstreams (`upstream_android.go:43-81`) dial
   with a plain socket "within VPN", which works there because the app's UID **is** in its own VPN.
   Domain routes use fake IPs (`routemanager/manager.go:432-434`).
6. Sockets are protected with `VpnService.protect()` (`net/protectsocket_android.go`).

**GOOS=android only (by tag or file suffix):** `iface/device/device_android.go`,
`renewable_tun.go`, `device_netstack_android.go`; `iface/iface_new_android.go`,
`iface_create_android.go`, `iface/device_android.go`, `iface_destroy_mobile.go`;
`routemanager/notifier/notifier_android.go`; `systemops/systemops_android.go`;
`dns/server_android.go`, `host_android.go`, `upstream_android.go`;
`internal/connect_android_{default,embed}.go`; `peer/ice/stdnet_android.go`;
`net/protectsocket_android.go`; all of `client/android/`. The `runtime.GOOS` switches are at
`engine.go:2223, 2239, 2259`, `routemanager/manager.go:432`, `wg_iface_monitor.go:38`,
`connect.go:260`, and `profilemanager/config.go:502`.

**Already generic (compiles under GOOS=linux):** the `TunAdapter` interface
(`device/adapter.go:4-8`), `MobileIFaceArguments` (`device/args.go`), `MobileDependency`
(`internal/mobile_dependency.go:13-26`), `RunOnAndroid`, and the **`androidRunOverride` hook**
(`connect.go:55-57, 117-121`; `server.connect` reaches it via `client.Run`, `server/server.go:2537`).
Also generic: `Engine.RenewTun`, `TunSettings`, `CurrentRouteRange`, `fakeip`,
`NewDefaultServerPermanentUpstream`, `ServiceViaMemory`, `hostsDNSHolder`, `tunnelnotifier`,
uspfilter, `FilteredDevice`, `netevents` (patch 04), wireguard-go `CreateUnmonitoredTUNFromFD`,
the fork's `Device.CreateOutboundPacket`, and `ExchangeWithNetstack` (`dns/upstream.go:717`).

**Under GOOS=linux, `!netstack.IsEnabled()` means "kernel".** Every such gate in a TUN mode
would reach netlink, iptables or nftables, sysfs, or UAPI. On Android, netlink use is dangerous
because of the leak recorded at `connect.go:~366` (patch 06): a denied netlink bind leaks an
`*os.File` whose finalizer later closes a reused fd. That fd could be the TUN fd or the control
socket. Every such gate must therefore treat TUN mode as "no kernel" (§3.2).

---

## 3. Design for BirdSocks

### 3.0 Mode

The mode is set by `NB_BIRDSOCKS_VPN_CTL=3`, the fd of a control socket. In this mode
`NB_USE_NETSTACK_MODE` is **unset**, because `netstack.IsEnabled()` means "netstack is
the device". Two helpers go in `client/iface/netstack/env.go` (already patch 01's file):

- `VpnFdEnabled()`: true when the variable is set.
- `NoKernel() = IsEnabled() || VpnFdEnabled()`: used by the "do not touch the kernel" gates.

The bridge also sets `NB_DISABLE_CUSTOM_ROUTING=true`, `NB_SKIP_SOCKET_MARK=true` and
`NB_FORCE_USERSPACE_FIREWALL=true` (existing knobs: `net/env.go:13`, `net/env_linux.go:20`,
`firewall/iface.go:14`) as a second safeguard.

### 3.1 Getting the fd into the child: a control socketpair plus SCM_RIGHTS

Inheriting the TUN fd alone (the TailSocks way) does not fit NetBird.

- The device is created at **engine** start, after `Up`. That happens many times during one
  daemon's life: Down/Up, a profile switch, a reconnect.
- Every engine stop **closes** the device and its fd (`device_android.go:141-156`, `RenewableTUN.Close`).
- NetBird wants a **new** fd whenever the routes change.

An fd 3 handed over at launch would die with the first engine. Every route change
would then mean relaunching the daemon, at TailSocks' cost of 3–5 s.

**Design.**

- At `Start` (`BS/appctr/core.go:132-170`), when `StartOptions.TunMode` is set, the bridge creates
  `unix.Socketpair(AF_UNIX, SOCK_SEQPACKET|SOCK_CLOEXEC)`. SEQPACKET keeps message boundaries,
  so one frame can carry one fd.
- The child end becomes `cmd.ExtraFiles=[child]`, i.e. fd 3, with env `NB_BIRDSOCKS_VPN_CTL=3`.
  The parent closes its copy of the child end after `Start`, and `go serveVpnCtl(parent)` begins.
- On each daemon launch there is a new pair. EOF from the daemon means it exited; EOF from the
  bridge makes the daemon tear the engine down.
- The fd arrives from Kotlin as an int. Go calls a gomobile interface
  `VpnController.Establish(cfgJSON string) int32`, which Kotlin implements and which returns
  `pfd.dup().detachFd()` (or -1). The bridge sends it with
  `unix.Sendmsg(…, unix.UnixRights(fd))` and then `unix.Close(fd)`.
- **Kotlin keeps the original PFD.** The VPN lives exactly as long as Kotlin's PFD, so a daemon
  crash does not drop the VPN: it black-holes traffic, which acts as a kill switch.
- On a restart, the new engine asks again. If the config is unchanged, Kotlin answers with
  another `dup()` of the same PFD: no re-establish, no gap.
- The probe stage alone uses `ExtraFiles` = the TUN fd itself (TailSocks patch 17 analogue).
  That confirms ioctl access on the device. A second probe must confirm SCM_RIGHTS
  (TailSocks never verified it; `NATIVE_TUN_PLAN.md:138`).

Frames are a JSON object in each SEQPACKET message, with an fd in the ancillary data where noted:

| Direction | op | Body | Meaning |
|---|---|---|---|
| d→b | `establish` | `id, addr, addr6, mtu, dns, search[], routes[]` | Comes from `ConfigureInterface` or a route/DNS change. |
| b→d | `tun` (+fd) / `tun_err` | `id, error` | Answer. The daemon waits ≤15 s, then the engine start fails with an error and its backoff retries. |
| d→b | `down` | – | The device closed (engine stop). Kotlin decides whether to tear down: not when Always-on is set. |
| b→d | `hostdns` | `servers[]` | The network's DNS servers (from `SetDNSServers`, `core.go:108`) go to `dns.GetServerDns().OnUpdatedHostDNSServer` (`server_export.go:14`, `server.go:555`). |
| b→d | `revoked` | – | `onRevoke`. The daemon logs it; the app relaunches the daemon in proxy mode. |

The daemon-side transport is a new package `NB/client/internal/vpnctl` (`//go:build linux`).
Its reader loop only dispatches: it reads a frame and hands it off. It never waits on engine
locks, which is TailSocks plan §3.7's lock invariant.

### 3.2 Patch `08-vpn-fd.patch`: the daemon builds its WireGuard device from the fd

New files (all `//go:build linux && !android`, so the Android build is unaffected):

- `iface/device/device_vpnfd_linux.go`: a `VpnFdDevice`, mirroring `device_android.go` (BSD-3, upstream).
  - `CreateWith(routes, dns, search)`: `adapter.ConfigureInterface`, then `CreateUnmonitoredTUNFromFD`,
    then `RenewableTUN.AddDevice`.
  - Builds the **side netstack** (§3.4) and a `splitTUN{kernel: RenewableTUN, ns}`, then
    `newDeviceFilter(split)` and `device.NewDevice`.
  - Uses **`NewUSPConfigurerNoUAPI`**, as `device_netstack.go:91` does. UAPI would try
    `/var/run/wireguard`.
  - Provides `Create()` for the linux `WGTunDevice` interface (`iface/device.go:15-27`),
    `RenewTun(fd)`, `GetNet()` returning the side netstack, and `Close`, which releases the
    proxy backend and the netstack.
- `iface/device/split_tun_linux.go`: the two-stack device and flow table (§3.4).
- `internal/connect_vpnfd_linux.go`: an `init()` that, when `VpnFdEnabled()`, sets
  `androidRunOverride` (`connect.go:57`) to run `c.run(MobileDependency{TunAdapter: vpnctl adapter,
  NetworkChangeListener: tunnelnotifier.New(vpnListener{c}, nil), HostDNSAddresses: vpnctl.HostDNS(),
  DnsReadyListener: noop}, runningChan, logPath)`.
  `vpnListener.OnNetworkChanged` runs `c.Engine().TunSettings()`, then `establish`, then
  `c.Engine().RenewTun(fd)`. It runs on the notifier's goroutine, so `TunSettings` waiting on
  `syncMsgMux` cannot deadlock. `ProtectSocket` returns true (UID-excluded); `UpdateAddr` re-establishes.
- `internal/dns/host_vpnfd.go`: a holder-backed no-op host manager, the same semantics as `host_android.go`.

Hunks in upstream files. Each one is a single condition:

| File:line | Change |
|---|---|
| `iface/netstack/env.go` | `VpnFdEnabled()`, `NoKernel()` |
| `iface/iface_new_linux.go:16` | Add a `VpnFdEnabled()` branch **before** the kernel and tun module probes, returning `NewVpnFdDevice(…, opts.MobileArgs.TunAdapter, opts.DisableDNS)` with `userspaceBind: true` and `NewUSPFactory`. |
| `iface/iface_create.go:24,28` | `CreateOnAndroid` and `RenewTun` forward to `w.tun` when it implements `CreateWith` and `RenewTun`. |
| `iface/iface.go:233` | `NoKernel()`: no `waitUntilRemoved`, no netlink `Destroy`. The VPN interface outlives the engine. |
| `iface/device/renewable_tun.go:1` | Tag `android` becomes `linux` (android implies linux). |
| `internal/engine.go:2223` | In vpnfd mode, set `opts.MobileArgs.TunAdapter`. |
| `internal/engine.go:2239` | In vpnfd mode, use `CreateOnAndroid(CurrentRouteRange(), DnsIP(), SearchDomains())`. |
| `internal/engine.go:2259` | In vpnfd mode, use `NewDefaultServerPermanentUpstream(…mobileDep.HostDNSAddresses…, NetworkChangeListener…)`. |
| `internal/engine.go:615` (patch 05 hunk) | `NoKernel()`, so the proxies get the search domains in both modes. |
| `internal/engine.go:2458` | `NoKernel()`: no network monitor (netlink). |
| `internal/connect.go:374` | `!NoKernel() && WireGuardModuleIsLoaded()`. **Critical**: this is the netlink `LinkAdd` probe that leaks. |
| `internal/wg_iface_monitor.go:43` | `NoKernel()`: no RTNLGRP_LINK subscription. |
| `internal/stdnet/stdnet.go:58` | `NoKernel()`: `pionDiscover`, which uses anet via patch 03. |
| `internal/routemanager/manager.go:147` | `useNoop := NoKernel() ‖ …` |
| `internal/routemanager/manager.go:432` | `GOOS=="android" ‖ VpnFdEnabled()`: fake-IP domain routes. Android cannot add /32 routes without a re-establish. |
| `internal/routemanager/manager.go:681` | `NoKernel()`, which allows 0/0 routes. |
| `internal/routemanager/systemops/systemops_generic.go:66` | `NoKernel()`: no-op refcounter. |
| `internal/routemanager/notifier/notifier_android.go:1` / `notifier_other.go:1` | Tags become `linux` / `!linux && !ios`. A nil listener is a no-op, so desktop is unchanged. |
| `internal/dns/server_unix.go:5` | `initialize()` returns the holder host manager in vpnfd mode. Lines `server.go:456,717` keep `IsEnabled()`. |
| `internal/dns/upstream_general.go:44` | In vpnfd mode, an upstream that is overlay or peer-routed goes through `ExchangeWithNetstack(nsNet…)`. Host DNS servers are dialed directly. |
| `net/env.go:21`, `net/env_linux.go:59`, `net/env_bound_iface.go:41` | `NoKernel()`: no fwmark or advanced routing. |
| `firewall/create_linux.go:48-49,101` | `NoKernel()`: uspfilter only, allower nil, no iptables/nftables probe. |
| `birdsocksd/main.go:68` (not a patch, our source) | `StartFront()` when `NoKernel()`. Also seed `hostdns` from `-dns-file`. |

The profile/interface blacklist (`profilemanager/config.go:57`) has only `"tun0"`, and the match
is a **prefix** match (`stdnet/filter.go:13-25`). In vpnfd mode, add `"tun"`. Otherwise ICE could
gather a candidate on `tun1` after a re-establish.

The ROADMAP's "NetBird's own Android device code" does not mean `GOOS=android`. That would drag in
the protect, IFaceDiscover and system-info dependencies that panic without the Java app
(`BS/agents.md:19-26`), and it would change the proxy-mode binary.

### 3.3 Routes and DNS into `VpnService.Builder`, and their updates

The daemon pushes; Kotlin only executes. Do **not** rebuild the route set in Kotlin from
`ListNetworks`/status. `CurrentRouteRange` contains things gRPC does not expose: fake-IP blocks,
v6 merges, selected-only exit nodes. This is the callback NetBird's own design uses, and it fixes
TailSocks' gap "subnet routes without exit node" for free.

Kotlin `BirdVpnService.establish(cfg)`:

- `setSession("BirdSocks")`, `setMtu(cfg.mtu)` (NetBird's default is 1280), `addAddress(addr)` and `addAddress(addr6)`.
- `addDnsServer(cfg.dns)` when it is non-empty (`DisableDNS` gives ""), plus `addSearchDomain` for each search domain.
- `addRoute` for each of `cfg.routes`: the overlay /16 and v6 net, network routes, `0.0.0.0/0` and `::/0` for an exit node, and fake-IP blocks.
- An exit node carrying only v4: add `::/0` anyway (no v6 peer, so v6 is dropped and does not leak). This needs a setting decision.
- "Allow LAN" behind an exit node: `excludeRoute` for the underlying network's prefixes, API 33+ only (the TailSocks code at `TunVpnService.kt:379-397`).
- `addDisallowedApplication(packageName)`, **always**, plus the user's excluded apps.
- `setMetered(false)` (Q+), `setUnderlyingNetworks(null)`.
- Before calling `establish()`: if the config JSON equals the current one, return `currentPfd.dup().detachFd()`.

Update triggers, all through `OnNetworkChanged`:

- exit node select/deselect (`SelectNetworks` RPC);
- the first network map after connect (the routes arrive after the device; this is the normal NetBird sequence, see the comment at `engine.go:2564-2581`);
- network route changes;
- search domains.

Address changes come through `UpdateAddr`. Host DNS comes from the existing
`NetbirdService.writeDnsServers` (`NetbirdService.kt:406-409`): `Appctr.setDNSServers` also sends a `hostdns` frame.

### 3.4 What replaces netstack's SOCKS proxy

- **Kernel dial from the daemon does not work.** The daemon's UID is excluded, so its sockets route via the underlying network.
  - Making it *not* excluded needs `protect()` on **every** socket: ICE, relay WS, signal, management, Go resolver, PKCE HTTP, the proxy's direct dials. From a child that means either an SCM_RIGHTS round-trip to `VpnService.protect` per socket, or speaking netd's fwmarkd `PROTECT_FROM_VPN` protocol by hand. One missed socket is a routing loop under `0/0`.
  - `SO_BINDTODEVICE tun0` from an excluded UID finds no rule in Android's per-UID policy routing.
  - `Network.bindSocket` refuses VPNs to UIDs outside them.
  - Rejected.
- **Without any userspace path in TUN mode:** excluded apps and LAN clients lose NetBird entirely. And NetBird nameservers hosted on peers break, because `upstream_general.go` dials them with a kernel socket from the excluded UID. Acceptable for stage 1 only.
- **Chosen: a side netstack on the same device, like TailSocks' netstack next to the TUN.**
  - `wireguard-go/tun/netstack.CreateNetTUN([v4,v6], [dnsIP], mtu)` gives a `tun.Device` plus `*netstack.Net`.
  - `splitTUN` sits **under** `FilteredDevice`, so uspfilter, conntrack, the DNS memory hooks and packet capture see both stacks.
  - `Read`: two pump goroutines (kernel `RenewableTUN`, netstack) feed one channel. The cost is one extra copy; batch size is 1 on Android anyway. An optimisation for later: wake a blocked kernel read with `SetReadDeadline` instead of pumping.
  - Each packet read from the netstack records `(proto, srcPort, dstIP, dstPort)` in a **flow table** (TCP: until FIN/RST plus linger, or 5 min idle; UDP: 60 s idle).
  - `Write` (WireGuard to host): if the reverse tuple is in the table, the packet goes to the netstack; anything else goes to the kernel TUN.
  - The DNS memory service replies by writing to `dev.Device` (`service_memory.go:143-147`), which is this split device. Replies to queries from the netstack's resolver therefore reach the netstack, and replies to app queries reach the kernel.
  - The fork's netstack hides its gVisor stack (`netbirdio/wireguard-go …/tun/netstack/tun.go:44-56`), so TailSocks' `CheckLocalTransportEndpoints` cannot be used. The flow table needs no fork.
  - Collision risk: a kernel socket and the netstack picking the same 4-tuple at the same moment. Fragments and ICMP errors without an L4 match go to the kernel.
- The netstack is lent to the proxies the way `iface/netstack/tun.go` already does. A new exported `netstack.Lend(tunNet, dnsAddr) (release func())` wraps `newBackend` plus `setBackend` (`front.go:41,106`), and `SetRouteSource(overlay, overlay6, device.IpcGet)` (`route.go:41`) decides what is overlay.
- The SOCKS5 proxy and DNS proxy then behave exactly as in proxy mode: overlay via the netstack, everything else direct. Direct now means the underlying network, because the UID is excluded.
- Peer-hosted DNS upstreams use the same netstack (§3.2, `upstream_general.go`).
- Inbound to the device from peers is not handled by the netstack. It arrives on the kernel TUN, and Android delivers it to listeners on `0.0.0.0` (Termux sshd, adbd), with uspfilter ACLs in front. Patch 07's forwarding to `127.0.0.1` does not apply, so 127.0.0.1-only services become unreachable in TUN mode.
  - **Must add:** drop inbound to the app's own proxy ports when SOCKS is shared on `0.0.0.0`. `NB_BIRDSOCKS_NO_FORWARD_PORTS` has to apply in uspfilter's local path, not only in the forwarder.

### 3.5 Excluded apps, the app itself, UID

- The daemon has the same UID and the same SELinux domain as the app.
  - `core.go:155` is a plain `exec.Command(bin…)` from `nativeLibraryDir`, with no `SysProcAttr.Credential`.
  - `birdsocksd/main.go:94-105` already relies on it: the socket is 0600 and ipcauth checks the peer UID.
- `addDisallowedApplication(packageName)` excludes the **UID**, so the daemon's WireGuard, ICE, relay, signal, management and direct proxy dials bypass the VPN.
  - TailSocks ships exactly this and has verified it (`NATIVE_TUN_PLAN.md:85-88`; patch 18 header).
  - `protect()` is not needed.
- Excluded apps reach NetBird through the SOCKS proxy.
- Since the app is outside its own VPN, `registerDefaultNetworkCallback` (`NetbirdService.kt:158`) keeps reporting the underlying network. So `files/dns-servers` never contains NetBird's DNS IP and cannot loop. This needs to be verified in stage 0.
- Always-on lockdown exempts the VPN package's own UID (AOSP `Vpn.setVpnForcedLocked` adds `mPackage` to the exemptions), so the daemon can still reach management before the VPN is up. Disallowed **other** apps are blocked under lockdown; that should be documented.

---

## 4. Work, effort, risks, stages

**Daemon.** Patch `08-vpn-fd.patch` (§3.2): about 8 new files and about 20 one-condition hunks.
The patch list also goes into `recreate_patches.sh`. `birdsocksd/main.go` needs the gate at `:68` and the hostdns seed.

**Bridge** (`BS/appctr`):
- `StartOptions.TunMode`.
- `core.go` `Start`/`daemonEnv` (`:132-170`, `:203-280`): the socketpair, `ExtraFiles`, env without `NB_USE_NETSTACK_MODE` (`:239`), plus the three knobs.
- A new `vpn.go`: the `VpnController` interface, `SetVpnController`/`ClearVpnController`, `serveVpnCtl`, `VpnRevoked()`, and a `hostdns` push from `SetDNSServers`.
- `core_live_test.go` gets a socketpair harness with a fake TUN (a Linux TUN under root, or a pipe-backed `tun.Device`).

**Kotlin:**
- `core/BirdVpnService.kt` (new):
  - Builder from JSON; holds and compares the PFD; `Establish` / `Teardown`.
  - `onRevoke`: `Appctr.vpnRevoked()`, then NetbirdService relaunches in proxy mode and posts a notice.
  - Goes foreground on `NetbirdService`'s notification id (one card, as in TailSocks).
- Manifest: `BIND_VPN_SERVICE`, `android.net.VpnService` intent-filter, `specialUse` subtype, `android.net.VpnService.SUPPORTS_ALWAYS_ON`.
- `NetbirdService.kt`:
  - `startOptions()` gets `TunMode` (`:210`).
  - Start `BirdVpnService` and register the controller **before** `Appctr.start` (`:194`).
  - A system (Always-on) start of the VpnService starts NetbirdService.
  - Teardown on Down, unless Always-on is set.
- `GlobalSettings`: `tun_mode`, `tun_excluded_apps`, `tun_allow_lan`, `tun_block_ipv6`.
- UI: a Settings switch (HelpText), VPN consent in `ui/PermissionsActivity.kt`, and an app picker ported from TailSocks `ui/TunExcludedAppsActivity.kt`.
- Strings in EN/RU (`strings_birdsocks.xml`).
- Docs: CHANGELOG, ROADMAP(_RU), agents.md architecture.

**Effort:** about **11–15 engineer-days**.

| Stage | Days |
|---|---|
| 0 Probe | 1 |
| 1 Dark daemon mode | 3 |
| 2 Updates and swap | 2–3 |
| 3 Side netstack | 2–3 |
| 4 Kotlin product | 2–3 |
| 5 Device matrix | 1–2 |

TailSocks' estimate for the comparable cut was 8–12 days. Here, NetBird's `RenewTun` and route callback remove the hardest part, and the side netstack adds about 2–3 days.

**Risks:**
1. **netlink fd-leak class.** One gate missed in §3.2 and a finalizer can close the TUN fd or the control socket at random. Mitigation: `NoKernel` everywhere; a debug check that logs any netlink socket creation in vpnfd mode; ideally patch the leak itself.
2. **`ConfigureInterface` blocks `Engine.Start` under `syncMsgMux`.** Needs a hard 15 s timeout. The VPN consent must be granted before TUN mode can be switched on, because a missing consent means `establish()` returns null and the start fails.
3. **Re-establish gap.** Android creates a new `tunN` and resets the old one; there is a short loss. The JSON-equality gate in Kotlin plus NetBird's route diff gate (`notifier_android.go:54`) keep re-establishes rare.
4. **fd lifetime.**
   - Kotlin's PFD owns the VPN. A daemon crash means a black hole until the restart (3 per 5 min, `NetbirdService.kt:368`).
   - When the app process dies, Android kills the process group and tears the VPN down. Unverified, the same open item as TailSocks.
   - The bridge must never keep a TUN fd copy, or a stopped daemon keeps nothing alive, but a leaked copy keeps a dead VPN alive.
5. **Always-on / lockdown.**
   - The daemon is exempt as the VPN package.
   - The user pressing Down while Always-on is set must keep the PFD, or Android restarts the service in a loop.
   - Boot start on HyperOS needs autostart permission.
6. **Android versions.**
   - minSdk 24, but TUN ioctls in a child are verified on 16 only. Test on WSA (13) and an old device, or gate TUN mode to API ≥ 26 until verified.
   - `excludeRoute` needs 33+, `setMetered` 29+.
7. **HyperOS.** The phantom-process killer (Android 12+) and MIUI battery policy can kill the daemon (a child process). The foreground VPN service helps. Verify on the POCO (Android 16, HyperOS) and the Redmi.
8. **Another VPN.** On the POCO, AdGuard in VPN mode loses its slot (`onRevoke` on one side or the other). Private DNS in "strict" mode bypasses the VPN's DNS server, so NetBird names will not resolve. Both need UI warnings.
9. **Inbound semantics differ from proxy mode.** Services bound to 127.0.0.1 are unreachable, and an own SOCKS port on 0.0.0.0 is exposed unless uspfilter drops it (§3.4).
10. **Licensing.** Mirror the Go `client/android` / `device_android.go` (BSD-3). Never copy the GPLv3 Java app (`BS/agents.md` licensing).

**Stages** (each one ships with proxy mode unchanged):

0. **Probe** (debug build, the TailSocks patch 17 pattern).
   - A test VpnService passes a `dup` to a short-lived `libnetbird.so` via `ExtraFiles`, and a second one via **SCM_RIGHTS** over a socketpair. The child does `fstat`/`TUNGETIFF`/`CreateUnmonitoredTUNFromFD`/`MTU()` and reads one packet. Check `avc:` denials.
   - With a `0/0` VPN up and the app disallowed: does the running daemon still reach management, relay and STUN? Does `registerDefaultNetworkCallback` report Wi-Fi/LTE?
   - Done when both hand-offs work on the POCO, WSA and the Redmi.
1. **Dark daemon mode.**
   - Patch 08 minus the side netstack, plus the control channel with `establish` at engine start only. Behind a hidden setting.
   - Done when a browser in the VPN reaches peers by IP and by NetBird name, and `core_live_test` in proxy mode is unchanged.
2. **Updates.**
   - `OnNetworkChanged`, then `RenewTun`: exit node on/off with no daemon relaunch, network routes, domain routes (fake IP), search domains.
   - Also `hostdns` frames, Down/Up, profile switch, and a daemon crash restart reusing the PFD.
3. **Side netstack.**
   - `splitTUN` plus the flow table, SOCKS and DNS proxy through the lent backend, and peer-hosted DNS upstreams.
   - Unit tests for flow routing; SOCKS from an excluded app reaches a peer.
4. **Product.**
   - Settings, consent, excluded apps, allow-LAN, IPv6 blocking, revoke handling, Always-on, one notification, the tile.
5. **Device matrix and docs.**
   - Wi-Fi↔LTE, doze, revoke and re-grant, Always-on plus lockdown, throughput against proxy mode.
   - CHANGELOG and ROADMAP updated: move "native TUN" from Later to shipped. hev-on-SOCKS then becomes unnecessary; delete `app/src/main/jni/hev-socks5-tunnel` from BirdSocks.
