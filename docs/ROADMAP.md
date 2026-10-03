# BirdSocks roadmap

What 0.1.0 has is in [`CHANGELOG.md`](../CHANGELOG.md). This is what comes next,
roughly in order.

## Verify on devices
- Sign-in through the browser and with a setup key, on NetBird Cloud and a self-hosted server.
- Direct (P2P) connections from inside the app's sandbox with patch 03; relay as the fallback.
- The proxy: overlay addresses, NetBird DNS names, the internet directly and through an exit node.
- Wi-Fi ↔ mobile switches (see the network monitor below).

## Next
- **Network changes.** NetBird's network monitor is off in netstack mode, so a
  switch of the default network is noticed only when connections fail. Wire the
  app's network callback to the engine (a patch: a signal or an RPC that triggers
  NetBird's own network-change handling), as TailSocks did with SIGUSR1.
- **DNS proxy.** A local DNS server that forwards to NetBird's resolver, for apps
  and ad-blockers that take a DNS address rather than a proxy.
- **TUN mode** through `hev-socks5-tunnel` on top of the SOCKS5 proxy (TailSocks'
  shipped design), with per-app exclusions.
- **Control-plane proxy and DPI bypass.** NetBird's dialers have no proxy support;
  patch `client/net.Dialer` for management, signal and relay, and bring back
  ByeDPI. `NB_RELAY_TRANSPORT=ws` already moves the relay to WebSocket.
- **Profiles**: several NetBird accounts or servers, switched from the main screen.
- **Session expiry**: SubscribeEvents carries the warnings; show them and offer
  `RequestExtendAuthSession`.
- **HTTP proxy** next to SOCKS5, for apps that only speak HTTP.
- Widgets, Tasker actions, backups — TailSocks had them; port what fits.

## Later
- Root mode.
- A native TUN engine on NetBird's own Android device code.
- F-Droid (reproducible builds, all four ABIs), Weblate.
- An own logo and screenshots.
