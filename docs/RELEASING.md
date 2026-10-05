# Releasing BirdSocks

*Русская версия: [RELEASING_RU.md](RELEASING_RU.md)*

From a clean checkout to signed APKs, written for 0.1.0, the first release.
A tag `v<VERSION_NAME>` pushed to GitHub builds the release in GitHub Actions
and leaves it as a **draft**; the same build also runs locally (sections 5–6).
**Actions are disabled** in the repository until the author turns them on
(section 1). Nothing is pushed, tagged on GitHub or published without the author.

## 1. Once: the signing key and Actions

1. **The key.** If you already sign BirdSocks locally, reuse that keystore:
   Android installs an update only over the same signature.

   ```bash
   keytool -genkeypair -v -storetype PKCS12 -keystore birdsocks.jks \
       -alias birdsocks -keyalg RSA -keysize 4096 -validity 10000
   ```

   PKCS12 has one password for the store and the key. Back the file and the
   password up outside the repository; a lost key means every user has to
   uninstall to update.
2. **Base64**, one line: `base64 -w0 birdsocks.jks > birdsocks.jks.b64`
3. **The secrets**, in Settings → Secrets and variables → Actions → New
   repository secret, or with `gh`:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the contents of `birdsocks.jks.b64` |
   | `KEYSTORE_PASSWORD` | the keystore's password |
   | `KEY_ALIAS` | `birdsocks`, the `-alias` above |
   | `KEY_PASSWORD` | the key's password: the same one, for PKCS12 |

   ```bash
   R=bropines/birdsocks
   gh secret set KEYSTORE_BASE64 -R $R < birdsocks.jks.b64
   gh secret set KEYSTORE_PASSWORD -R $R          # asks for the value
   gh secret set KEY_ALIAS -R $R --body birdsocks
   gh secret set KEY_PASSWORD -R $R
   rm birdsocks.jks.b64
   ```

   Optional, a variable rather than a secret: `RELEASE_CERT_SHA256`, the
   certificate's fingerprint. The workflow then refuses APKs signed by any
   other key.

   ```bash
   gh variable set RELEASE_CERT_SHA256 -R $R --body \
       "$(keytool -list -v -keystore birdsocks.jks -alias birdsocks | sed -n 's/^\s*SHA256: //p')"
   ```
4. **Actions on**: Settings → Actions → General. Allowing GitHub's actions
   plus `gradle/actions/*` and `softprops/action-gh-release@*` is enough;
   workflow permissions may stay read-only, each job asks for what it needs.
   The older `repomix-context.yml` and `repo-to-llm.yml` start too (the first
   on every push to main): delete them if they are not wanted.

What then runs:

| Workflow | When | What |
|---|---|---|
| `ci.yml` | a push to main, a pull request | gofmt; the daemon's Go tests in the patched NetBird tree; a debug APK (arm64-v8a) with the NewApi lint, kept 7 days |
| `release.yml` | a tag `v*` | checks the tag against `version.properties`, the dated CHANGELOG section and the store changelogs; builds the core for four ABIs; signs, names and verifies the APKs; writes `SHA256SUMS`; opens a draft release |

