# Translating BirdSocks

*Русская версия: [TRANSLATING_RU.md](TRANSLATING_RU.md)*

BirdSocks is to be translated on [Hosted Weblate](https://hosted.weblate.org/), as the project `birdsocks`. Translators work in the browser, with machine suggestions to start from, and Weblate sends the result back as pull requests. English is the source, Russian is maintained by hand in the repository, and every other language comes from Weblate.

> **Status: to be created by the author.** The Weblate project does not exist yet: the free Libre plan is for public projects, and this repository is still private. The repository is ready for it — the steps below, the language list and its test — and the README badges point at `hosted.weblate.org/engage/birdsocks/`, which starts working once the project is created.

## For translators

Open the project on Hosted Weblate, pick a language (or start a new one) and translate. Strings that are brand or protocol names — NetBird, SOCKS5, DNS — and technical placeholders such as addresses are marked non-translatable and do not appear.

A few conventions:
- Keep placeholders exactly: `%1$s`, `%2$d`, `\n`.
- Keep it short. Explanations on screen are folded to one or two lines; a translation twice the length of the English is cut off.
- "NetBird" and its product names stay as they are; the app is an unofficial client, never "the NetBird app".

## Setting the project up (maintainer, once)

1. **Create the project** on hosted.weblate.org → *Add new translation project*. Name `BirdSocks`, slug `birdsocks`, website the GitHub repository. Translation licence: **BSD-3-Clause**, the code's own — the default, Proprietary, rules out the free Libre plan. The project starts in a trial; once it exists, ask for the **Libre** plan (public, libre-licensed projects) from its billing page.
2. **Main component**, *From version control*:
   - Repository: `https://github.com/bropines/birdsocks.git`, branch `main`.
   - Version control: *GitHub pull request*. Afterwards accept the offer to move the component to the **Hosted Weblate GitHub app**: it pushes translation branches and opens pull requests, so nothing lands on `main` unreviewed.
   - File format: **Android String Resource**.
   - File mask: `app/src/main/res/values-*/strings.xml`
   - Monolingual base language file: `app/src/main/res/values/strings.xml`
   - Template for new translations: the same base file. Language code style: *Android*.
3. **The other string files** — screens keep their strings in `strings_<area>.xml` next to `strings.xml`. Add the *Component discovery* add-on to the main component:
   - Regular expression: `app/src/main/res/values-(?P<language>[^/]*)/(?P<component>strings_[^/]*)\.xml`
   - Component name: `{{ component }}`
   - Base file: `app/src/main/res/values/{{ component }}.xml`, also as the template for new translations.
   - File format: Android String Resource.
4. **Notifications** of new pushes come through the GitHub app. Only without it: this repository's *Settings → Webhooks → Add webhook*, payload URL `https://hosted.weblate.org/hooks/github/`, content type `application/json`, just the push event.
5. **Add-ons** worth enabling: *Cleanup translation files* (drops strings the source removed) and *Squash Git commits* (one commit per language per PR).
6. **Russian is maintained by hand.** Every change adds its Russian strings to `values-ru/` in the same commit, so Weblate only ever finds Russian complete. Keep it that way on Weblate too: in the Russian translation's settings, or by restricting it to reviewers, so that a suggestion there cannot overwrite the hand-written text unseen.
7. **Badge** in `readme.md` and `readme_ru.md`, already in place:

   ```markdown
   [![Translation status](https://hosted.weblate.org/widget/birdsocks/svg-badge.svg)](https://hosted.weblate.org/engage/birdsocks/)
   ```

## For contributors adding strings

Add every new string in English to `values/` and in Russian to `values-ru/`; Weblate carries the rest. Give a screen with many strings its own `strings_<area>.xml` rather than growing `strings.xml`, so component discovery picks it up. Mark a string `translatable="false"` only when it is not language at all: a brand or protocol name, an address, a format with no words.

## Merging a new language

A language appears in Settings → Appearance & language once it has a translated string and its tag is in `AppLanguages.TAGS` (`core/AppLanguages.kt`) and in `res/xml/locales_config.xml`, the list Android 13+ shows in its own per-app language settings. `AppLanguagesTest` fails until both match `res/values-*`, so merging a new language from Weblate means adding its BCP 47 tag (`values-zh-rTW` → `zh-TW`) in both places. A language Weblate has only opened, with empty files, is left out until it has strings. Weblate's pull requests are merged by the author.

## What is not on Weblate

- **The changelog.** `CHANGELOG.md` and its Russian twin `CHANGELOG_RU.md` are written together by hand; "What's new" shows the Russian one in a Russian interface and English everywhere else.
- **Store texts.** `fastlane/metadata/android/` has `en-US` and `ru`. Weblate can take them later as an extra component (format *Fastlane metadata*); until then a new language there is a pull request.
