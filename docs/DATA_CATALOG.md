# BirdSocks data catalog: what the NetBird v0.80.0 daemon gives, what the app parses and shows

**State:** app after commit `941a3af` and the per-profile / live-log-level fixes that follow it.

Sources: `appctr/netbird_src/client/proto/daemon.proto`, `client/server/*.go`, `client/internal/peer/status.go`;
app code under `app/src/main/java/io/github/bropines/birdsocks/` (paths below are relative to it).
Samples are the captures from the POCO, with identifiers replaced by placeholders.

**Wire format, as the app gets it** (`appctr/rpc.go`, `protojson` with `EmitUnpopulated: true`):
- Every field arrives, including zero values. The captured samples were made without that option, so they leave zero values out.
- int64/uint64 arrive quoted (`"51820"`, `"2"`). int32 arrives bare.
- A Timestamp is RFC 3339. An unset peer time is the zero time `0001-01-01T00:00:00Z`, which `parseRfc3339Millis` maps to null (`ui/UIComponents.kt:70`).
- A Duration is `"0.012345s"`. `bytes` fields arrive as base64. Enums arrive as their names.
- A stream the daemon ends itself reports `Netbird.STREAM_DONE` ("the daemon closed the stream").
- **In the sample all 16 peers are Idle (lazy connections).** The fields marked † (connected-only) were missing from it. Their shapes come from the proto and the daemon code.

Columns: **parsed** = declared in `models/NetbirdModels.kt`. **shown** = the code that renders it (file:line), or "no".

---

## 1. Status (`Status` / `SubscribeStatus`, request `{"getFullPeerStatus":true}`)

The app streams `SubscribeStatus` in `core/NetbirdService.kt:234-258` into `NetbirdState.status`, and feeds `fullStatus.events` into the EventLog (`:247`). The stream fires only on state changes: connected/connecting, management or signal flips, address changes, changes to the peer list.

Byte counters, handshakes, latency and STUN/TURN probe results move without a push. Only a polled full `Status` refreshes them, because each such call runs the throttled health probe and a WireGuard stats refresh (`server/probe_throttle.go`). Two screens poll every 3 s: `ui/PeersActivity.kt:62-67` and `ui/StatusDetailsActivity.kt:46-51`.

### StatusResponse
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| status | daemon connection state: Idle, Connecting, Connected, NeedsLogin, LoginFailed, SessionExpired | `"Connected"` | yes `NbStatus.status` (+ `state` enum) | status card `ui/MainActivity.kt:126-137, 382-409`; notification `core/NetbirdService.kt:462-470`; QS tile `core/ProxyTileService.kt:73-77`; enables Logout `ui/SettingsActivity.kt:168` |
| fullStatus | everything below; filled only with getFullPeerStatus | `{…}` | yes | see below |
| daemonVersion | running daemon's NetBird version | `"0.80.0"` | yes | `ui/SettingsActivity.kt:332-333`; Details `ui/StatusDetailsActivity.kt:116` |
| sessionExpiresAt | when the server signs this SSO peer out; null for setup-key peers or when expiry is off | `"2026-10-04T21:17:02.07Z"` | yes | SessionRow `ui/MainActivity.kt:218, 787`; 10-minute warning notification `core/NetbirdService.kt:426-436` |

### FullStatus
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| managementState | control-plane connection | see below | yes | Details screen; error also on main |
| signalState | signalling connection (needed to set up peer links) | see below | yes | Details `ui/StatusDetailsActivity.kt:70` |
| localPeerState | this device | see below | yes | see below |
| peers[] | every peer this one may reach, including lazy-idle ones | 16 entries | yes | Peers screen; counts `ui/MainActivity.kt:247, 400`, notification `core/NetbirdService.kt:468` |
| relays[] | STUN/TURN servers + the NetBird relay, with health | 2 entries | yes `relays` | Details `ui/StatusDetailsActivity.kt:74-83` |
| dnsServers[] (`dns_servers`) | nameserver groups pushed by management | 1 entry | yes `dnsServers` | Details `ui/StatusDetailsActivity.kt:86-98` |
| NumberOfForwardingRules | count of ingress port-forward rules this peer applies | `0` | yes `forwardingRules` | Details `ui/StatusDetailsActivity.kt:111` (only when > 0) |
| events[] | last ≤10 SystemEvents (ring buffer, `eventQueueSize = 10`) | 8 entries | yes `events` | seeds the EventLog `core/NetbirdService.kt:247` → Events screen (see SystemEvent) |
| lazyConnectionEnabled | the engine's effective lazy-connection mode | `true` | yes | Peers hint `ui/PeersActivity.kt:98-99`; Details `ui/StatusDetailsActivity.kt:109` |
| sshServerState | NetBird SSH server on this device; null while the engine is down (the model then falls back to its default) | `{}` | yes `sshServerState` | Details `ui/StatusDetailsActivity.kt:112-115` |
| networksRevision | bumps when routable networks or their selection change | `"2"` | yes | not displayed; triggers ListNetworks re-read `ui/MainActivity.kt:153-156`, `ui/NetworksActivity.kt:52, 67` |

