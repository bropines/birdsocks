package io.github.bropines.birdsocks.ui

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The connection details moved to Diagnostics' first page (this device's own
 * to the Peers screen, the DNS servers to the DNS screen). This only forwards
 * there, so callers that still name it keep working until they are gone.
 */
class StatusDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(DiagnosticsActivity.intent(this))
        finish()
    }
}
