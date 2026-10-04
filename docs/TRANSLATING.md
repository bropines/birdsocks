# Translating BirdSocks

*Русская версия: [TRANSLATING_RU.md](TRANSLATING_RU.md)*

BirdSocks is to be translated on [Hosted Weblate](https://hosted.weblate.org/). The project does not exist yet: Weblate's free Libre plan is for public projects, and this repository is still private. Until then English and Russian are maintained in the repository. Once it exists, translators work in the browser, with machine suggestions to start from, and Weblate sends the result back as pull requests.

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
6. **Badge** for the README once the project exists:

   ```markdown
   [![Translation status](https://hosted.weblate.org/widget/birdsocks/svg-badge.svg)](https://hosted.weblate.org/engage/birdsocks/)
   ```

## For contributors adding strings

Add every new string in English to `values/` and in Russian to `values-ru/`; Weblate will carry the rest. Give a screen with many strings its own `strings_<area>.xml` rather than growing `strings.xml`. Mark a string `translatable="false"` only when it is not language at all: a brand or protocol name, an address, a format with no words.