GitHub Free gives a private repository 2,000 Actions minutes a month and
500 MB for artifacts (check the plan's current limits): a release run takes
tens of minutes, and its artifacts are kept for days, not months. Build provenance is attested only
once the repository is public (private ones need GitHub Enterprise Cloud).

## 2. The release commit

```bash
git clone --recursive https://github.com/bropines/birdsocks.git && cd birdsocks
# or, in an existing clone: git status (clean), git submodule update --init
```

- `version.properties`: `VERSION_NAME`, and `VERSION_CODE` =
  major·1000000 + minor·10000 + patch·100 (0.1.0 → 10000).
- `CHANGELOG.md`: `## [0.1.0] - Unreleased` becomes `## [0.1.0] - YYYY-MM-DD`,
  the day of the release. The workflow refuses an undated section.
- `fastlane/metadata/android/{en-US,ru}/changelogs/<VERSION_CODE>.txt`: a two-
  or three-line digest of that section, at most 500 characters.
- `readme.md`, `readme_ru.md`: drop the "not released" note.
- Commit `chore: release 0.1.0`; tag `v0.1.0`.

`python3 scripts/changelog_section.py 0.1.0 --require-date` prints the notes
the release will get, or says what is missing.

## 3. Tag and push

```bash
git push origin main && git push origin v0.1.0
```

The Release workflow runs four jobs: preflight, core, apk, release. Then:

- Download the run's `mapping-v0.1.0` artifact and keep it with the release:
  a release stack trace is readable only through it (`retrace`). It expires
  in 90 days.
- Releases → the draft: six files, `BirdSocks-v0.1.0-<abi>.apk` for
  armeabi-v7a, arm64-v8a, x86, x86_64 and universal, and `SHA256SUMS`; the
  notes are the CHANGELOG section. Test (section 7), then publish.

A run that failed by chance: Re-run failed jobs. One that needs a fix: delete
the draft, commit the fix, move the tag (`git tag -f v0.1.0`,
`git push -f origin v0.1.0`) — only while nothing is published.

## 4. Keeping CI current

Actions are pinned by commit SHA, with the version in a comment.
`.github/renovate.json` keeps them current once the Renovate app is installed
on the repository; it also proposes Gradle and Go bumps — `appctr/go.mod`
follows NetBird's, so those wait for a NetBird update. Dependabot would need
a `.github/dependabot.yml` for `github-actions` instead.

## 5. Locally: the native core, all four ABIs

```bash
export ANDROID_HOME=~/android-sdk ANDROID_NDK_HOME=~/android-sdk/ndk/28.2.13676358
export PATH="$PATH:$HOME/go/bin"   # gomobile, gobind: the x/mobile version in appctr/go.mod
cd appctr && bash build.sh --clean && cd ..
```

`--clean` downloads NetBird again and checks it against `appctr/NETBIRD_SHA256`;
leave `TS_ABIS` unset to build every ABI. `app/src/main/jniLibs/` must then hold
`libnetbird.so` for armeabi-v7a, arm64-v8a, x86 and x86_64. Gradle refuses a
release whose `appctr/tmp/appctr.aar` is older than a Go source or patch.

## 6. Locally: signed APKs

```bash
export KEYSTORE_FILE=/path/outside/the/repo/birdsocks.jks KEY_ALIAS=birdsocks
read -rs KEYSTORE_PASSWORD; read -rs KEY_PASSWORD
export KEYSTORE_PASSWORD KEY_PASSWORD
./gradlew clean app:assembleRelease
```

- Out come `app/build/outputs/apk/release/app-<abi>-release.apk` for the four
  ABIs (versionCode + 1…4) and `app-universal-release.apk` (versionCode).
  Rename them `BirdSocks-v<VERSION>-<abi>.apk`, as CI does, and write
  `sha256sum BirdSocks-*.apk > SHA256SUMS`.
- The build fails when R8 removed a JNI method (`verifyReleaseNativeMethods`:
  hev's `TProxy*`, ByeDPI's `jni*`) or a signing variable is missing.
- Check one APK: `apksigner verify --print-certs` shows your certificate;
  `aapt2 dump badging` shows `io.github.bropines.birdsocks` and the version;
  `unzip -l` lists `libnetbird.so`, `libgojni.so`, `libhev-socks5-tunnel.so`
  and `libbyedpi.so`.
- Keep `app/build/outputs/mapping/release/mapping.txt` with the release.

## 7. On a device

Install the release APK (not `.dev`); HyperOS asks on screen first.

- Sign-in in the browser and with a setup key; NetBird Cloud and a self-hosted server.
- SOCKS5 (with and without a password): a peer by its NetBird name, the
  internet directly and through an exit node.
- DNS proxy: a NetBird name and a public name.
- VPN mode: on, off and on again; DNS answers; an excluded app goes around it;
  stopping BirdSocks takes the VPN down.
- ByeDPI on for the server connection, then off.
- Quick Settings tile, start after reboot, an account switch, the debug bundle.
- Settings → About shows the version and the commit; Licenses → Full texts
  opens the license texts.
- Logs hold no `UnsatisfiedLinkError`, `NoSuchMethodError`,
  `ClassNotFoundException` or `SerializationException`.

## 8. Publishing

The author's step: publishing the draft — or, after a local build, a GitHub
release with the APKs, `SHA256SUMS` and the CHANGELOG section as its notes.
