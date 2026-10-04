# Как помочь BirdSocks

BirdSocks — Android-клиент NetBird. Он запускает собственный демон NetBird
дочерним процессом в режиме netstack и отдаёт сеть приложениям через
локальный SOCKS5-прокси, DNS-прокси и, по желанию, VPN-режим. VPN-слот
нужен, только если включить VPN-режим.

## Сначала прочитайте
- [`agents.md`](agents.md) — правила: как устроены демон, мост и приложение, что оформлять патчем, договорённости по интерфейсу, лицензии.
- [`docs/BUILDING_RU.md`](docs/BUILDING_RU.md) — как собрать демон (`appctr/build.sh`) и APK.
- [`docs/RELEASING_RU.md`](docs/RELEASING_RU.md) — как делается релиз.
- [`docs/TRANSLATING_RU.md`](docs/TRANSLATING_RU.md) — как добавить язык.

## Главные правила
- Демон — это NetBird. Общайтесь с ним через его gRPC API, а не через CLI. Его поведение меняется только патчем в `appctr/patches/`: правка в `appctr/netbird_src/`, затем `appctr/patches/recreate_patches.sh`, затем `appctr/build.sh`.
- Не копируйте код из официального Android-приложения NetBird. Оно под GPLv3: читайте его, чтобы понять поведение, а код пишите свой.
- Новые строки кладите в `res/values*/strings_<раздел>.xml` своего экрана, на английском и русском.
- На каждое изменение поведения — короткий пункт в верхний раздел [`CHANGELOG.md`](CHANGELOG.md). Причины пишите в сообщение коммита.
- Коммиты маленькие, сообщения в стиле conventional (`feat:`, `fix:`, `docs:`…).

## Перед pull request
- `./gradlew assembleDebug lintDebug -PlintNewApiOnly`. Приложение поддерживает API 24+.
- Если меняли Go: `go test -ldflags=-checklinkname=0` для своих пакетов, запуск в `appctr/netbird_src`.
- Проверьте на устройстве: вход, прокси и то, что поменяли.

Отправляя изменения, вы соглашаетесь опубликовать их под лицензией BSD 3-Clause этого репозитория.
