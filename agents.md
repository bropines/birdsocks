# BirdSocks: developer guidelines and project mandate

Mandatory rules and working knowledge for anyone — agent or human — changing
this repository.

---

## Architecture

BirdSocks is an Android client for [NetBird](https://netbird.io) that does what
TailSocks does for Tailscale: it runs the client as a **userspace node** and
hands its network to apps through a **SOCKS5 proxy**, without taking Android's
VPN slot.

1. **The daemon (`libnetbird.so`).** NetBird's own daemon server
   (`client/server`, the one `netbird service run` hosts on desktops), started by
   our entry point [`appctr/birdsocksd`](appctr/birdsocksd/) and serving its gRPC
   API on a unix socket in the app's files directory. It is built from the
   pinned NetBird release (`appctr/NETBIRD_VERSION`) with the patches in
   `appctr/patches/`, as a **static `GOOS=linux` binary**: NetBird's `android`
   code paths belong to its own app's library (a TUN fd, DNS-ready and network
   callbacks from Java) and fail in a daemon, while its Linux **netstack mode**
   (`NB_USE_NETSTACK_MODE=true`) is the supported unprivileged one. The app runs
   it as a child process from `nativeLibraryDir`.
2. **The bridge (`appctr`, gomobile).** [`core.go`](appctr/core.go) starts and
   stops the daemon and files its output into the log ring;
   [`rpc.go`](appctr/rpc.go) is the app's gRPC client: `Call(method, json)` for
   any unary method of `daemon.proto` (types read from the descriptor) and
   `Subscribe` for server streams. Kotlin speaks protobuf JSON.
3. **The app (Kotlin, Compose).** `core/NetbirdService` keeps the daemon in the
   foreground and mirrors its status stream into `NetbirdState`; `core/Netbird.kt`
   wraps the RPCs; the screens read the state flows.

### Rules

* **The daemon is NetBird's, unmodified in behaviour except by patches.** Talk to
  it only through its gRPC API. Never shell out to a CLI.
* **Linux paths, Android facts.** Everything Android-specific a Linux binary
  cannot find for itself comes from the app: the network's DNS servers
  (`files/dns-servers`, re-read on change — `birdsocksd/resolver.go`), the time
  zone (`TZ`, with `time/tzdata` embedded), the device name (`-hostname`), the
  OS, model and API level (`NB_ANDROID_*`, patches 02 and 03).
* **Android 11+ forbids netlink link dumps to apps.** `net.Interfaces()` and
  `Interface.Addrs()` fail inside the app's sandbox; patch 03 routes NetBird's
  interface listing through a vendored copy of `wlynxg/anet`. Any new code in the
  daemon that lists interfaces must use `client/internal/anet`.
* **The SOCKS5 proxy (patch 01)** asks for a username and password when both are
  set (`NB_SOCKS5_USER`/`PASS`), resolves names through NetBird's DNS first, and
  dials destinations no peer routes directly from the host — a proxy for
  everything, not only the overlay.
* **VPN mode** (`core/TunVpnService`) feeds Android's VPN into the SOCKS5 proxy
  through hev-socks5-tunnel. It announces exactly one DNS server, 198.18.0.2,
  which the proxy answers itself (patch 01, `tundns.go`); never announce
  NetBird's resolver or a second server. Every `io.github.bropines.birdsocks*`
  package stays outside the VPN (a loop guard, and it keeps
  `files/dns-servers` on the real network). The VPN is down whenever the
  daemon is: stop it before any daemon stop or restart and on a crash. hev
  gets no `mapdns` — its fake-IP pool is NetBird's 100.64.0.0/10.
* **Signing in** is `Login` then, when it asks, the browser (device-code flow:
  `verificationURIComplete` and `userCode`) and `WaitSSOLogin`, then `Up`.
  `LoginFlow` runs it in the app's scope so the browser taking the screen does
  not cancel the wait. The tile and boot paths cannot sign in; they show
  "Sign-in needed".
