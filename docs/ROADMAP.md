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
- Root mode with Private DNS on, on KernelSU and Magisk, and beside a VPN app other than our own sibling.
- The admin panel against NetBird Cloud and a self-hosted server; signing in as this device.

## Next
- **HTTP proxy** next to SOCKS5, for apps that only speak HTTP.
- **Admin panel, phase 2**: users and invites, the audit feed; a token behind SOCKS5 with a password.
- **Weblate**: the hosted project, once the repository is public (docs/TRANSLATING.md).

## Later
- Admin panel, phase 3: policies, networks, DNS, posture checks.
- Root mode at boot, before the app opens.
- F-Droid (reproducible builds, all four ABIs).
