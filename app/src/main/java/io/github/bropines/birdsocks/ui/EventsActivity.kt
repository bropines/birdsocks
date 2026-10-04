package io.github.bropines.birdsocks.ui

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The events moved to Diagnostics' second page. This only forwards there, so
 * callers that still name it keep working until they are gone.
 */
class EventsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(DiagnosticsActivity.intent(this, DiagnosticsActivity.PAGE_EVENTS))
        finish()
    }
}