### LocalPeerState
All shown on the Details screen, `ui/StatusDetailsActivity.kt:101-117`.

| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| IP | overlay IPv4 with prefix | `"10.x.y.z/16"` | yes `NbLocalPeer.ip` (`address` strips the prefix) | AddressCard `ui/MainActivity.kt:594`; notification `core/NetbirdService.kt:467`; Details `:104` (with prefix) |
| pubKey | this device's WireGuard key | `"<wg pubkey>"` | yes | Details `:106` |
| kernelInterface | kernel WG in use; always false here (netstack) | `false` | yes | no (kernel only) |
| fqdn | this device's NetBird DNS name | `"phone.example.corp"` | yes | AddressCard `ui/MainActivity.kt:593`; top-bar subtitle `ui/MainActivity.kt:179`; Details `:103` |
| rosenpassEnabled / rosenpassPermissive | the profile's Rosenpass settings, echoed | `false` | yes | Details `:110` |
| networks[] | routes this device serves as a routing peer | `[]` / `["192.168.1.0/24"]` | yes | Details `:108` ("none" when empty) |
| ipv6 | overlay IPv6 with prefix, if the account has v6 | `"fdxx:…/64"` | yes | `ui/MainActivity.kt:595`; Details `:105` |
| wgPort | WireGuard UDP listen port | `51820` | yes | Details `:107` |

### PeerState
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| IP | peer overlay IPv4 (no prefix) | `"10.x.y.z"` | yes `NbPeer.ip`/`address` | row `ui/PeersActivity.kt:140`, details `:192`; search `:82`; Trace picker `ui/TraceActivity.kt:146` |
| pubKey | peer's WG key; the stable id | `"<wg pubkey>"` | yes | details `ui/PeersActivity.kt:212`; list key `:116` |
| connStatus | Idle / Connecting / Connected | `"Idle"` | yes (`connected`, `connecting`) | status dot `ui/PeersActivity.kt:155-162`; details `:181-185`; filter chips `:74-81`; counts `ui/MainActivity.kt:400` |
| connStatusUpdate | when connStatus last changed (zero time if never) | RFC 3339 | yes | details "· 5 min ago" `ui/PeersActivity.kt:185` |
| relayed † | traffic goes through a relay, not P2P | `true` | yes | "relayed/direct" `ui/PeersActivity.kt:165-169` → row `:140`, details `:195` |
| localIceCandidateType / remoteIceCandidateType † | ICE candidate kinds: host, srflx, prflx, relay | `"srflx"` | yes | details `ui/PeersActivity.kt:197-200` |
| fqdn | peer DNS name | `"laptop.example.corp"` | yes (`shortName`) | row `ui/PeersActivity.kt:137`, details `:179, :191`; exit "via" `ui/MainActivity.kt:226→678, 286`; network "via" `ui/NetworksActivity.kt:115`; Trace `ui/TraceActivity.kt:64, 146` |
| localIceCandidateEndpoint † | this device's endpoint as used for that peer (NAT mapping) | `"203.0.113.7:51820"` | yes | **no** |
| remoteIceCandidateEndpoint † | peer's public endpoint | `"198.51.100.4:51820"` | yes | details `ui/PeersActivity.kt:201` |
| lastWireguardHandshake † | last WG handshake (zero time if none) | RFC 3339 | yes | details `ui/PeersActivity.kt:203-205` |
| bytesRx / bytesTx † | WG byte counters (int64 quoted) | `"1048576"` | yes | details `ui/PeersActivity.kt:206-208` |
| rosenpassEnabled | Rosenpass active with this peer | `false` | yes | details `ui/PeersActivity.kt:211` (only when true) |
| networks[] † | routes this peer currently serves to us: CIDR for prefix routes, the domain list string for domain routes | `["0.0.0.0/0","::/0"]`, `["*.corp.example"]` | yes (`isExitNode`, `NbNetwork.routedBy`) | details `ui/PeersActivity.kt:210`; globe icon `:147`; routing-peer lookup `ui/MainActivity.kt:303-305`, `ui/NetworksActivity.kt:100` |
| latency † | ICE round-trip time; `"0s"` when unknown (relay-only peers stay 0) | `"0.012345s"` | yes (`latencyMs`) | row `ui/PeersActivity.kt:140`, details `:202` |
| relayAddress † | relay server used for this peer | `"rels://relay.example.com:443"` | yes | details `ui/PeersActivity.kt:196` (only when relayed) |
| sshHostKey | peer's NetBird SSH host key (base64 of an OpenSSH line) | `"<base64 'ssh-ed25519 AAAA…'>"` | **no** | no |
| ipv6 | peer overlay IPv6 | `"fdxx:…"` | yes | details `ui/PeersActivity.kt:193`; search `:82` |

