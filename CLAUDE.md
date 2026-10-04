# BirdSocks — instructions for Claude Code sessions

Loaded automatically at the start of every session in this repository. It is a
pointer; the substance lives in the files it names.

1. **Read [`agents.md`](agents.md) first.** It is the project mandate: how the
   app runs NetBird, the build and patch pipeline, UI standards and the working
   agreements — including *never* `git push` without the author's explicit «пушь».
2. Unreleased work is at the top of [`CHANGELOG.md`](CHANGELOG.md). Extend that
   section in its own style whenever you change behaviour.
3. Plans and leftovers: [`docs/ROADMAP.md`](docs/ROADMAP.md).
4. Building: [`docs/BUILDING.md`](docs/BUILDING.md). Run `appctr/build.sh` after
   any Go or patch change, or the APK will not contain it.
5. The project's base comes from TailSocks (`~/projects/tailsocks`): port from it
   rather than reinvent, but name it nowhere in user-facing text or docs. The official
   NetBird Android app is GPLv3 and is a reference only: read it, never copy
   from it — this repository is BSD-3.
6. Devices arrive over `adb connect` from the author, who writes in Russian —
   answer in Russian. Never `adb uninstall` without asking.
