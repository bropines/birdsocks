# Выпуск BirdSocks

*English version: [RELEASING.md](RELEASING.md)*

От чистой копии до подписанных APK, на примере 0.1.0, первого выпуска.
Тег `v<VERSION_NAME>`, отправленный на GitHub, собирает выпуск в GitHub Actions
и оставляет его **черновиком**; та же сборка идёт и локально (разделы 5–6).
**GitHub Actions в репозитории выключены**, пока автор их не включит
(раздел 1). Ничего не пушится, не тегается на GitHub и не публикуется без автора.

## 1. Один раз: ключ подписи и Actions

1. **Ключ.** Если вы уже подписываете BirdSocks локально, возьмите то же
   хранилище: Android ставит обновление только поверх той же подписи.

   ```bash
   keytool -genkeypair -v -storetype PKCS12 -keystore birdsocks.jks \
       -alias birdsocks -keyalg RSA -keysize 4096 -validity 10000
   ```

   У PKCS12 один пароль на хранилище и ключ. Сохраните файл и пароль вне
   репозитория: потерянный ключ значит, что для обновления всем придётся
   удалить приложение.
2. **Base64**, одной строкой: `base64 -w0 birdsocks.jks > birdsocks.jks.b64`
3. **Секреты** — в Settings → Secrets and variables → Actions → New repository
   secret или через `gh`:

   | Секрет | Значение |
   |---|---|
   | `KEYSTORE_BASE64` | содержимое `birdsocks.jks.b64` |
   | `KEYSTORE_PASSWORD` | пароль хранилища |
   | `KEY_ALIAS` | `birdsocks`, `-alias` выше |
   | `KEY_PASSWORD` | пароль ключа: тот же, у PKCS12 |

   ```bash
   R=bropines/birdsocks
   gh secret set KEYSTORE_BASE64 -R $R < birdsocks.jks.b64
   gh secret set KEYSTORE_PASSWORD -R $R          # спросит значение
   gh secret set KEY_ALIAS -R $R --body birdsocks
   gh secret set KEY_PASSWORD -R $R
   rm birdsocks.jks.b64
   ```

   По желанию — переменная, не секрет: `RELEASE_CERT_SHA256`, отпечаток
   сертификата. Тогда сборка откажется от APK, подписанных другим ключом.

   ```bash
   gh variable set RELEASE_CERT_SHA256 -R $R --body \
       "$(keytool -list -v -keystore birdsocks.jks -alias birdsocks | sed -n 's/^\s*SHA256: //p')"
   ```
4. **Включить Actions**: Settings → Actions → General. Хватит разрешить
   действия GitHub и ещё `gradle/actions/*` и `softprops/action-gh-release@*`;
   права workflow можно оставить «только чтение», каждая задача просит своё.
   Запустятся и старые `repomix-context.yml` и `repo-to-llm.yml` (первый — на
   каждый пуш в main): удалите их, если они не нужны.

Что тогда работает:

| Workflow | Когда | Что |
|---|---|---|
| `ci.yml` | пуш в main, pull request | gofmt; тесты Go демона в пропатченном дереве NetBird; отладочный APK (arm64-v8a) с проверкой NewApi, хранится 7 дней |
| `release.yml` | тег `v*` | сверяет тег с `version.properties`, проверяет датированный раздел CHANGELOG и описания для магазинов; собирает ядро для четырёх ABI; подписывает, называет и проверяет APK; пишет `SHA256SUMS`; открывает черновик выпуска |

У приватного репозитория на GitHub Free 2000 минут Actions в месяц и 500 МБ
под артефакты (проверьте текущие лимиты тарифа): сборка выпуска идёт десятки
минут, а её артефакты хранятся дни, не месяцы. Происхождение сборки
(provenance) подтверждается, только когда репозиторий публичный (приватному
нужен GitHub Enterprise Cloud).

## 2. Коммит выпуска

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# или в существующей копии: git status (чисто), git submodule update --init
```

- `version.properties`: `VERSION_NAME` и `VERSION_CODE` =
  major·1000000 + minor·10000 + patch·100 (0.1.0 → 10000).
- `CHANGELOG.md`: `## [0.1.0] - Unreleased` становится `## [0.1.0] - ГГГГ-ММ-ДД`,
  днём выпуска. Раздел без даты сборка не примет.
