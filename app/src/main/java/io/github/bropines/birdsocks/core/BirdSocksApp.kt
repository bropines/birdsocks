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
        // Go cannot find the device's zone on its own, and the log stamps it
        // writes would be UTC; kept current when the user travels.
        applyTimeZone()
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = applyTimeZone()
        }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    private fun applyTimeZone() {
        runCatching { Appctr.setTimeZone(TimeZone.getDefault().id) }
    }
}

fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job = launch(Dispatchers.IO, block = block)
