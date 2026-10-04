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
# NetBird names resolvable through it, the internet reached directly when no
# peer carries the destination (falling back over a name's other addresses),
# a DNS proxy beside it, both kept for the daemon's life and lent to
# whichever engine runs (front.go).
make_patch 01-socks5-birdsocks.patch \
    client/iface/netstack/env.go \
    client/iface/netstack/proxy.go \
    client/iface/netstack/tun.go \
    client/iface/netstack/dialer.go \
    client/iface/netstack/route.go \
    client/iface/netstack/route_test.go \
    client/iface/netstack/dnsproxy.go \
    client/iface/netstack/associate.go \
    client/iface/netstack/associate_test.go \
    client/iface/netstack/searchdomains.go \
    client/iface/netstack/searchdomains_test.go \
    client/iface/netstack/fallback.go \
    client/iface/netstack/fallback_test.go \
    client/iface/netstack/front.go \
    client/iface/netstack/front_test.go \
    client/iface/device/device_netstack.go

# The device as the dashboard shows it: Android, its version and model, which
# a Linux binary cannot find on its own (no os-release, no DMI).
make_patch 02-android-system-info.patch \
    client/system/info_linux.go

# Interfaces inside an Android app: from Android 11 an app may not bind a
# netlink route socket or ask it for links, so net.Interfaces fails and ICE
# has no candidates. A copy of wlynxg/anet does it the allowed way.
make_patch 03-android-interfaces.patch \
    client/internal/anet/interface_linux.go \
    client/internal/anet/netlink_linux.go \
    client/internal/anet/api_level_linux.go \
    client/internal/anet/anet_other.go \
    client/internal/anet/anet_linux_test.go \
    client/internal/stdnet/discover_pion.go \
    client/firewall/uspfilter/localip.go \
    client/system/network_addr.go

# Network events in the daemon: NetBird's mobile bindings report network
# switches to the client, the daemon had no way to receive them, and netstack
# mode runs no monitor. The app signals birdsocksd, which calls these.
make_patch 04-daemon-network-events.patch \
    client/server/server.go

# The engine's Android-side hooks: its interface prefixes listed through anet
# (patch 03's package), and its search domains handed to the proxies, which
# expand short names themselves — netstack mode has no system resolver.
make_patch 05-engine-android-hooks.patch \
    client/internal/engine.go

# Connecting fast and keeping the connections: no kernel-WireGuard probe in
# netstack mode (on Android it closed Signal's or the relay's socket from a
# GC finalizer), a connection attempt cut after 10 s, not 20, and a Signal
# stream left unanswered dropped with its transport after 6 s, not 40.
make_patch 06-connect-faster.patch \
    client/internal/connect.go \
    client/grpc/dialer.go \
    client/grpc/dialer_generic.go \
    shared/signal/client/grpc.go \
    shared/signal/client/registration_test.go