Peer details also link to an access check for that peer (`ui/PeersActivity.kt:213-220`, TracePacket).

### ManagementState / SignalState (same shape)
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| URL | server address | `"https://mgmt.example.com:443"` | yes `NbServerState.url` | Details `ui/StatusDetailsActivity.kt:69-70 → 134-136` |
| connected | the stream to that server is up | `true` | yes | Details: ✓/✗ icon `:134-136` |
| error | last connection or login error text | `""` / `"…pending approval"` | yes | Details `:135`. Management error also shown on main: LoginCard `ui/MainActivity.kt:215 → 468-469`; an "extra DNS labels" refusal opens DnsLabelsRefusedCard `:210-212, 636-660` |

### RelayState
STUN/TURN entries refresh only from the health probe, which runs on full Status calls.

| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| URI | STUN/TURN URL or the relay (`rels://`) | `"stun:mgmt.example.com:3478"`, `"rels://mgmt.example.com:443"` | yes `NbRelay.uri` | Details `ui/StatusDetailsActivity.kt:79` |
| available | last probe or connection succeeded (error empty) | `true` | yes | Details ✓/✗ `:78` |
| error | probe or connection error | `""` | yes | Details `:80` |
| transport | negotiated relay transport; only on the relay entry | `"ws"` / `"quic"` / `""` | yes | Details `:80` |

### NSGroupState
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| servers[] | upstream nameservers `ip:port` | `["192.0.2.53:53"]` | yes `NbNsGroup.servers` | Details `ui/StatusDetailsActivity.kt:91` |
| domains[] | match domains; empty means primary (all names) | `[]` / `["corp.example"]` | yes | Details `:93` ("all names" when empty) |
| enabled | group in use (false while deactivated, e.g. unreachable) | `true` | yes | Details ✓/✗ `:90` |
| error | why the group failed | `""` | yes | Details `:90, :94` |

### SystemEvent (`FullStatus.events`, `SubscribeEvents`)
- `GetEvents` returns **Unimplemented** in v0.80.0.
- The app builds an in-memory `EventLog` (`core/Features.kt:15-33`, newest 200, deduplicated by id). It is seeded from `FullStatus.events` (`core/NetbirdService.kt:247`) and fed by `SubscribeEvents` (`core/NetbirdService.kt:261-273`).
- Screen: `ui/EventsActivity.kt`, newest first, with a category filter at `:46-47, 63-66`.
- Notifications (`core/NetbirdService.kt:277-297`) go out for fresh WARNING/ERROR/CRITICAL events at most 2 minutes old, when "event notifications" is on (`ui/SettingsActivity.kt:314`, `ui/EventsActivity.kt:51-53`).

| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| id | uuid | `"<uuid>"` | yes `NbEvent.id` | not displayed; dedup and list key `ui/EventsActivity.kt:72` |
| severity | INFO / WARNING / ERROR / CRITICAL | `"WARNING"` | yes | icon and colour `ui/EventsActivity.kt:82-94`; notification filter `core/NetbirdService.kt:283` |
| category | NETWORK / DNS / AUTHENTICATION / CONNECTIVITY / SYSTEM | `"DNS"` | yes | overline `ui/EventsActivity.kt:96`; filter chips `:63-66` |
| message | technical text | `"Nameserver group unreachable"` | yes | headline when there is no userMessage `ui/EventsActivity.kt:97`; else on expand `:101`; notification fallback `core/NetbirdService.kt:289` |
| userMessage | human text. When non-empty, NetBird means it as a user notification | `"Unable to reach one or more DNS servers…"` | yes | headline `ui/EventsActivity.kt:97`; notification text `core/NetbirdService.kt:289-290` |
| timestamp | when it happened | RFC 3339 | yes | `ui/EventsActivity.kt:96, 123-130`; notification freshness `core/NetbirdService.kt:284-285` |
| metadata | key/value details (see below) | `{"upstreams":"192.0.2.53:53"}` | yes | on expand `ui/EventsActivity.kt:102-104` |

Events v0.80.0 can emit on this phone:

| message | severity/category | userMessage | metadata | notifies? |
|---|---|---|---|---|
| Default route added | INFO/NETWORK | "Exit node connected." | id, peer, network | no (INFO) |
| Default route disconnected due to peer unreachability | WARNING/NETWORK | "Exit node connection lost. Your internet access might be affected." | id, peer, network | yes |
| Default route removed / updated / HA change / unknown | INFO or ERROR/NETWORK | some | id, peer, network | only the ERROR one |
| Nameserver group unreachable / recovered | WARNING or INFO/DNS | yes | upstreams | unreachable only |
| session expiry warning / final warning | CRITICAL/AUTHENTICATION | – | session_warning, session_final_warning, session_expires_at, lead_minutes (10 / 2) | yes, alongside the app's own 10-minute warning |
| session deadline rejected | ERROR/AUTHENTICATION | – | session_deadline_rejected | yes |
| panic occurred | CRITICAL/SYSTEM | yes | – | yes |
| Network selection changed / deselection changed | INFO/SYSTEM | – | networks, append, all | no |
| Network map updated; daemon config changed (source=startup\|up_rpc) | INFO/SYSTEM | – | source, type=config_changed | no |
| Profile list changed; Log level changed | INFO/SYSTEM | – | kind=…, profile / level | no. "Log level changed" is replayed with a fresh id on every (re)subscribe |
| MDM policy applied; updater events | – | – | – | desktop only |

### SSHServerState / SSHSessionInfo
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| enabled | NetBird SSH server running on this device | `false` | yes `NbSshServer.enabled` | Details `ui/StatusDetailsActivity.kt:112` |
| sessions[] | active inbound SSH sessions | `[]` | yes | Details `:113-115`, one line each |
| └ username / jwtUsername | local user / IdP identity | `"u0_a123"` / `"user@example.com"` | yes | `:114` (jwtUsername, else username) |
| └ remoteAddress / command | client `ip:port` / command run | `"10.x.y.z:52344"` / `""` | yes | `:114` |
| └ portForwards[] | forwarded ports | `["…"]` | yes | **no** |

---

## 2. Config (`GetConfig`, per profile)

Read by `Netbird.config()` (`core/Netbird.kt:109`) in Settings, LoginCard and AccountSheet. Written by `Netbird.setConfig()` (`core/Netbird.kt:118`) from `ui/SettingsActivity.kt` (SetConfig → GetConfig → reconnect) and from `ui/MainActivity.kt:650`.

