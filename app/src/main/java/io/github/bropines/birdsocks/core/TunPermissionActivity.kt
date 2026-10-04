package io.github.bropines.birdsocks.core

import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.bropines.birdsocks.R

/**
 * A see-through trampoline that asks for Android's VPN permission — from the
 * "VPN permission needed" notification or the tile, where the services may
 * not start an activity themselves. Granted, the tunnel comes up (now, or
 * with the daemon); refused, VPN mode is turned off so nothing asks again.
 */
class TunPermissionActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) granted() else denied()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the system dialog is already up
        val intent = VpnService.prepare(this)
        if (intent == null) {
            granted()
            finish()
        } else {
            runCatching { consent.launch(intent) }.onFailure {
                Log.w(TAG, "no VPN dialog: ${it.message}")
                denied()
                finish()
            }
        }
    }

    private fun granted() {
        Log.i(TAG, "VPN permission granted")
        TunVpnService.clearConsentNotice(this)
        GlobalSettings.setTunModeEnabled(this, true)
        TunVpnService.start(this)
    }

    private fun denied() {
        Log.w(TAG, "VPN permission denied")
        TunVpnService.clearConsentNotice(this)
        GlobalSettings.setTunModeEnabled(this, false)
        Toast.makeText(this, R.string.nb_tun_denied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "TunPermissionActivity"
    }
}
