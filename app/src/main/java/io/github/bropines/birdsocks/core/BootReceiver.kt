package io.github.bropines.birdsocks.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the service back after a reboot (when "start on boot" is on) and
 * after an update (whenever it was running before: the install killed it,
 * not the user).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val replaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val boot = action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON"
        if (!replaced && !boot) return
        val wanted = GlobalSettings.wasRunning(context) && (replaced || GlobalSettings.isAutoStartEnabled(context))
        Log.i("BootReceiver", "$action: start=$wanted")
        if (!wanted) return
        try {
            NetbirdService.start(context)
        } catch (e: Exception) {
            // Android 12+ and OEM skins can refuse a background start; the next app launch retries.
            Log.e("BootReceiver", "start refused: ${e.message}")
        }
    }
}
