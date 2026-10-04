<h1 align="center">BirdSocks</h1>

<p align="center">
  <strong>An unofficial NetBird client for Android that gives your NetBird network to apps through a SOCKS5 proxy</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="readme_ru.md">Русский</a>
</p>

BirdSocks runs the real [NetBird](https://netbird.io) client on the phone as a
userspace node. Apps reach the network through a local SOCKS5 proxy and a DNS
proxy, so BirdSocks does not need Android's VPN slot and can run next to another
VPN, an ad-blocker or a proxy client such as Throne. When an app cannot use a
proxy, a VPN mode is there too.

The project's base comes from [TailSocks](https://github.com/bropines/tailsocks).

> 0.1.0 is not released yet. See [`CHANGELOG.md`](CHANGELOG.md) and the
> [roadmap](docs/ROADMAP.md).

## Features

- **Sign-in:** NetBird Cloud or your own server, through the browser (SSO) or with a setup key.
- **Several accounts:** each has its own keys and settings, and you switch between them from the main screen.
- **SOCKS5 proxy, `127.0.0.1:48125` by default:**
  - reaches peers, the networks they route and NetBird DNS names;
  - sends everything else straight to the internet, or through an exit node;
  - supports UDP;
  - can ask for a username and password, and can be opened to the LAN.
- **DNS proxy, `127.0.0.1:48153`:**
  - answers NetBird names first;
  - sends other names to the network's resolvers or to your own;
  - never sends a NetBird name to a public resolver.
- **VPN mode (TUN):** for apps without proxy settings. By default only NetBird's ranges go through it; all traffic does when you ask, or when an exit node is on. Apps can be excluded.
- **Peers and networks:** see a peer's path (P2P or relay), latency and traffic. Choose networks and an exit node; an exit node that forwards nothing is flagged.
- **DNS screen:** the proxy test, lookups, nameservers, and the DNS records the server gave this device.
- **Server connection:** reach the NetBird server through a SOCKS5 or HTTP proxy, or through the built-in ByeDPI, on networks that block it.
- **Access and publish:**
  - peers can reach the phone's own services, such as adb, Termux's sshd or a web server;
  - a local port can get a public address through NetBird's reverse proxy.
- **Diagnostics:**
  - the state of the connection and of the servers;
  - NetBird events, with warnings shown as notifications;
  - an access check (what the firewall does with a packet);
  - packet capture to `.pcap`, logs, and the debug bundle.
- **Around the app:** a Quick Settings tile, start on boot, and `birdsocks://` links to every screen.

## How it works

```
apps ──SOCKS5──► BirdSocks proxy ─┬─► NetBird netstack ──WireGuard──► peers
apps ──DNS─────► BirdSocks DNS ───┤                                   (P2P or relay)
VPN mode: Android VPN → hev-socks5-tunnel → the same proxy
                                  └─► the internet, directly or through an exit node
```

The NetBird daemon (`client/server` from a pinned release, plus a few patches)
runs as its own process in netstack mode. The app talks to it over its gRPC API.
The details are in [`agents.md`](agents.md).

## Getting started

1. Install the APK, open BirdSocks and sign in: choose NetBird Cloud or enter your server, then use the browser or a setup key.
2. Point your apps at `127.0.0.1:48125`, as SOCKS5 with remote DNS (`socks5h`). Proxy clients such as Throne or AdGuard can carry it for apps that have no proxy setting.
3. For DNS, use `127.0.0.1:48153` in a client that lets you set it. Otherwise turn on VPN mode in Settings → Tunnel mode.

The [user guide](docs/GUIDE.md) covers setups with AdGuard and Throne, the
server connection and ByeDPI, and what to do when something does not connect.

## Documentation

| | |
|---|---|
| [User guide](docs/GUIDE.md) | Setup, every mode, troubleshooting |
| [Building](docs/BUILDING.md) | The NetBird daemon, the bridge and the APK |
| [Releasing](docs/RELEASING.md) | From a clean checkout to signed APKs |
| [Translating](docs/TRANSLATING.md) | Adding a language |
| [Roadmap](docs/ROADMAP.md) | What comes next |
| [`agents.md`](agents.md) | Architecture and rules for contributors |
| [Data catalog](docs/DATA_CATALOG.md) | What the daemon reports and what the app shows |

## License

BSD 3-Clause, like the NetBird client it builds on. The components the app ships
are listed in [`LICENSE`](LICENSE). BirdSocks is not affiliated with NetBird
GmbH; "NetBird" is their trademark.
