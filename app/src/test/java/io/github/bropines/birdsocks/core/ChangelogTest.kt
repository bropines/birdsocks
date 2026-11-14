package io.github.bropines.birdsocks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/*
 * "What's new" reads CHANGELOG.md, and CHANGELOG_RU.md for the Russian interface. The
 * first tests are what keep the second file from rotting: a release that renames
 * [Unreleased] in one file and not in the other, or a bullet added to one, fails here
 * instead of reaching the dialog as English in a Russian screen (or as a stale translation).
 */
class ChangelogTest {
    private val repository: File = run {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }
        module.canonicalFile.parentFile
    }
    private val english = Changelog.parse(File(repository, Changelog.ASSET_NAME).readText())
    private val russian = Changelog.parse(File(repository, Changelog.ASSET_NAME_RU).readText())

    private fun Changelog.Section.isUnreleased() = version.equals("unreleased", ignoreCase = true)

    /** The sections must have the same date, the same groups in the same order, and as many bullets in each. */
    private fun assertSameShape(en: Changelog.Section, ru: Changelog.Section) {
        val label = "[${en.version}]"
        assertEquals("$label: the date", en.date, ru.date)
        assertEquals("$label: the groups (Added, Changed, Fixed, ...) of CHANGELOG_RU.md", en.groups.size, ru.groups.size)
        en.groups.zip(ru.groups).forEach { (e, r) ->
            assertEquals("$label ${e.title} / ${r.title}: the bullets", e.items.size, r.items.size)
            assertEquals("$label ${e.title} / ${r.title}: the nesting of the bullets", e.items.map { it.level }, r.items.map { it.level })
        }
    }

    @Test
    fun theNewestVersionHasARussianCounterpart() {
        val newest = english.first { !it.isUnreleased() }
        val translated = russian.firstOrNull { it.version == newest.version }
        assertNotNull(
            "CHANGELOG_RU.md has no section [${newest.version}]: translate it, or rename [Unreleased] there as it was renamed in CHANGELOG.md",
            translated
        )
        assertSameShape(newest, translated!!)
    }

    @Test
    fun theUnreleasedWorkIsInBothFiles() {
        val coming = english.firstOrNull { it.isUnreleased() && it.groups.any { g -> g.items.isNotEmpty() } } ?: return
        val translated = russian.firstOrNull { it.isUnreleased() }
        assertNotNull("CHANGELOG_RU.md has no [Unreleased] section for the work CHANGELOG.md lists", translated)
        assertSameShape(coming, translated!!)
    }

    /** Older sections need no translation, but one that exists must match its English one. */
    @Test
    fun everyRussianSectionMatchesItsEnglishOne() {
        val byVersion = english.associateBy { it.version }
        russian.forEach { ru ->
            val en = byVersion[ru.version]
            assertNotNull("CHANGELOG_RU.md has [${ru.version}], which CHANGELOG.md does not", en)
            assertSameShape(en!!, ru)
        }
    }

    @Test
    fun aRussianSectionIsShownInPlaceOfTheEnglishOneOfTheSameVersion() {
        val shown = Changelog.pick(Changelog.parse(SAMPLE_EN), Changelog.parse(SAMPLE_RU))
        assertEquals("1.1", shown?.version)
        assertEquals("Добавлено", shown?.groups?.single()?.title)
    }

    @Test
    fun aVersionWithoutRussianTextFallsBackToEnglish() {
        val en = Changelog.parse(SAMPLE_EN)
        // Only the older version is translated: the newest is shown as it is.
        val ru = Changelog.parse("## [1.0] - 2026-01-01\n\n### Добавлено\n\n- Старое.\n")
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, ru))
        // A heading with nothing under it is no translation either.
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, Changelog.parse("## [1.1] - 2026-02-01\n")))
        assertSame(en.first { it.version == "1.1" }, Changelog.pick(en, emptyList()))
    }

    @Test
    fun theUnreleasedSectionIsNeverTheOneShown() {
        val ru = Changelog.parse("## [Unreleased]\n\n### Добавлено\n\n- Скоро.\n")
        assertEquals("1.1", Changelog.pick(Changelog.parse(SAMPLE_EN), ru)?.version)
        assertEquals(null, Changelog.pick(emptyList(), ru))
    }

    /** "What's new" appears once per version; a debug build's suffix is the same version. */
    @Test
    fun aBuildSuffixIsTheSameVersion() {
        assertEquals("0.4.0", Changelog.seenKey("0.4.0"))
        assertEquals("0.4.0", Changelog.seenKey("0.4.0-dev"))
        assertEquals("0.5.0", Changelog.seenKey("v0.5.0"))
    }

    private companion object {
        const val SAMPLE_EN = """
## [Unreleased]

### Added

- Soon.

## [1.1] - 2026-02-01

### Added

- New.

## [1.0] - 2026-01-01

### Added

- Old.
"""
        const val SAMPLE_RU = """
## [1.1] - 2026-02-01

### Добавлено

- Новое.
"""
    }
}
