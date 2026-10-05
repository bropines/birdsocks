# Сборка BirdSocks

## Что собирается

* **`libnetbird.so`** — демон NetBird: `client/server` из NetBird за нашей точкой
  входа `appctr/birdsocksd`, из релиза, закреплённого в `appctr/NETBIRD_VERSION`
  (сверяется с `appctr/NETBIRD_SHA256`), с патчами из `appctr/patches/`.
  Статический бинарник `GOOS=linux`, по одному на ABI; приложение запускает его
  отдельным процессом.
* **`appctr.aar`** — мост gomobile (`appctr/*.go`): запускает демон и общается с
  его gRPC API.
* **`libhev-socks5-tunnel.so`** — туннель режима VPN, из подмодуля
  `app/src/main/jni/hev-socks5-tunnel`; его собирает ndkBuild в Gradle.
* **`libbyedpi.so`** — ByeDPI (`app/src/main/jni/byedpi`), его тоже собирает ndkBuild.
* **APK** — Kotlin и Compose вместе со всем перечисленным.

## Шаги

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# уже склонированный: git submodule update --init
export ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358
cd appctr && bash build.sh && cd ..      # TS_ABIS=arm64-v8a — один ABI
./gradlew app:assembleDebug              # ставится рядом с релизом (.dev)
```

`build.sh` нужны Go (версия из `appctr/go.mod`), `gomobile` и NDK. Запускайте
его после любого изменения Go или патчей: APK упаковывает только то, что он собрал.

Для релизной сборки нужно хранилище ключей:

```bash
KEYSTORE_FILE="$PWD/birdsocks.jks" KEYSTORE_PASSWORD=... \
KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease
```

Получается по APK на каждый ABI и универсальный; `-PtargetAbi=arm64-v8a`
собирает только этот ABI. Весь порядок выпуска — [`RELEASING_RU.md`](RELEASING_RU.md).

## Изменения в демоне

Правьте `appctr/netbird_src/`, затем пересоздайте патчи:

```bash
bash appctr/patches/recreate_patches.sh
```

Скрипт сравнивает с нетронутым `appctr/netbird_orig/` и перечисляет файлы каждого
патча; для нового добавьте строку `make_patch`. Патчи должны накладываться с
`patch -p1 -F0`.

## Проверка демона без телефона

Тестам Go нужно только пропатченное дерево, без NDK; CI гоняет пакеты,
перечисленные в `.github/workflows/ci.yml`:

```bash
bash appctr/build.sh --prepare
cd appctr/netbird_src && go test -ldflags=-checklinkname=0 \
    ./client/iface/netstack/... ./client/net/ ./client/birdsocksd/
```

Сборка для хоста выполняет тот же код (это Linux-бинарник):

```bash
cd appctr/netbird_src && CGO_ENABLED=0 go build -ldflags=-checklinkname=0 \
    -o /tmp/nb/libnetbird.so ./client/birdsocksd && cd ..
BIRDSOCKS_LIVE_LIB=/tmp/nb go test -run TestDaemonLive -v .
```

С `BIRDSOCKS_LIVE_SETUP_KEY=<ключ>` тест ещё и регистрирует узел и подключается.
