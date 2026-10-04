# Automation

*Русская версия: [AUTOMATION_RU.md](AUTOMATION_RU.md)*

Turn on **Settings → Automation** and tap **Generate**. Until then, and without the token, every request is refused.

## Broadcasts: Tasker, MacroDroid, adb

- **Receiver:** `io.github.bropines.birdsocks/io.github.bropines.birdsocks.core.AutomationReceiver`. The debug build's package is `io.github.bropines.birdsocks.dev`; the class and the actions stay the same.
- **Token:** every intent carries it in the string extra `secret` (or `token`, `key`).
- **Background:** on Android 12+ a broadcast can start BirdSocks only if battery optimization is off for it.

| Action `io.github.bropines.birdsocks.action.…` | Extras | What it does |
|---|---|---|
| `CONNECT` | — | Starts BirdSocks and connects |
| `DISCONNECT` | — | Stops it, like the Stop button |
| `TOGGLE` | — | Starts or stops it |
| `RESTART` | — | Restarts the daemon |
| `GET_STATUS` | `reply_to`: a package (optional) | Sends `STATUS_CHANGED` to that app now and after every change |
| `SET_EXIT_NODE` | `peer`: the exit node's name, or its peer's name, FQDN or NetBird IP; `none` | Selects the exit node, waiting up to 20 s for the connection |
| `SWITCH_ACCOUNT` | `name`: the account (profile) | Switches the account, starting BirdSocks if needed |
| `SET_TUN` | `on`: `true` / `false` | Turns VPN mode on or off |
| `SET_SERVER_CONNECTION` | `mode`: `direct` / `proxy` / `byedpi`; `flags` (ByeDPI, optional) | Saves the server connection and restarts the daemon |

A peer name works for an exit node that is in use, or one whose name contains the peer's name.

**STATUS_CHANGED** (`io.github.bropines.birdsocks.action.STATUS_CHANGED`) goes only to the app named in `reply_to`, never to every app. Turning automation off or changing the token forgets that app.

Extras:
- `running`, `tun`: booleans;
- `state`: `STOPPED`, `STARTING`, `CONNECTING`, `CONNECTED`, `NEEDS_LOGIN`, `IDLE`, `OFFLINE` or `STOPPING`;
- `profile`, `exit_node`, `ip`: strings.

`adb shell am broadcast` gets the result back directly: `result=-1` means done, `1` means not done.

## Links

| Link | What it does |
|---|---|
| `birdsocks://connect`, `disconnect`, `toggle` | The same as the broadcasts |
| `birdsocks://exit-node?peer=<name\|none>` | Selects the exit node |
| `birdsocks://account?name=<account>` | Switches the account |
| `birdsocks://tun?on=true\|false` | Turns VPN mode on or off |
| `birdsocks://add-account?server=<url>&name=<name>&key=<setup key>` | Opens **Add account**, filled in. Nothing is added until you tap. Without `server`, it uses NetBird Cloud |

- **Confirmation:** any app or web page can open a link, so BirdSocks asks first.
- **Skipping it:** with `&secret=<token>`, and automation on, the link runs at once. Use this only in your own shortcuts: a URL can end up in browser history.
- **Screen links:** `birdsocks://peers`, `networks`, `dns`, `publish`, `diagnostics`, `events`, `permissions`, `settings?section=<id>`, `logs?category=NETBIRD`.

## Examples

```bash
R=io.github.bropines.birdsocks/io.github.bropines.birdsocks.core.AutomationReceiver
A=io.github.bropines.birdsocks.action
adb shell am broadcast -n $R -a $A.CONNECT --es secret TOKEN
adb shell am broadcast -n $R -a $A.SET_EXIT_NODE --es secret TOKEN --es peer laptop
adb shell am broadcast -n $R -a $A.SET_TUN --es secret TOKEN --ez on true
adb shell am broadcast -n $R -a $A.GET_STATUS --es secret TOKEN --es reply_to net.dinglisch.android.taskerm
adb shell am start -a android.intent.action.VIEW -d 'birdsocks://exit-node?peer=none'
```

**Tasker**
- **Sending:** Send Intent, with the action, the package, the class above, Extra `secret:TOKEN` and Target *Broadcast Receiver*.
- **Receiving:** the *Intent Received* event on `…action.STATUS_CHANGED`. The extras arrive as `%state`, `%profile`, and so on.
