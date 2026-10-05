# Building BirdSocks

## What gets built

* **`libnetbird.so`** — the NetBird daemon: NetBird's `client/server` behind our
  entry point `appctr/birdsocksd`, built from the release pinned in
  `appctr/NETBIRD_VERSION` (checked against `appctr/NETBIRD_SHA256`) with the
  patches in `appctr/patches/`. It is a static `GOOS=linux` binary, one per ABI,
  that the app runs as its own process.
* **`appctr.aar`** — the gomobile bridge (`appctr/*.go`): starts the daemon and
  talks to its gRPC API.
* **`libhev-socks5-tunnel.so`** — the VPN mode's tunnel, from the
  `app/src/main/jni/hev-socks5-tunnel` submodule, built by Gradle's ndkBuild.
* **`libbyedpi.so`** — ByeDPI (`app/src/main/jni/byedpi`), also built by ndkBuild.
* **The APK** — Kotlin and Compose, with all of the above.

## Steps

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# an existing clone: git submodule update --init
export ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358
cd appctr && bash build.sh && cd ..      # TS_ABIS=arm64-v8a builds one ABI
./gradlew app:assembleDebug              # installs beside a release (.dev)
```

`build.sh` needs Go (the version in `appctr/go.mod`), `gomobile` and the NDK.
Run it after every Go or patch change: the APK only packages what it built.

A release build needs a keystore:

```bash
KEYSTORE_FILE="$PWD/birdsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```

It makes one APK per ABI and a universal one; `-PtargetAbi=arm64-v8a` builds
only that ABI. The whole release procedure: [`RELEASING.md`](RELEASING.md).

## Changing the daemon

Edit `appctr/netbird_src/`, then regenerate the patches:

```bash
bash appctr/patches/recreate_patches.sh
```

It diffs against the pristine `appctr/netbird_orig/` and names each patch's
files; add a `make_patch` line for a new one. Patches must apply with
`patch -p1 -F0`.

## Testing the daemon without a phone

The Go tests need only the patched tree, not the NDK; CI runs the packages
listed in `.github/workflows/ci.yml`:

```bash
bash appctr/build.sh --prepare
cd appctr/netbird_src && go test -ldflags=-checklinkname=0 \
    ./client/iface/netstack/... ./client/net/ ./client/birdsocksd/
```

A host build runs the same code (it is a Linux binary):

```bash
cd appctr/netbird_src && CGO_ENABLED=0 go build -ldflags=-checklinkname=0 \
    -o /tmp/nb/libnetbird.so ./client/birdsocksd && cd ..
BIRDSOCKS_LIVE_LIB=/tmp/nb go test -run TestDaemonLive -v .
```

With `BIRDSOCKS_LIVE_SETUP_KEY=<key>` the test also registers and connects.
