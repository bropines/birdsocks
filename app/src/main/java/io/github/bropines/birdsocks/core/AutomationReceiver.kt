package io.github.bropines.birdsocks.core

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tasker, MacroDroid and adb: `io.github.bropines.birdsocks.action.*`
 * broadcasts, each carrying the automation token in `secret` (or `token`,
 * `key`). Exported with no permission, so it refuses everything until
 * Settings → Automation is on with a token, and then whatever does not carry
 * it; [Automation] reads the rest and does the work.
 *
 * An ordered broadcast (`adb shell am broadcast` is one) gets the outcome
 * back: result -1 and a message when it went, 1 when it did not; GET_STATUS
 * also the status extras.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // A sender chooses the extras: one carrying a class this app does not
        // have throws when the bundle is read, and must not take the app down.
        val (secret, command) = runCatching {
            (intent.getStringExtra("secret") ?: intent.getStringExtra("token") ?: intent.getStringExtra("key")) to Automation.commandOf(intent)
        }.getOrElse { null to null }
        // For the log only, and the sender chose it: short and printable.
        val name = intent.action?.removePrefix(Automation.ACTION_PREFIX)?.take(40)?.filterNot { it.isISOControl() } ?: "?"

        Automation.refusal(app, secret)?.let { refusal ->
            logRefusal("$name refused: " + when (refusal) {
                Automation.Refusal.DISABLED -> "automation is off in Settings"
                Automation.Refusal.NO_TOKEN -> "no token is set in Settings"
                Automation.Refusal.BAD_TOKEN -> "wrong or missing token"
            })
            return
        }
        if (command == null) {
            Automation.log("WARN", "$name: unknown action or a missing or invalid extra")
            if (isOrderedBroadcast) setResult(RESULT_FAILED, "unknown action or a missing or invalid extra", null)
            return
        }

        val ordered = isOrderedBroadcast
        val pending = goAsync()
        BirdSocksApp.scope.launch {
            try {
                // A background broadcast has 60 s; the slow ones wait for a connection.
                val outcome = withTimeoutOrNull(40_000) { Automation.run(app, command) }
                    ?: Automation.Outcome(false, "timed out")
                Automation.log(if (outcome.ok) "INFO" else "WARN", "${Automation.describe(command)}: ${outcome.message}")
                if (ordered) pending.setResult(if (outcome.ok) Activity.RESULT_OK else RESULT_FAILED, outcome.message, outcome.extras)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RESULT_FAILED = 1

        /** One refusal line a second at most: a flood of wrong tokens would push the log out. */
        @Volatile var lastRefusal = 0L

        fun logRefusal(message: String) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefusal < 1000) return
            lastRefusal = now
            Automation.log("WARN", message)
        }
    }
}
