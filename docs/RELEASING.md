# Releasing BirdSocks

*Русская версия: [RELEASING_RU.md](RELEASING_RU.md)*

From a clean checkout to signed APKs, written for 0.1.0, the first release.
Releases are built locally: **GitHub Actions are disabled** in the repository
until the author decides otherwise, and `.github/workflows/android.yml` is
dormant. Nothing is pushed, tagged on GitHub or published without the author.

## 1. The release commit

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# or, in an existing clone: git status (clean), git submodule update --init
```

- `version.properties`: `VERSION_NAME`, and `VERSION_CODE` =
  major·1000000 + minor·10000 + patch·100 (0.1.0 → 10000).
- `CHANGELOG.md`: `## [0.1.0] - Unreleased` becomes `## [0.1.0] - YYYY-MM-DD`,
  the day of the release.
- `fastlane/metadata/android/{en-US,ru}/changelogs/<VERSION_CODE>.txt`: a two-
  or three-line digest of that section, at most 500 characters.
- `readme.md`, `readme_ru.md`: drop the "not released" note.
- Commit `chore: release 0.1.0`; tag `v0.1.0` locally. Build only after the
  commit: the APK carries the commit hash.

## 2. The native core, all four ABIs

```bash
export ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358
export PATH="$PATH:$HOME/go/bin"   # gomobile, gobind: the x/mobile version in appctr/go.mod
cd appctr && bash build.sh --clean && cd ..
```

`--clean` downloads NetBird again and checks it against `appctr/NETBIRD_SHA256`;
leave `TS_ABIS` unset to build every ABI. `app/src/main/jniLibs/` must then hold
`libnetbird.so` for armeabi-v7a, arm64-v8a, x86 and x86_64. Gradle refuses a
release whose `appctr/tmp/appctr.aar` is older than a Go source or patch.

## 3. Signed APKs

```bash
export KEYSTORE_FILE=/path/outside/the/repo/birdsocks.jks KEY_ALIAS=...
read -rs KEYSTORE_PASSWORD; read -rs KEY_PASSWORD
export KEYSTORE_PASSWORD KEY_PASSWORD
./gradlew clean app:assembleRelease
```

- The key signs every later update too: Android refuses an update signed by
  another. Back the keystore up; never commit it.
- Out come `app/build/outputs/apk/release/app-<abi>-release.apk` for the four
  ABIs (versionCode + 1…4) and `app-universal-release.apk` (versionCode).
  Rename them `BirdSocks-v<VERSION>-<abi>-release.apk`.
- The build fails when R8 removed a JNI method (`verifyReleaseNativeMethods`:
  hev's `TProxy*`, ByeDPI's `jni*`) or a signing variable is missing.
- Check one APK: `apksigner verify --print-certs` shows your certificate;
  `aapt2 dump badging` shows `io.github.bropines.birdsocks` and the version;
  `unzip -l` lists `libnetbird.so`, `libgojni.so`, `libhev-socks5-tunnel.so`
  and `libbyedpi.so`.
- Keep `app/build/outputs/mapping/release/mapping.txt` with the release: a
  release stack trace is readable only through it (`retrace`).

## 4. On a device

Install the release APK (not `.dev`); HyperOS asks on screen first.

- Sign-in in the browser and with a setup key; NetBird Cloud and a self-hosted server.
- SOCKS5 (with and without a password): a peer by its NetBird name, the
  internet directly and through an exit node.
- DNS proxy: a NetBird name and a public name.
- VPN mode: on, off and on again; DNS answers; an excluded app goes around it;
  stopping BirdSocks takes the VPN down.
- ByeDPI on for the server connection, then off.
- Quick Settings tile, start after reboot, an account switch, the debug bundle.
- Settings → About shows the version and the commit; Licenses opens.
- Logs hold no `UnsatisfiedLinkError`, `NoSuchMethodError`,
  `ClassNotFoundException` or `SerializationException`.

## 5. Publishing

The author's step: pushing the commit and the tag, and a GitHub release with
the APKs and the CHANGELOG section as its notes.