* **Accounts are NetBird profiles.** `default` is addressed without a username;
  every other profile under the user the daemon runs as (`USER=birdsocks`,
  `Netbird.USER`), stored in `files/netbird/birdsocks/`. Calls that take a
  profile (GetConfig, SetConfig) address the active one.

---

## Build and patches

```bash
cd appctr && bash build.sh        # TS_ABIS=arm64-v8a for one ABI
./gradlew app:assembleDebug       # application id suffix .dev
```

* `build.sh` downloads the NetBird release, checks it against
  `appctr/NETBIRD_SHA256`, unpacks it twice (`netbird_orig`, `netbird_src`),
  applies `patches/*.patch` with `patch -p1 -F0`, copies `birdsocksd/*.go` into
  `netbird_src/client/birdsocksd`, builds the daemon per ABI and binds the bridge.
* **Daemon changes are patches.** Edit `appctr/netbird_src/`, then run
  `appctr/patches/recreate_patches.sh`, which diffs against `netbird_orig` and
  lists each patch's files. Never hand-edit a `.patch`; never commit
  `netbird_src/`, `netbird_orig/` or `.so` files.
* `-checklinkname=0` is required: the vendored anet links into `net.zoneCache`.
* `appctr/core_live_test.go` drives a host build of the daemon end to end:
  `BIRDSOCKS_LIVE_LIB=<dir with libnetbird.so> go test -run TestDaemonLive`
  (`BIRDSOCKS_LIVE_SETUP_KEY` also logs in and connects).
* Release builds are R8-minified: no reflection-based JSON (use
  `kotlinx.serialization` via `core/AppJson.kt`), no `getIdentifier`.

---

## Licensing

The NetBird client (`client/`, `shared/`, `util/`…) is BSD-3, like this
repository. Its `management/`, `signal/`, `relay/` and `combined/` directories are
AGPLv3 and are never built into the app. The official NetBird Android app
(`netbirdio/android-client`) is **GPLv3: a reference, never a source** — read it to
learn behaviour, write our own code. `client/internal/anet` carries anet's
BSD-3 notice.

---

## UI standards

Inherited from TailSocks and still binding:

* Material 3 through `BirdSocksTheme`; semantic colours, `MaterialTheme.shapes`.
* Every screen uses `AppTopBar`. Explanations fold through `HelpText` (two lines,
  ⓘ, unfold on tap or long press); long text belongs in dialogs. No ⋮ menus on
  cards.
* Every new string goes in English to `values/` and in Russian to `values-ru/`.
* Never block the main thread on the bridge: `Netbird.*` calls are suspend
  functions on `Dispatchers.IO`.
* The structure follows TailSocks': the main screen is the status card, banners
  only when something needs the user, the exit-node row and six tiles (Peers,
  Networks, DNS, Publish, Diagnostics, Settings). This device lives in Peers as
  its first row. Settings is a hub of sections (`SettingsSections` ids, opened by
  `EXTRA_OPEN_SECTION` and `birdsocks://settings?section=`); each setting lives in
  one place. Accounts are managed only in the account sheet.
* Chip selectors are `SlidingSegmentedChips` (draggable) or the scrollable
  variant; when they switch content they drive a `HorizontalPager` through
  `positionOffset`.
* New strings of a screen group go in that group's own file (`strings_peers`,
  `strings_settings`, `strings_diagnostics`, `strings_main`, `strings_tun`, …).

---

## Working agreements

* **Never run `git push`** — the author publishes, after testing, on «пушь».
  Commit locally as much as you like.
* **Every Go or patch change goes through `appctr/build.sh`** before an APK means
  anything.
* Code, comments, commits and docs in English; the author is answered in his
  language (Russian). Documentation is bilingual (`X.md` / `X_RU.md`).
* `CHANGELOG.md`: one terse line per change; the why goes in commit bodies.
* **Never `adb uninstall` without explicit permission**, and then with `-k`.
* HyperOS asks on screen before an `adb install`: a locked phone means the author
  has to be there.
