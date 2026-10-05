#!/bin/bash
# Builds BirdSocks's native core:
#   - libnetbird.so: the NetBird daemon (birdsocksd/main.go, built inside the
#     pinned and patched NetBird tree), one per ABI, run by the app as its
#     own process — app/src/main/jniLibs/<abi>/libnetbird.so;
#   - appctr.aar: the gomobile bridge the app calls (appctr/*.go).
#
#   --clean    download and patch NetBird again even when the tree is current
#   --prepare  only unpack, patch and assemble netbird_src, then stop: what
#              `go test` in the tree needs, without the NDK
# NETBIRD_DIST_DIR, when set, keeps the downloaded release archive there and
# reuses it (CI caches the directory); it is checked against NETBIRD_SHA256
# every time all the same.
set -e
cd "$(dirname "$0")"

CLEAN= PREPARE=
for arg in "$@"; do
    case $arg in
        --clean) CLEAN=1 ;;
        --prepare) PREPARE=1 ;;
        *) echo "❌ Unknown option: $arg" >&2; exit 1 ;;
    esac
done

if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$PREPARE" ]; then
    echo "❌ ANDROID_NDK_HOME is not set." >&2
    exit 1
fi

NB_VERSION=$(tr -d ' \n\r' < NETBIRD_VERSION)
echo "-> NetBird ${NB_VERSION}"

# A tree patched by an older patch set is not what the patches describe:
# stamp the version, the patches and our daemon entry point, and re-extract
# when any of them moves.
STAMP=$(cat NETBIRD_VERSION patches/*.patch 2>/dev/null | sha256sum | cut -d" " -f1)
if [ -n "$CLEAN" ] || [ "$(cat netbird_src/.patch_stamp 2>/dev/null)" != "$STAMP" ]; then
    rm -rf netbird_src netbird_orig
fi

if [ ! -d netbird_src ]; then
    # Checked against NETBIRD_SHA256 before anything in it is used. A new
    # version has no line yet: verify the printed hash and add it.
    if [ -n "$NETBIRD_DIST_DIR" ]; then mkdir -p "$NETBIRD_DIST_DIR"; fi
    TARBALL="${NETBIRD_DIST_DIR:-.}/netbird-${NB_VERSION}.tar.gz"
    if [ -z "$NETBIRD_DIST_DIR" ] || [ ! -s "$TARBALL" ]; then
        curl -fsSL -o "$TARBALL" "https://github.com/netbirdio/netbird/archive/refs/tags/${NB_VERSION}.tar.gz"
    fi
    ACTUAL=$(sha256sum "$TARBALL" | cut -d" " -f1)
    EXPECTED=$(awk -v v="$NB_VERSION" '!/^#/ && $2 == v { print $1 }' NETBIRD_SHA256)
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        rm -f "$TARBALL"
        echo "❌ ${NB_VERSION} archive: expected '${EXPECTED:-no line in NETBIRD_SHA256}', got $ACTUAL" >&2
        exit 1
    fi
    mkdir -p .extract && tar -xzf "$TARBALL" -C .extract
    if [ -z "$NETBIRD_DIST_DIR" ]; then rm -f "$TARBALL"; fi
    mv ".extract/netbird-${NB_VERSION#v}" netbird_orig && rmdir .extract
    cp -r netbird_orig netbird_src
    for p in patches/*.patch; do
        [ -f "$p" ] || continue
        # An empty patch is a lost fix, not a no-op.
        [ -s "$p" ] || { echo "❌ $(basename "$p") is empty" >&2; exit 1; }
        echo "Applying patch: $(basename "$p")"
        # -F0: a release that shifted the context fails here instead of
        # landing a hunk in the wrong place.
        patch -p1 --batch --forward -F0 -d netbird_src < "$p"
    done
    echo "$STAMP" > netbird_src/.patch_stamp
fi

# Our entry point builds inside the NetBird module, so it uses NetBird's
# go.mod and may import the client's internal packages.
# Only the Go files: birdsocksd/go.mod exists to keep the directory out of the
# appctr module, and inside NetBird's tree it would wall it off from client/internal.
rm -rf netbird_src/client/birdsocksd && mkdir -p netbird_src/client/birdsocksd
cp birdsocksd/*.go netbird_src/client/birdsocksd/

if [ -n "$PREPARE" ]; then
    echo "✅ netbird_src is ready."
    exit 0
fi

export GOTOOLCHAIN=${GOTOOLCHAIN:-auto}
# Reproducible: no VCS stamp, no build paths, no build ID.
export GOFLAGS=-buildvcs=false

TS_ABIS=${TS_ABIS:-"armeabi-v7a arm64-v8a x86 x86_64"}
abi_goarch() { case $1 in armeabi-v7a) echo arm;; arm64-v8a) echo arm64;; x86) echo 386;; x86_64) echo amd64;; *) return 1;; esac; }

NB_LDFLAGS="-s -w -buildid= -checklinkname=0 -X github.com/netbirdio/netbird/version.version=${NB_VERSION#v}"
GOMOBILE_TARGETS=""
mkdir -p netbird_src/tmp
for ABI in $TS_ABIS; do
    GOARCH_ABI=$(abi_goarch "$ABI") || { echo "Unknown ABI: $ABI" >&2; exit 1; }
    GOMOBILE_TARGETS="${GOMOBILE_TARGETS:+$GOMOBILE_TARGETS,}android/$GOARCH_ABI"
    if [ "$GOARCH_ABI" = arm ]; then export GOARM=7; else unset GOARM; fi
    echo "-> Compiling birdsocksd [$ABI]..."
    # A static Linux binary, no cgo: NetBird's Linux netstack mode is the
    # unprivileged one, its android paths need its own app (birdsocksd/resolver.go).
    (cd netbird_src && CGO_ENABLED=0 GOOS=linux GOARCH=$GOARCH_ABI go build -trimpath \
        -ldflags="$NB_LDFLAGS" -o "tmp/libnetbird_${ABI}.so" ./client/birdsocksd)
done
unset GOARM

echo "-> Building appctr.aar (gomobile bridge)..."
GIT_HASH=$(git rev-parse --short=7 HEAD 2>/dev/null || echo dev)
mkdir -p tmp
go mod tidy
gomobile bind -ldflags="-s -w -buildid= -checklinkname=0 -X appctr.coreVersion=${NB_VERSION}-${GIT_HASH}" \
    -trimpath -target="$GOMOBILE_TARGETS" -androidapi 21 -o tmp/appctr.aar -v .

for ABI in $TS_ABIS; do
    mkdir -p "../app/src/main/jniLibs/$ABI"
    rm -f "../app/src/main/jniLibs/$ABI/libtailscale.so" "../app/src/main/jniLibs/$ABI/libtailscale_cli.so"
    cp "netbird_src/tmp/libnetbird_${ABI}.so" "../app/src/main/jniLibs/$ABI/libnetbird.so"
done
echo "✅ Done."
