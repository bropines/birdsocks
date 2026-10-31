package io.github.bropines.birdsocks.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 35])
class TaskRecentsTest {
    private lateinit var app: Application
    private lateinit var manager: ActivityManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("global_settings", Context.MODE_PRIVATE).edit().clear().commit()
        manager = app.getSystemService(ActivityManager::class.java)
    }

    @Test
    fun toggleUpdatesEveryTaskWithoutFinishingActivities() {
        val tasks = listOf(ShadowAppTask.newInstance(), ShadowAppTask.newInstance())
        shadowOf(manager).setAppTasks(tasks)
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            GlobalSettings.setHideFromRecents(app, true)
            assertTrue(GlobalSettings.isHideFromRecents(controller.get()))
            tasks.forEach { assertTrue(shadowOf(it).isExcludedFromRecents) }
            assertFalse(controller.get().isFinishing)

            GlobalSettings.setHideFromRecents(app, false)
            tasks.forEach {
                assertFalse(shadowOf(it).isExcludedFromRecents)
                assertFalse(shadowOf(it).isFinishedAndRemoved)
            }
            assertFalse(controller.get().isFinishing)
        }
    }

    @Test
    fun restoreUpdatesExistingTasksImmediately() = runBlocking {
        val task = ShadowAppTask.newInstance()
        shadowOf(manager).setAppTasks(listOf(task))
        Backup.restoreSettings(app, buildJsonObject { put("hide_from_recents", true) }, secrets = false)
        assertTrue(shadowOf(task).isExcludedFromRecents)
        Backup.restoreSettings(app, buildJsonObject { put("hide_from_recents", false) }, secrets = false)
        assertFalse(shadowOf(task).isExcludedFromRecents)
    }

    @Test
    fun activityStartAppliesSavedPreferenceToNewTasks() {
        // Persist before registering the process's lifecycle policy, as on a cold launch.
        GlobalSettings.setBoolean(app, "hide_from_recents", true)
        TaskRecents.watch(app)
        val first = ShadowAppTask.newInstance()
        shadowOf(manager).setAppTasks(listOf(first))
        Robolectric.buildActivity(Activity::class.java).setup().use {
            assertTrue(shadowOf(first).isExcludedFromRecents)
            val next = ShadowAppTask.newInstance()
            shadowOf(manager).setAppTasks(listOf(first, next))
            Robolectric.buildActivity(Activity::class.java).setup().use {
                assertTrue(shadowOf(next).isExcludedFromRecents)
                GlobalSettings.setHideFromRecents(app, false)
                assertFalse(shadowOf(first).isExcludedFromRecents)
                assertFalse(shadowOf(next).isExcludedFromRecents)
            }
        }
    }
}
