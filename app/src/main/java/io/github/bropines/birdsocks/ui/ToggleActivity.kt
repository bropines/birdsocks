package io.github.bropines.birdsocks.ui

import android.app.Activity
import android.os.Bundle
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState

/** Invisible: toggles the service from a shortcut and closes. */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) NetbirdService.start(this) else NetbirdService.stop(this)
        finish()
    }
}
