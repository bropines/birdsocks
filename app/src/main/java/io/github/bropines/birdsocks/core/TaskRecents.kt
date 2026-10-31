package io.github.bropines.birdsocks.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle

/** Changes task visibility only; activities and background services stay alive. */
internal object TaskRecents {
    fun watch(app: Application) {
        // Every entry point and every task follows the saved recents preference.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = apply(activity)
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun apply(context: Context) {
        val hidden = GlobalSettings.isHideFromRecents(context)
        for (task in context.getSystemService(ActivityManager::class.java).appTasks) {
            task.setExcludeFromRecents(hidden)
        }
    }
}