| field | meaning | example | parsed | shown | settable from app? |
|---|---|---|---|---|---|
| managementUrl | server this profile logs into | `"https://mgmt.example.com:443"` | yes | Server row `ui/SettingsActivity.kt:160`; LoginCard prefill `ui/MainActivity.kt:445`; account server/label `ui/AccountSheet.kt:39-40, 27, 89, 164` → top bar `ui/MainActivity.kt:173, 179` | via **Login** only (`ui/MainActivity.kt:536-545`) |
| configFile | always `""` in v0.80.0 (handler never fills it) | `""` | no | no | no |
| logFile | always `""` in v0.80.0 | `""` | no | no | no |
| preSharedKey | `"**********"` when set, else `""` | `"**********"` | yes | masked `ui/SettingsActivity.kt:232` | yes `optionalPreSharedKey` `:234` |
| adminURL | dashboard URL; the daemon defaults it to `https://app.netbird.io:443` for every profile | `"https://app.netbird.io:443"` | yes | not displayed. **Fixed:** `NbConfig.dashboardUrl` (`models/NetbirdModels.kt:213-218`) falls back to the management host for self-hosted servers; opened by the Server row `ui/SettingsActivity.kt:162` | no |
| interfaceName | WG interface name; meaningless in netstack | `"wt0"` | no | no | no (kernel) |
| wireguardPort | WG UDP listen port | `"51820"` | yes | no (the live port is on Details via `wgPort`) | no |
| mtu | tunnel MTU | `"1280"` | yes | `ui/SettingsActivity.kt:265` | yes `mtu` `:270` |
| disableAutoConnect | daemon stays down when it starts | `false` | yes | no | no |
| serverSSHAllowed | run the NetBird SSH server here | `false` | yes | no (the live state is on Details) | no |
| rosenpassEnabled / rosenpassPermissive | post-quantum PSK | `false` | yes | settings hidden on purpose (`ui/SettingsActivity.kt:229-231`); live value on Details | no |
| disable_notifications | desktop UI toasts off (default true) | `true` | no | no | no (desktop) |
| lazyConnectionEnabled | profile's lazy setting; the app overrides it with `NB_LAZY_CONN` | `false` | yes | no (the effective value is on Details `ui/StatusDetailsActivity.kt:109`) | no (the app sets the env var: `ui/SettingsActivity.kt:277-286`) |
| blockInbound | refuse connections that peers start to this device | `false` | yes | `ui/SettingsActivity.kt:238` | yes `:239` |
| networkMonitor | daemon restarts on network change (the app does this itself) | `false` | no | no | no |
| disable_dns | ignore NetBird DNS config | `false` | yes `disableDns` | `ui/SettingsActivity.kt:253` (inverted) | yes `:254` |
| disable_client_routes | ignore routes other peers offer | `false` | yes | `:256` | yes `:257` |
| disable_server_routes | don't act as a routing peer | `false` | yes | `:235` | yes `:236` |
| block_lan_access | block the LAN while an exit node is in use | `false` | yes | no | no |
| enableSSHRoot / enableSSHSFTP / enableSSHLocalPortForwarding / enableSSHRemotePortForwarding / disableSSHAuth / sshJWTCacheTTL | NetBird SSH options | `false` / `0` | no | no | no |
| disable_ipv6 | no overlay IPv6 | `false` | yes `disableIpv6` | `:259` | yes `:260` |
| remoteJobsAllowed | let management request debug bundles remotely | `false` | yes | `:262` | yes `:263` |
| mDMManagedFields[] | keys an MDM policy locks | `[]` | no | no | n/a (no MDM on Android) |

**Write-only fields.** SetConfig/Login accept these, but GetConfig never returns them:
- **dns_labels** (+ cleanDNSLabels): now set from `ui/SettingsActivity.kt:241-252` and cleared from `ui/MainActivity.kt:650`. The app shows its own copy (`GlobalSettings.getDnsLabels`).
- disable_firewall, natExternalIPs, customDNSAddress, extraIFaceBlacklist, dnsRouteInterval, enable_local_metrics, local_metrics_address.
- Login also takes hostname; the app sends the device name.

**App settings that override the daemon, not in GetConfig:**
- lazy connections (`NB_LAZY_CONN`)
- relay transport (`NB_RELAY_TRANSPORT=ws` unless QUIC is on)
- force relay (`NB_FORCE_RELAY`)
- **inbound access** (`NB_ENABLE_NETSTACK_LOCAL_FORWARDING` + `NB_BIRDSOCKS_NO_FORWARD_PORTS`; `ui/SettingsActivity.kt:292-294`, `core/NetbirdService.kt:221`)
- log level (a start flag, `:216`)
- SOCKS and DNS-proxy addresses (`core/NetbirdService.kt:209-230`)

---

## 3. Other read data

### Networks / routes (`ListNetworks` → `routes[]`)
Read by `Netbird.networks()`. Errors with "not connected" when the engine is down. Exit nodes are entries whose range is `0.0.0.0/0`. The Networks screen hides them (`ui/NetworksActivity.kt:55`); the main screen shows them.

| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| ID | network/route name from the dashboard; the selection key | `"office-lan"`, `"exit-node-01"` | yes `NbNetwork.id` | `ui/NetworksActivity.kt:103`; ExitNodeRow `ui/MainActivity.kt:675`; exit picker `:286` |
| range | CIDR; a v6 exit pair is merged as `"0.0.0.0/0, ::/0"`; a placeholder for domain routes | `"192.168.1.0/24"` | yes (`isExitNode`, `routedBy`) | `ui/NetworksActivity.kt:107` (when there are no domains) |
| selected | routed now | `true` | yes | switch `ui/NetworksActivity.kt:120`; "x of y" subtitle `:72-74`; current exit `ui/MainActivity.kt:160, 282` |
| domains[] | names of a domain route | `["*.corp.example"]` | yes (`routedBy` matches them, fixed) | `ui/NetworksActivity.kt:107`, icon `:102`, search `:89` |
| resolvedIPs | resolved domain → IPs currently routed | `{"app.corp.example":{"ips":["10.1.2.3"]}}` | yes | `ui/NetworksActivity.kt:111-114`, flattened (the domain keys are lost) |

### Profiles (`ListProfiles` → `profiles[]`, `GetActiveProfile`)
| field | meaning | example | parsed | shown |
|---|---|---|---|---|
| Profile.name | display name | `"default"`, `"work"` | yes `NbProfile.name` | `ui/AccountSheet.kt:85, 162`. The default profile shows its server host instead (`:27`) |
| Profile.is_active | active profile | `true` | yes `isActive` | fallback only `ui/AccountSheet.kt:79` |
| Profile.id | on-disk id / handle | `"default"` / random | yes | no |
| GetActiveProfile.profileName | active profile name | `"default"` | yes `NbActiveProfile` | drives `NetbirdState.profile` → the checked account `ui/AccountSheet.kt:79, 159`, top bar `ui/MainActivity.kt:173` |
| GetActiveProfile.username / id | OS user / id | `""` / `"default"` | id parsed, username no | no |

- The server host per account is derived from `GetConfig(profile).managementUrl` (`ui/AccountSheet.kt:39-40`).
- **Signed-in email:** WaitSSOLogin's result is stored per profile name (`core/Netbird.kt:327-331` → `GlobalSettings.setAccountEmail`). It is shown in the switcher sheet only, `ui/AccountSheet.kt:86-90`; the Settings accounts card does not show it.

### Features (`GetFeatures`): not called, not parsed
Fields: `disable_profiles`, `disable_update_settings`, `disable_networks`, `disable_advanced_view` (tristate, MDM only). birdsocksd builds the server with `server.New(…, profilesDisabled=false, updateSettingsDisabled=false, captureEnabled=true, networksDisabled=false)` (`appctr/birdsocksd/main.go:119`), and Android has no MDM. All of them are always false or unset, so there is nothing to show.

### Forwarding rules (`ForwardingRules` → `rules[]`): not called, not parsed
These are ingress port-forward rules that management pushes to **this** peer, which then forwards incoming ports to another address. Only the count (`FullStatus.NumberOfForwardingRules`) reaches the UI, on the Details screen. Sample: `{}`.

| field | meaning | example |
|---|---|---|
| protocol | `tcp` / `udp` | `"tcp"` |
| destinationPort | `{port:N}` or `{range:{start,end}}` | `{"port":8080}` |
| translatedAddress | where traffic goes | `"10.x.y.z"` |
| translatedHostname | that address's peer FQDN, else the IP | `"srv.example.corp"` |
| translatedPort | port or range on the target | `{"port":80}` |

### Log level (`GetLogLevel` → `level`)
- An enum name: UNKNOWN, PANIC, FATAL, ERROR, WARN, **INFO** (sample), DEBUG, TRACE.
- The app passes the level at daemon start and changes the running daemon's at once with `SetLogLevel` (`Netbird.setLogLevel`, from Settings); `GetLogLevel` is not called.
- The level does reach the Events screen through `SubscribeEvents`' "Log level changed" event (metadata `level`).