- `fastlane/metadata/android/{en-US,ru}/changelogs/<VERSION_CODE>.txt`: выжимка
  раздела в две-три строки, не больше 500 символов.
- `readme.md`, `readme_ru.md`: убрать пометку «не выпущен».
- Коммит `chore: release 0.1.0`; тег `v0.1.0`.

`python3 scripts/changelog_section.py 0.1.0 --require-date` печатает описание,
которое получит выпуск, или говорит, чего не хватает.

## 3. Тег и пуш

```bash
git push origin main && git push origin v0.1.0
```

Workflow Release идёт четырьмя задачами: preflight, core, apk, release. Затем:

- Скачайте артефакт запуска `mapping-v0.1.0` и сохраните его вместе с
  выпуском: стек вызовов из выпуска читается только через него (`retrace`).
  Через 90 дней он пропадёт.
- Releases → черновик: шесть файлов, `BirdSocks-v0.1.0-<abi>.apk` для
  armeabi-v7a, arm64-v8a, x86, x86_64 и universal, и `SHA256SUMS`; описание —
  раздел CHANGELOG. Проверьте (раздел 7) и опубликуйте.

Запуск упал случайно — Re-run failed jobs. Нужна правка — удалите черновик,
закоммитьте правку, передвиньте тег (`git tag -f v0.1.0`,
`git push -f origin v0.1.0`), пока ничего не опубликовано.

## 4. Обновления CI

Действия закреплены по SHA коммита, версия — в комментарии.
`.github/renovate.json` держит их свежими, когда на репозиторий поставлено
приложение Renovate; оно же предлагает обновления Gradle и Go — `appctr/go.mod`
следует за `go.mod` NetBird, так что они ждут обновления NetBird. Для
Dependabot вместо этого нужен `.github/dependabot.yml` с `github-actions`.

## 5. Локально: нативное ядро, все четыре ABI

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

## 6. Локально: подписанные APK

```bash
export KEYSTORE_FILE=/путь/вне/репозитория/birdsocks.jks KEY_ALIAS=birdsocks
read -rs KEYSTORE_PASSWORD; read -rs KEY_PASSWORD
export KEYSTORE_PASSWORD KEY_PASSWORD
./gradlew clean app:assembleRelease
```

- На выходе `app/build/outputs/apk/release/app-<abi>-release.apk` для четырёх
  ABI (versionCode + 1…4) и `app-universal-release.apk` (versionCode).
  Переименуйте в `BirdSocks-v<ВЕРСИЯ>-<abi>.apk`, как это делает CI, и
  запишите `sha256sum BirdSocks-*.apk > SHA256SUMS`.
- Сборка падает, если R8 удалил JNI-метод (`verifyReleaseNativeMethods`:
  `TProxy*` у hev, `jni*` у ByeDPI) или не задана переменная подписи.
- Проверьте один APK: `apksigner verify --print-certs` показывает ваш
  сертификат; `aapt2 dump badging` — `io.github.bropines.birdsocks` и версию;
  `unzip -l` — `libnetbird.so`, `libgojni.so`, `libhev-socks5-tunnel.so` и
  `libbyedpi.so`.
- Сохраните `app/build/outputs/mapping/release/mapping.txt` вместе с выпуском.

## 7. На устройстве

Поставьте релизный APK (не `.dev`); HyperOS сначала спросит на экране.

- Вход через браузер и по ключу установки; NetBird Cloud и свой сервер.
- SOCKS5 (с паролем и без): узел по имени NetBird, интернет напрямую и через
  выходной узел.
- DNS-прокси: имя NetBird и публичное имя.
- Режим VPN: включить, выключить, включить снова; DNS отвечает; исключённое
  приложение идёт мимо; остановка BirdSocks опускает VPN.
- ByeDPI для соединения с сервером: включить, выключить.
- Плитка быстрых настроек, запуск после перезагрузки, смена аккаунта, отладочный пакет.
- Настройки → О приложении показывают версию и коммит; «Лицензии» → «Полные
  тексты» открывают тексты лицензий.
- В логах нет `UnsatisfiedLinkError`, `NoSuchMethodError`,
  `ClassNotFoundException` и `SerializationException`.

## 8. Публикация

Шаг автора: опубликовать черновик — или, после локальной сборки, выпуск на
GitHub с APK, `SHA256SUMS` и разделом CHANGELOG в описании.
