Changelogs for app stores (F-Droid, IzzyOnDroid) live in this directory.

One file per release, named after its versionCode, literally and without
padding: VERSION_CODE from version.properties, so 10000.txt for 0.1.0.
Per-ABI APKs carry VERSION_CODE plus 1 to 4 (app/build.gradle.kts); a store
that lists them separately looks for those names. Plain text, at most 500
characters. Stores ignore any other file name here, this README included.

The file has to be in the tagged release commit. Write it as a two- or
three-line digest of the CHANGELOG.md section, one file per locale (en-US is
the fallback for every other language).
