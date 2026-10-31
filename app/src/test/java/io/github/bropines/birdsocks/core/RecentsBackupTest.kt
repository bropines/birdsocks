package io.github.bropines.birdsocks.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecentsBackupTest {
    @Test
    fun recentsPreferenceSurvivesSettingsAndFullBackupRoundTrips() {
        for (scopes in listOf(setOf(BackupFormat.Scope.SETTING), BackupFormat.Scope.entries.toSet())) {
            for (hidden in listOf(true, false)) {
                val exported = BackupFormat.exportPrefs(mapOf("hide_from_recents" to hidden), scopes)
                val imported = BackupFormat.importPrefs(exported, scopes)
                assertEquals(mapOf("hide_from_recents" to hidden), imported.values)
                assertEquals(emptyList<String>(), imported.skipped)
            }
        }
    }

    @Test
    fun oldBackupsLeaveRecentsUnchangedAndMistypedValuesAreSkipped() {
        val old = BackupFormat.importPrefs(buildJsonObject { put("auto_start", true) }, setOf(BackupFormat.Scope.SETTING))
        assertFalse(old.values.containsKey("hide_from_recents"))
        val mistyped = BackupFormat.importPrefs(buildJsonObject { put("hide_from_recents", JsonPrimitive("true")) }, setOf(BackupFormat.Scope.SETTING))
        assertFalse(mistyped.values.containsKey("hide_from_recents"))
        assertEquals(listOf("hide_from_recents"), mistyped.skipped)
    }
}
