#!/bin/bash
# Regenerates patches/*.patch from the edits in netbird_src against the
# pristine netbird_orig (both are extracted by build.sh from the pinned
# NetBird release; netbird_orig is kept only for this). Each patch lists its
# files; a file NetBird does not have is created by the patch.
#
# Headers carry fixed labels, no timestamps, so regenerating an unchanged
# patch changes nothing.
set -e
cd "$(dirname "$0")/.."
[ -d netbird_orig ] && [ -d netbird_src ] || { echo "netbird_orig and netbird_src are needed: run build.sh first" >&2; exit 1; }
find netbird_src \( -name "*.orig" -o -name "*.rej" \) -delete 2>/dev/null || true

make_patch() {
    local out="patches/$1"; shift
    : > "$out"
    for f in "$@"; do
        diff -uN --label "netbird_orig/$f" --label "netbird_src/$f" "netbird_orig/$f" "netbird_src/$f" >> "$out" || true
    done
    if [ ! -s "$out" ]; then
        echo "❌ $out came out empty: its files are unchanged" >&2
        exit 1
    fi
    echo "-> $out"
}

# The SOCKS5 proxy of netstack mode, made fit for an Android app: a password,
# NetBird names resolvable through it, and the internet reached directly when
# no peer carries the destination.
make_patch 01-socks5-birdsocks.patch \
    client/iface/netstack/env.go \
    client/iface/netstack/proxy.go \
    client/iface/netstack/tun.go \
    client/iface/netstack/dialer.go \
    client/iface/netstack/route.go \
    client/iface/netstack/route_test.go \
    client/iface/device/device_netstack.go

# The device as the dashboard shows it: Android, its version and model, which
# a Linux binary cannot find on its own (no os-release, no DMI).
make_patch 02-android-system-info.patch \
    client/system/info_linux.go
