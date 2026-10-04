package io.github.bropines.birdsocks.ui

import android.app.Activity
import android.os.Bundle
import io.github.bropines.birdsocks.core.Automation

/**
 * Invisible: the launcher's On / Off shortcut (res/xml/shortcuts.xml). It
 * starts or stops the service and closes, with no question asked: it is not
 * exported, so only this app's own shortcut reaches it, and the user just
 * tapped that. A start in VPN mode asks for the VPN permission when needed.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Automation.runFromActivity(this, Automation.Command.Toggle)
        finish()
    }
}
