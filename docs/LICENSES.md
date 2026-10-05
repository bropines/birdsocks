# Licenses

*Русская версия: [LICENSES_RU.md](LICENSES_RU.md)*

BirdSocks is licensed under the [BSD 3-Clause License](../LICENSE),
Copyright (c) 2026, Bropines. The components it builds on and ships keep
their own licenses:

| Component | In BirdSocks | License |
|---|---|---|
| [NetBird client](https://github.com/netbirdio/netbird) (`client/`, `shared/`, `util/` …) | the daemon, with the patches in `appctr/patches/` | BSD-3-Clause |
| [wireguard-go](https://github.com/netbirdio/wireguard-go) (NetBird's fork) | WireGuard in userspace | MIT |
| [gVisor](https://github.com/google/gvisor) netstack | the daemon's TCP/IP stack | Apache-2.0 |
| [Pion](https://github.com/pion) ICE (NetBird's fork), STUN, TURN, DTLS | connections between peers | MIT |
| [go-socks5](https://github.com/things-go/go-socks5) | the SOCKS5 proxy (patch 01) | MIT |
| [anet](https://github.com/wlynxg/anet) | network interfaces on Android 11+, vendored (patch 03) | BSD-3-Clause |
| [gRPC-Go](https://github.com/grpc/grpc-go), [Go protobuf](https://github.com/protocolbuffers/protobuf-go) | the daemon's API and the bridge | Apache-2.0, BSD-3-Clause |
| [Go](https://go.dev) and [golang.org/x/mobile](https://github.com/golang/mobile) | the runtime of every Go binary; the bridge | BSD-3-Clause |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | VPN mode's tunnel | MIT |
| [lwIP](https://savannah.nongnu.org/projects/lwip) | hev-socks5-tunnel's TCP/IP stack | BSD-3-Clause |
| [hev-task-system](https://github.com/heiher/hev-task-system) | hev-socks5-tunnel's task runtime | MIT |
| [ByeDPI](https://github.com/hufrea/byedpi) | the server connection against DPI | MIT |
| [AndroidX, Jetpack Compose](https://developer.android.com/jetpack/androidx), [Material Components](https://github.com/material-components/material-components-android), [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), [OkHttp](https://github.com/square/okhttp) | the app | Apache-2.0 |

The daemon links about 110 Go modules in all (NetBird's `go.mod`): Apache-2.0,
MIT, BSD, ISC and CC0, and three unmodified MPL-2.0 HashiCorp modules
(`errwrap`, `go-multierror`, `go-version`). None is GPL or AGPL.

**NetBird's AGPL parts are not linked.** NetBird's `management/`, `signal/`,
`relay/`, `combined/`, `proxy/` and `tools/idp-migrate/` are AGPLv3 — the
servers, not the client. No package from them is built into BirdSocks;
`go list -deps ./client/birdsocksd` in `appctr/netbird_src` shows it. Nor is
any code taken from NetBird's official Android app, which is GPLv3.

## In the app

Settings → About → **Licenses** lists the components; **Full texts** opens
every license in full, as MIT and BSD ask a binary to carry them. The texts
live in [`app/src/main/assets/third_party_licenses.txt`](../app/src/main/assets/third_party_licenses.txt);
update it when a component is added or changes its license.

## Trademark

NetBird is a trademark of NetBird GmbH. BirdSocks is an unofficial client: it
is not made, endorsed or supported by NetBird GmbH and is not affiliated with
it. The name says only which network the app works with.
