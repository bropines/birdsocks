package io.github.bropines.birdsocks.ui.settings

import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * What every Settings section reads, and the two ways a change applies.
 *
 * App settings live in BirdSocks' own preferences and the daemon reads them
 * when it starts: [startSetting] saves one and raises the restart banner.
 * Profile settings live in NetBird's profile: [setConfig] writes them and
 * reconnects, which needs the daemon, so a section shows them only while
 * [config] is there.
 */
class SettingsEnv(
    val running: Boolean,
    val status: NbStatus?,
    /** The active NetBird profile; "default" until the daemon has said. */
    val profile: String,
    /** The profile's settings from the daemon, null while it is not running or has not answered. */
    val config: NbConfig?,
    val startSetting: (() -> Unit) -> Unit,
    val setConfig: (JsonObjectBuilder.() -> Unit) -> Unit,
)
