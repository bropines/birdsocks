package io.github.bropines.birdsocks.core

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import appctr.Appctr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Process-wide entry point. */
class BirdSocksApp : Application() {
    companion object {
        /** Work that must outlive a screen — a sign-in waiting on the browser. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onCreate() {
        super.onCreate()
        // The log ring, mirrored to a file anyone debugging can read with
        // `adb shell run-as <package> cat files/logs/birdsocks.log`.
        runCatching { Appctr.setLogFile(java.io.File(filesDir, "logs/birdsocks.log").absolutePath) }
        // Go cannot find the device's zone on its own, and the log stamps it
        // writes would be UTC; kept current when the user travels.
        applyTimeZone()
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = applyTimeZone()
        }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        // STATUS_CHANGED for the app automation named, when it named one.
        Automation.watch(this)
        // The enabled launcher alias, back in line with the picked icon should
        // they disagree: a restore, an update that dropped an icon, a crash
        // between writing the preference and switching.
        scope.launchIO { AppIcons.reconcile(this@BirdSocksApp) }
    }

    private fun applyTimeZone() {
        runCatching { Appctr.setTimeZone(TimeZone.getDefault().id) }
    }
}

fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job = launch(Dispatchers.IO, block = block)
