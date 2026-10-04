# BirdSocks user guide

*Русская версия: [GUIDE_RU.md](GUIDE_RU.md)*

## First start

1. Allow notifications. BirdSocks runs as a foreground service with a notification, and NetBird's warnings arrive as notifications too.
2. Exempt it from battery optimization in Settings → Background & permissions. HyperOS and MIUI also need autostart allowed.
3. Sign in. Choose **NetBird Cloud** or **Own server** (paste your server or dashboard address), then sign in through the browser or tick **Use a setup key**.

**Several accounts:** tap the title on the main screen, then **Add account**. A new account takes its server and setup key at once, and its name is taken from the server's domain. Each account keeps its own keys and settings. Switching reconnects, and the account you leave stays signed in.

## The SOCKS5 proxy

- The address is `127.0.0.1:48125` by default (Settings → Local proxies). Any `127.x.x.x` address works, and the dice picks a random one, which a port scanner will not guess.
- **Username and password:** set both or neither. One alone is ignored.
- **Share on the local network** opens the proxy to other devices on your Wi-Fi. Set a password first.
- Use remote DNS (`socks5h`, "resolve through proxy"), so that NetBird names such as `server.netbird.cloud` resolve.
- **What goes where:**
  - peers and the networks they route go through NetBird;
  - everything else goes straight to the internet, or through the exit node if one is selected;
  - UDP works (UDP ASSOCIATE).

## Behind other apps: Throne, AdGuard

Most apps have no proxy setting. A proxy client can carry their traffic to BirdSocks.

**Throne / NekoBox / sing-box clients**
1. Add a SOCKS5 profile with BirdSocks' address, port, username and password. Name it, for example `netbird`.
2. Add route rules that send your NetBird domains (`netbird.cloud` or your own) and NetBird's ranges (`100.64.0.0/10`, or your own range such as `10.90.0.0/16`) to that profile.
3. Put these rules **above** `ip_is_private → direct` and above rules for other mesh networks. Rules are first-match, and a private range such as 10.x would otherwise go direct.
4. Throne resolves a name with its own DNS before it routes. For NetBird names, add `udp://127.0.0.1:48153` (the BirdSocks DNS proxy) as a DNS server for your NetBird domains, or turn off resolving before routing.

**AdGuard (VPN mode)**
- Set the BirdSocks DNS proxy as AdGuard's DNS server (`127.0.0.1:48153`), or as the upstream for your NetBird domains only: `[/netbird.cloud/]127.0.0.1:48153`.
- AdGuard keeps `10.0.0.0/8` out of its VPN by default (Low-level settings → *IPv4 ranges excluded from filtering*). If your NetBird range is inside it, replace that line with `10/8` minus your range. For `10.90.0.0/16`:
  ```
  10.0.0.0/10
  10.64.0.0/12
  10.80.0.0/13
  10.88.0.0/15
  10.91.0.0/16
  10.92.0.0/14
  10.96.0.0/11
  10.128.0.0/9
  ```
- The chain is then: app → AdGuard → its outbound proxy (Throne or BirdSocks) → BirdSocks → NetBird.

## The DNS proxy

- `127.0.0.1:48153`, UDP, in Settings → DNS.
- **Answers:**
  - NetBird names come first;
  - everything else goes to the network's resolvers, or to the fallbacks you set (for example another DNS proxy on the phone), all asked at once;
  - a NetBird name never goes to a public resolver.
- **The DNS screen** tests the proxy, looks names up and shows NetBird's nameservers. Under **Records** it lists every name the server gave this device: the peers' zone and the zones an admin made. Tap a name to copy it.

## VPN mode

For apps that can use neither a proxy nor a proxy client. Turn it on in Settings → Tunnel mode → Mode: VPN (TUN). Android asks for the VPN permission the first time.

- **It takes Android's VPN slot,** so another VPN such as AdGuard stops while it runs.
- **What goes in:** by default only NetBird's ranges. Everything goes in with **Route all traffic**, or by itself when an exit node or a domain route is selected.
- **DNS:** the VPN announces one DNS server, `198.18.0.2`. BirdSocks answers it itself, NetBird names first.
- **Excluded apps** stay outside the tunnel. A few Russian apps that refuse to work behind a VPN (banks, VK, MAX) are excluded by default.

## Exit node and networks

Pick an exit node on the main screen or in Networks. BirdSocks then checks the internet through it. If nothing comes back, the row says so and offers to turn the node off: peers still work, but apps behind the proxy would hang.

## Server connection: proxy or ByeDPI

Use this when your network blocks the NetBird server or filters its TLS handshake. It is in Settings → Connection → **Server connection**.

- **Proxy:** your SOCKS5 or HTTP proxy, with a username and password if it asks for them.
- **ByeDPI:**
  - BirdSocks starts ByeDPI on a random loopback address and sends the server connection through it;
  - the default flags are `-o1 -a1 -r-5+se`, and options that would bind, fork or touch files are dropped;
  - **IPv4 only** helps where the server's IPv6 address fails.
- **What goes through it:** management, signal, the relay (WebSocket only) and sign-in requests. Traffic between peers is UDP and keeps its own way.
- If the setting is wrong, the connection fails rather than going direct. BirdSocks restarts NetBird to apply a change.

## Access to the phone, publishing

- **Access to the phone** (Settings → Access to this phone): peers reach the phone's own services on its NetBird address, as your ACLs allow — adb over Wi-Fi, Termux's `sshd`, a web server. BirdSocks' own proxies stay closed to peers.
- **Publish:** a local port gets a public HTTPS address through NetBird's reverse proxy, protected by a PIN, a password or SSO groups. The server must have the reverse proxy and "peer expose" turned on.

## Diagnostics

- **Diagnostics → Connection:**
  - management, signal and relays, each with its transport and error;
  - NetBird's DNS servers;
  - this device's addresses and session.
- **Diagnostics → Events:** NetBird's events, from network, DNS, sign-in and connections. Warnings are also notifications.
- **Access check:** what NetBird's firewall does with a packet to or from a peer, and which rule decides.
- **Logs:** filters, packet capture to `.pcap` (opens in Wireshark), and the debug bundle (anonymized, saved or uploaded to NetBird).

## Links

`birdsocks://` opens a screen from Tasker, a shortcut or a browser:

```
birdsocks://peers
birdsocks://networks
birdsocks://dns
birdsocks://publish
birdsocks://diagnostics
birdsocks://events
birdsocks://access-check?peer=<name>
birdsocks://logs?category=<category>
birdsocks://settings?section=<id>
birdsocks://permissions
```

The settings sections are `appearance`, `account`, `tunnel`, `proxies`, `dns`, `access`, `connection`, `background`, `diagnostics` and `about`.

## When something does not work

| Symptom | Where to look |
|---|---|
| Stuck on "Connecting" | Diagnostics → Connection: is it management or signal that fails? If the server is blocked, use Server connection (proxy or ByeDPI). |
| A NetBird name does not resolve in an app | The app must resolve through the proxy (`socks5h`), or ask the DNS proxy. Behind Throne or AdGuard, see the section on them above. |
| A peer does not answer | Its row in Peers shows P2P or relay and when it was last seen. **Access check** shows whether an ACL drops the packet. |
| Everything works but the internet through the exit node | The exit node row says it forwards nothing. Pick another node or tell the network admin. |
| A peer address in `10.x` fails behind AdGuard | AdGuard excludes `10.0.0.0/8` from its VPN. See the section on AdGuard above. |
| BirdSocks stops in the background | Settings → Background & permissions: battery optimization off, autostart on (HyperOS/MIUI). |
