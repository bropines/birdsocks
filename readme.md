<h1 align="center">BirdSocks</h1>

<p align="center">
  <strong>Unofficial NetBird client for Android — your NetBird network as a SOCKS5 proxy</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="readme_ru.md">Русский</a>
</p>

BirdSocks runs the [NetBird](https://netbird.io) client as a userspace node and
hands its network to apps through a local SOCKS5 proxy. It does not take
Android's VPN slot, so it lives next to another VPN, an ad-blocker or a work
profile. It is the NetBird sibling of
[TailSocks](https://github.com/bropines/tailsocks), and is built from it.

> Early days: 0.1.0 is not released. See [`CHANGELOG.md`](CHANGELOG.md) and the
> [roadmap](docs/ROADMAP.md).

## What it does

* Signs in to NetBird Cloud or your own server, with a setup key or in the browser.
* Serves a SOCKS5 proxy (optionally with a username and password, optionally on
  the LAN) that reaches your peers, the networks they route, NetBird's DNS names —
  and the rest of the internet, directly or through an exit node.
* Shows peers with their connection path, latency and traffic; selects networks
  and the exit node.

## How

The NetBird daemon (`client/server` from the pinned release, a few patches on
top) runs as its own process in netstack mode; the app talks to it over its gRPC
API. Details: [`agents.md`](agents.md), building: [`docs/BUILDING.md`](docs/BUILDING.md).

## License

BSD 3-Clause, like the NetBird client it builds on. BirdSocks is not affiliated
with NetBird GmbH; "NetBird" is their trademark.
