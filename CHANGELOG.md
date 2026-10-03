# Changelog

BirdSocks is a fork of [TailSocks](https://github.com/bropines/tailsocks) 4.6.1;
TailSocks' history is in its own repository.

## [0.1.0] - Unreleased

### Added
- NetBird v0.80.0 as a userspace node with a local SOCKS5 proxy, without the VPN slot.
- Sign-in to NetBird Cloud or an own server, with a setup key or through the browser.
- Status card, device addresses, the proxy's URI and an exit node picker on the main screen.
- Peers with search, filters and connection details; Networks with route selection.
- Settings: device name, logout, proxy port, credentials and LAN sharing, NetBird's profile switches, force relay, log level.
- Quick Settings tile, start on boot, `birdsocks://` links to screens.
- The proxy listens on 127.0.0.1:48125 and asks for a username and password when they are set.
- Names resolve through NetBird's DNS; destinations no peer routes go out directly.
- The dashboard shows the device as Android with its model and version.
- Interfaces are listed the way Android 11+ allows, so direct connections can form.
- The proxy comes back after a reconnect or a profile switch.
- A DNS proxy on 127.0.0.1:48153: NetBird names first, everything else through the network's resolvers.
- A network switch or loss reaches the daemon, so its connections redial at once.
- Several accounts: switch from the title on the main screen, rename and remove in Settings.
- UDP through the proxy (UDP ASSOCIATE), to peers and to the internet; the relay answers only the client that asked.
- The DNS proxy can send what NetBird does not answer to another resolver, such as TailSocks' DNS proxy.
- The daemon restarts by itself after a crash, up to three times in five minutes.
- Peer counts read as peers and active connections, with lazy connections explained.
- The management server row in Settings opens its dashboard.
