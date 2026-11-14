package io.github.bropines.birdsocks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Locale

/*
 * A language reaches the picker only through AppLanguages.TAGS, and Android 13+'s own
 * per-app language page only through res/xml/locales_config.xml. These keep both lists
 * equal to the translations the APK carries: a language Weblate brings fails here until
 * its tag is added, and a tag without strings would offer a choice that changes nothing.
 */
class AppLanguagesTest {
    private val res: File = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }
        .let { File(it, "src/main/res") }

    /** values-zh-rTW → zh-TW: the tag a resource directory answers; null for values-night and the like. */
    private fun tagOf(directory: String): String? {
        val qualifier = directory.removePrefix("values-")
        val match = Regex("^([a-z]{2,3})(?:-r([A-Z]{2}))?$").matchEntire(qualifier) ?: return null
        return listOfNotNull(match.groupValues[1], match.groupValues[2].ifEmpty { null }).joinToString("-")
    }

    /** Whether the directory holds at least one translated string — a language Weblate only opened holds none. */
    private fun translates(directory: File): Boolean =
        directory.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            .any { Regex("<(string|plurals) name=").containsMatchIn(it.readText()) }

    @Test
    fun everyTranslatedLanguageIsOffered() {
        val translated = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .mapNotNull { dir -> tagOf(dir.name)?.takeIf { translates(dir) } }
        assertEquals(
            "AppLanguages.TAGS must list English and every values-* language with a translated string",
            (translated + "en").sorted(),
            AppLanguages.TAGS.sorted()
        )
    }

    @Test
    fun theSystemSettingsOfferTheSameLanguages() {
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""<locale\s+android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals("res/xml/locales_config.xml must list exactly AppLanguages.TAGS", AppLanguages.TAGS.sorted(), listed.sorted())
    }

    @Test
    fun namesAreTheLanguagesOwn() {
        assertEquals("Русский", AppLanguages.nativeName("ru"))
        assertEquals("English", AppLanguages.nativeName("en"))
        assertEquals("繁體中文", AppLanguages.nativeName("zh-TW"))
        assertNull(AppLanguages.nameFor("en", Locale.ENGLISH))
        assertEquals("Russian", AppLanguages.nameFor("ru", Locale.ENGLISH))
    }

    @Test
    fun aPlatformLanguageMapsOntoAnOfferedTag() {
        assertEquals("ru", AppLanguages.fromPlatform("ru"))
        assertEquals("ru", AppLanguages.fromPlatform("ru-RU"))
        assertEquals("en", AppLanguages.fromPlatform("en-GB"))
        // Not offered: kept as it is, and the picker shows "System".
        assertEquals("de", AppLanguages.fromPlatform("de"))
    }
}
