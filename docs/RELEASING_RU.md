# Выпуск BirdSocks

*English version: [RELEASING.md](RELEASING.md)*

От чистой копии до подписанных APK, на примере 0.1.0, первого выпуска.
Выпуски собираются локально: **GitHub Actions в репозитории выключены**, пока
автор не решит иначе, и `.github/workflows/android.yml` спит. Ничего не
пушится, не тегается на GitHub и не публикуется без автора.

## 1. Коммит выпуска

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# или в существующей копии: git status (чисто), git submodule update --init
```

- `version.properties`: `VERSION_NAME` и `VERSION_CODE` =
  major·1000000 + minor·10000 + patch·100 (0.1.0 → 10000).
- `CHANGELOG.md`: `## [0.1.0] - Unreleased` становится `## [0.1.0] - ГГГГ-ММ-ДД`,
  днём выпуска.
- `fastlane/metadata/android/{en-US,ru}/changelogs/<VERSION_CODE>.txt`: выжимка
  раздела в две-три строки, не больше 500 символов.
- `readme.md`, `readme_ru.md`: убрать пометку «не выпущен».
- Коммит `chore: release 0.1.0`; тег `v0.1.0` локально. Собирать только после
  коммита: APK несёт хеш коммита.

## 2. Нативное ядро, все четыре ABI

```bash
export ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358
export PATH="$PATH:$HOME/go/bin"   # gomobile, gobind: версия x/mobile из appctr/go.mod
cd appctr && bash build.sh --clean && cd ..
```

`--clean` заново скачивает NetBird и сверяет его с `appctr/NETBIRD_SHA256`;
`TS_ABIS` не задавайте, чтобы собрать все ABI. После этого в
`app/src/main/jniLibs/` должен лежать `libnetbird.so` для armeabi-v7a, arm64-v8a,
x86 и x86_64. Gradle откажется собирать выпуск, если `appctr/tmp/appctr.aar`
старше какого-либо исходника Go или патча.

## 3. Подписанные APK

```bash
export KEYSTORE_FILE=/путь/вне/репозитория/birdsocks.jks KEY_ALIAS=...
read -rs KEYSTORE_PASSWORD; read -rs KEY_PASSWORD
export KEYSTORE_PASSWORD KEY_PASSWORD
./gradlew clean app:assembleRelease
```

- Этим же ключом подписываются все будущие обновления: обновление с другой
  подписью Android не поставит. Храните копию хранилища ключей; в репозиторий
  его не кладите.
- На выходе `app/build/outputs/apk/release/app-<abi>-release.apk` для четырёх
  ABI (versionCode + 1…4) и `app-universal-release.apk` (versionCode).
  Переименуйте в `BirdSocks-v<ВЕРСИЯ>-<abi>-release.apk`.
- Сборка падает, если R8 удалил JNI-метод (`verifyReleaseNativeMethods`:
  `TProxy*` у hev, `jni*` у ByeDPI) или не задана переменная подписи.
- Проверьте один APK: `apksigner verify --print-certs` показывает ваш
  сертификат; `aapt2 dump badging` — `io.github.bropines.birdsocks` и версию;
  `unzip -l` — `libnetbird.so`, `libgojni.so`, `libhev-socks5-tunnel.so` и
  `libbyedpi.so`.
- Сохраните `app/build/outputs/mapping/release/mapping.txt` вместе с выпуском:
  стек вызовов из выпуска читается только через него (`retrace`).

## 4. На устройстве

Поставьте релизный APK (не `.dev`); HyperOS сначала спросит на экране.

- Вход через браузер и по ключу установки; NetBird Cloud и свой сервер.
- SOCKS5 (с паролем и без): узел по имени NetBird, интернет напрямую и через
  выходной узел.
- DNS-прокси: имя NetBird и публичное имя.
- Режим VPN: включить, выключить, включить снова; DNS отвечает; исключённое
  приложение идёт мимо; остановка BirdSocks опускает VPN.
- ByeDPI для соединения с сервером: включить, выключить.
- Плитка быстрых настроек, запуск после перезагрузки, смена аккаунта, отладочный пакет.
- Настройки → О приложении показывают версию и коммит; «Лицензии» открываются.
- В логах нет `UnsatisfiedLinkError`, `NoSuchMethodError`,
  `ClassNotFoundException` и `SerializationException`.

## 5. Публикация

Шаг автора: пуш коммита и тега и релиз на GitHub с APK и разделом CHANGELOG
в описании.
