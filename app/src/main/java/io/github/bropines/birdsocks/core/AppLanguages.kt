package io.github.bropines.birdsocks.core

import java.util.Locale

/**
 * The languages the app can be switched to, besides the system's (`app_locale`).
 *
 * English is the source and Russian is kept alongside it by hand; every other language
 * comes from Weblate and is offered once someone has translated at least one string of
 * it. A language Weblate has only opened — empty files — waits: picking it would change
 * nothing. AppLanguagesTest fails when `res/values-*`, `res/xml/locales_config.xml` and
 * this list disagree, so a language Weblate brings is added here (and there) before a
 * release.
 *
 * Tags are BCP 47, the form `app_locale` stores and Locale.forLanguageTag reads:
 * `values-zh-rTW` answers `zh-TW`.
 */
object AppLanguages {
    /** `app_locale` for "follow the system". */
    const val SYSTEM = "sys"

    val TAGS: List<String> = listOf("en", "ru", "ja")

    /**
     * [tag]'s name in its own language — what someone who reads only that language looks
     * for in a list. Written out for the scripts where the platform's name is not the one
     * people use (zh-TW is "中文 (台灣)" to it).
     */
    fun nativeName(tag: String): String = NATIVE[tag] ?: Locale.forLanguageTag(tag).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * [tag]'s name in [reader]'s language, or null where that says nothing [nativeName]
     * has not: English to an English reader.
     */
    fun nameFor(tag: String, reader: Locale): String? {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(reader).replaceFirstChar { it.titlecase(reader) }
        return name.takeIf { it.isNotBlank() && it != nativeName(tag) && it != tag }
    }

    /**
     * The `app_locale` value for a language the platform holds for the app (Android 13+'s
     * per-app language, set in the system's settings): the tag of [TAGS] it names, the one
     * of its language when only that matches (`ru-RU` → `ru`), or itself.
     */
    fun fromPlatform(tag: String): String {
        TAGS.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val language = Locale.forLanguageTag(tag).language
        return TAGS.firstOrNull { Locale.forLanguageTag(it).language == language } ?: tag
    }

    private val NATIVE = mapOf(
        "en" to "English",
        "ru" to "Русский",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "ko" to "한국어",
        "ja" to "日本語",
    )
}
