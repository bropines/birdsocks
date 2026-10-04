# Contributing to BirdSocks

BirdSocks is an Android client for NetBird. It runs NetBird's own daemon as a
child process, in netstack mode, and offers the network to apps through a
local SOCKS5 proxy, a DNS proxy and an optional VPN mode. It does not need the
VPN slot unless you turn VPN mode on.

## Read first
- [`agents.md`](agents.md) sets the rules: how the daemon, the bridge and the app fit together, what goes into a patch, the UI conventions, licensing.
- [`docs/BUILDING.md`](docs/BUILDING.md) explains how to build the daemon (`appctr/build.sh`) and the APK.
- [`docs/RELEASING.md`](docs/RELEASING.md) explains how a release is made.
- [`docs/TRANSLATING.md`](docs/TRANSLATING.md) explains how to add a language.

## The rules that matter most
- The daemon is NetBird's. Talk to it through its gRPC API, never a CLI. Change its behaviour only with a patch in `appctr/patches/`: edit `appctr/netbird_src/`, run `appctr/patches/recreate_patches.sh`, then `appctr/build.sh`.
- Never copy code from NetBird's official Android app. It is GPLv3; read it to learn how things behave, then write your own.
- Put new strings in the `res/values*/strings_<area>.xml` of their screen, in English and Russian.
- Add a short bullet to the top section of [`CHANGELOG.md`](CHANGELOG.md) for every change in behaviour. The reasons go in the commit message.
- Keep commits small, with conventional messages (`feat:`, `fix:`, `docs:`…).

## Before a pull request
- `./gradlew assembleDebug lintDebug -PlintNewApiOnly`. The app supports API 24+.
- If you touched Go: `go test -ldflags=-checklinkname=0` for the packages you changed, run in `appctr/netbird_src`.
- Try it on a device: sign-in, the proxy, and whatever you changed.

By contributing you agree that your work is published under the BSD 3-Clause
License of this repository.