### Other responses
| message | fields | app |
|---|---|---|
| LoginResponse | needsSSOLogin, userCode, verificationURI(Complete) | parsed; userCode shown `ui/MainActivity.kt:473-481`; URL opened `:457-460` |
| WaitSSOLoginResponse | email | parsed; stored per profile `core/Netbird.kt:327-331`; shown `ui/AccountSheet.kt:87-89` |
| RequestExtendAuthSessionResponse | verificationURI(Complete), userCode, deviceCode, expiresIn | parsed; URL opened `ui/MainActivity.kt:773-775`; userCode/expiresIn not shown |
| WaitExtendAuthSessionResponse | sessionExpiresAt | parsed, ignored (the stream brings it) |
| DebugBundleResponse | path, uploadedKey, uploadFailureReason | all used `ui/LogsActivity.kt:699-706` |
| TracePacketResponse | stages[]{name, message, allowed, forwarding_details}, final_disposition | parsed `NbTrace`/`NbTraceStage`; verdict `ui/TraceActivity.kt:122-127`, stages `:132-137` |
| CapturePacket (stream) | data (pcap bytes; the first message is the pcap header) | parsed `NbCapturePacket`; written to a file and saved through SAF, `ui/LogsActivity.kt:739-808` (entry button `:478`, byte counter `:800`) |
| ExposeServiceEvent.ready | service_name, service_url, domain, port_auto_assigned | parsed `NbExposeReady`. serviceUrl: `ui/ExposeActivity.kt:160` and notification `core/NetbirdService.kt:311`. serviceName: `ui/ExposeActivity.kt:161`. domain/portAutoAssigned not shown |
| ListStatesResponse | states[].name | not called; sample `["routeselector_state"]` |
| GetPeerSSHHostKeyResponse | sshHostKey, peerIP, peerFQDN, found | not called |
| Add/Rename/Remove/SwitchProfile | id / oldProfileName | ignored |
| CleanState/DeleteState, TriggerUpdate, InstallerResult, RequestJWTAuth, WaitJWTToken | counts; success+errorMsg; JWT flow | not called |

**Request options the app doesn't send:**
- **TracePacket:** sends source/destination IP, protocol, a fixed source port of 40000, destination port and direction (`ui/TraceActivity.kt:66-76`). It does not send `tcp_flags` or `icmp_type/code`.
- **ExposeService:** sends port, protocol, name prefix, pin, password and user groups (`ui/ExposeActivity.kt:138-139`). It does not send `domain` or `listen_port`.
- **StartCapture:** sends duration, `snapLen` 0 and the filter (`core/Netbird.kt:239-247`). It does not send `text_output`, `verbose` or `ascii`.

---

## 4. RPCs

The bridge (`appctr/rpc.go`) reaches every unary method by name and every stream through `Subscribe`.

| rpc | used? | Kotlin | what it is for |
|---|---|---|---|
| Login | yes | `Netbird.login` | register with a setup key, or start SSO |
| WaitSSOLogin | yes | `Netbird.waitSso` (email kept) | block until the browser login finishes |
| Up | yes | `Netbird.up` (async) | connect the engine |
| Status | yes | `Netbird.status` (Peers and Details polls) | snapshot + probes + WG stats refresh |
| SubscribeStatus | yes | `NetbirdService.followStatus` | status push stream |
| Down | yes | `Netbird.down` | disconnect |
| GetConfig | yes | `Netbird.config` | profile settings |
| ListNetworks | yes | `Netbird.networks` | routes / exit nodes |
| SelectNetworks / DeselectNetworks | yes | `Netbird.selectNetworks` / `deselectNetworks` | toggle routes / pick exit |
| ForwardingRules | no | – | ingress port-forward rules on this peer |
| DebugBundle | yes | `Netbird.debugBundle` | support archive / upload |
| SetLogLevel | yes | `Netbird.setLogLevel` | change the daemon's log level live |
| GetLogLevel | no | – | read the daemon's log level |
| ListStates / CleanState / DeleteState | no | – | persisted system-change state (routes, DNS); `routeselector_state` |
| SetSyncResponsePersistence | no | – | keep the network map for the debug bundle |
| TracePacket | **yes** | `Netbird.trace` (TraceActivity) | simulate a packet through the ACL filter |
| StartCapture (stream) | **yes** | `Netbird.capture` (Logs → CaptureDialog) | live pcap of overlay traffic |
| StartBundleCapture / StopBundleCapture | no | – | capture into the next debug bundle |
| SubscribeEvents (stream) | **yes** | `NetbirdService.followEvents` → `EventLog` | SystemEvent push |
| GetEvents | no | – | **Unimplemented** in v0.80.0 |
| RegisterUILog | no | – | desktop UI log path for bundles |
| SwitchProfile | yes | `Netbird.switchProfile` | change the active profile |
| SetConfig | yes | `Netbird.setConfig` | change profile settings (incl. dnsLabels) |
| AddProfile / RenameProfile / RemoveProfile | yes | `addProfile` / `renameProfile` / `removeProfile` | manage accounts |
| ListProfiles / GetActiveProfile | yes | `profiles` / `activeProfile` | list accounts / active one |
| Logout | yes | `Netbird.logout` | deregister the peer |
| GetFeatures | no | – | MDM / install-flag feature gates |
| TriggerUpdate / GetInstallerResult | no | – | desktop auto-update |
| GetPeerSSHHostKey | no | – | peer SSH host key for known_hosts |
| RequestJWTAuth / WaitJWTToken | no | – | JWT for `netbird ssh` |
| RequestExtendAuthSession / WaitExtendAuthSession | yes | `requestExtend` / `waitExtend` | renew the SSO session without a drop |
| DismissSessionWarning | no | – | silence the desktop's final-warning dialog |
| StartCPUProfile / StopCPUProfile | no | – | daemon profiling |
| ExposeService (stream) | **yes** | `Netbird.expose` via `ExposeFlow` (ExposeActivity, notification) | publish a local port through the NetBird reverse proxy |
| WailsUIReady | no | – | desktop UI version probe |

