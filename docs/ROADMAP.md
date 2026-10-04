# BirdSocks roadmap

What 0.1.0 has is in [`CHANGELOG.md`](../CHANGELOG.md). This is what comes next,
roughly in order.

## Verify on devices
- VPN mode: DNS through 198.18.0.2, routes with and without an exit node,
  excluded apps, a daemon crash taking the VPN down, always-on, Private DNS
  "automatic". Rebuilds should update the VPN network in place.
- Sign-in through the browser and with a setup key, on NetBird Cloud and a self-hosted server.
- Direct (P2P) connections from inside the app's sandbox with patch 03; relay as the fallback.
- The proxy: overlay addresses, NetBird DNS names, the internet directly and through an exit node.
- Wi-Fi ↔ mobile switches (see the network monitor below).
- The server connection through a SOCKS5/HTTP proxy and through ByeDPI (patch 08).
- The R8 release build on a device.

## Next
- **HTTP proxy** next to SOCKS5, for apps that only speak HTTP.
- Widgets, Tasker actions, backups — TailSocks had them; port what fits.

## Later
- Root mode.
- A native TUN engine on NetBird's own Android device code.
- F-Droid (reproducible builds, all four ABIs), Weblate.
- An own logo and screenshots.
