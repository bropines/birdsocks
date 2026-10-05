# Лицензии

*English version: [LICENSES.md](LICENSES.md)*

BirdSocks распространяется по [лицензии BSD 3-Clause](../LICENSE),
Copyright (c) 2026, Bropines. Компоненты, на которых он построен и которые
входят в APK, остаются под своими лицензиями:

| Компонент | Зачем в BirdSocks | Лицензия |
|---|---|---|
| [Клиент NetBird](https://github.com/netbirdio/netbird) (`client/`, `shared/`, `util/` …) | демон, с патчами из `appctr/patches/` | BSD-3-Clause |
| [wireguard-go](https://github.com/netbirdio/wireguard-go) (форк NetBird) | WireGuard в пространстве пользователя | MIT |
| [gVisor](https://github.com/google/gvisor) netstack | стек TCP/IP демона | Apache-2.0 |
| [Pion](https://github.com/pion) ICE (форк NetBird), STUN, TURN, DTLS | соединения между узлами | MIT |
| [go-socks5](https://github.com/things-go/go-socks5) | прокси SOCKS5 (патч 01) | MIT |
| [anet](https://github.com/wlynxg/anet) | сетевые интерфейсы на Android 11+, своя копия (патч 03) | BSD-3-Clause |
| [gRPC-Go](https://github.com/grpc/grpc-go), [Go protobuf](https://github.com/protocolbuffers/protobuf-go) | API демона и мост | Apache-2.0, BSD-3-Clause |
| [Go](https://go.dev) и [golang.org/x/mobile](https://github.com/golang/mobile) | среда выполнения любого бинарника Go; мост | BSD-3-Clause |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | туннель режима VPN | MIT |
| [lwIP](https://savannah.nongnu.org/projects/lwip) | стек TCP/IP hev-socks5-tunnel | BSD-3-Clause |
| [hev-task-system](https://github.com/heiher/hev-task-system) | задачи hev-socks5-tunnel | MIT |
| [ByeDPI](https://github.com/hufrea/byedpi) | соединение с сервером в обход DPI | MIT |
| [AndroidX, Jetpack Compose](https://developer.android.com/jetpack/androidx), [Material Components](https://github.com/material-components/material-components-android), [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), [OkHttp](https://github.com/square/okhttp) | приложение | Apache-2.0 |

Всего демон собирает около 110 модулей Go (`go.mod` NetBird): Apache-2.0, MIT,
BSD, ISC и CC0, и три неизменённых модуля HashiCorp под MPL-2.0 (`errwrap`,
`go-multierror`, `go-version`). Ни одного под GPL или AGPL.

**Части NetBird под AGPL не собираются.** `management/`, `signal/`, `relay/`,
`combined/`, `proxy/` и `tools/idp-migrate/` у NetBird под AGPLv3 — это
серверы, не клиент. Ни один их пакет в BirdSocks не входит; это показывает
`go list -deps ./client/birdsocksd` в `appctr/netbird_src`. Кода из
официального приложения NetBird для Android (GPLv3) здесь тоже нет.

## В приложении

Настройки → О приложении → **Лицензии** перечисляют компоненты; **Полные
тексты** открывают каждую лицензию целиком, как MIT и BSD требуют от
бинарной сборки. Тексты лежат в
[`app/src/main/assets/third_party_licenses.txt`](../app/src/main/assets/third_party_licenses.txt);
обновляйте его, когда добавляется компонент или меняется его лицензия.

## Товарный знак

NetBird — товарный знак NetBird GmbH. BirdSocks — неофициальный клиент:
NetBird GmbH его не делает, не одобряет и не поддерживает, и с ней он не
связан. Имя говорит лишь о том, с какой сетью работает приложение.