25 of 46 are used (4 more since the first catalog: TracePacket, StartCapture, SubscribeEvents, ExposeService).

---

## 5. Gaps: what is still missing, most useful first

Already covered by 941a3af and dropped from this list:
- connection health (management, signal, relays + transport)
- DNS groups and system events (screen + notifications)
- signed-in email
- local key, port and served routes
- forwarding-rule count and SSH sessions
- trace, capture and publish
- the self-hosted dashboard link and domain-route "via"
- after 941a3af: log level changed live (`SetLogLevel`), the event log kept in `files/events.json` with log-level replays dropped, DNS labels and the email kept per profile (moved on rename, cleared on remove), any management port dropped from the dashboard link

### Still missing
1. **Health is not visible at a glance.** Signal, management or relay failures, and disabled DNS groups, show only on the Details screen. The main card stays "Connected", and an event notification fires only for exit-node and DNS warnings. A small warning chip on the main card would catch "connected but nothing reaches peers".
2. **Settings doesn't say what the server chose for lazy connections.** The effective value is on Details, but the "server default" chip doesn't say whether that means on or off.
3. **Parsed but not shown, cheap to add:**
   - `peer.localIceCandidateEndpoint`: this phone's NAT mapping, the other half of the P2P path.
   - `resolvedIPs` per domain: currently flattened.
   - `ExposeReady.domain`.
   - `SSHSessionInfo.portForwards`.
   - The signed-in email in the Settings accounts card.
   - `RequestExtendAuthSession.userCode/expiresIn`: needed only on a device-code fallback.
4. **Config flags parsed but not offered:**
   - `disableAutoConnect`: start the daemon but stay disconnected.
   - `blockLanAccess`: check what it does in netstack before exposing it.
   - `serverSSHAllowed` (+ SSH options): only if a NetBird SSH server on the phone is ever wanted.
   - `wireguardPort`.
5. **Debug-bundle extras:**
   - `StartBundleCapture`/`StopBundleCapture`: a pcap inside the bundle.
   - `SetSyncResponsePersistence`: the network map in the bundle.
   - DebugBundle `anonymizeLevel: "strict"`.

   These help support without the user handling pcap files.
6. **Publish options:** ExposeService `domain` (a custom domain) and `listen_port` (a fixed public port for TCP/UDP) are not offered.
7. **Forwarding rules in detail:** only the count is shown. The rules themselves (`ForwardingRules`) matter only if the phone is an ingress peer.
8. **Peer SSH host keys:** `peer.sshHostKey` / `GetPeerSSHHostKey` could give known_hosts lines for Termux ssh. Niche.
9. **Saved route selection:** `ListStates`/`DeleteState` could reset `routeselector_state`. Niche.
10. **Trace options:** TracePacket's TCP flags and ICMP type/code are not offered (minor; SYN by default is what matters).


### Desktop or kernel only; skip
- `kernelInterface` (parsed, always false), `interfaceName`, `networkMonitor`, `disable_notifications`
- `configFile` / `logFile` (always empty)
- `mDMManagedFields`, `GetFeatures`, `RegisterUILog`, `WailsUIReady`, `TriggerUpdate`, `GetInstallerResult`
- `DismissSessionWarning`, `RequestJWTAuth` / `WaitJWTToken` (`netbird ssh`), CPU profiling
- `CleanState` (kernel route/DNS residue)
- Rosenpass settings (no effect in netstack, `ui/SettingsActivity.kt:229-231`)
