package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * Settings → Tunnel mode. The Settings hub shows this section only while
 * [AVAILABLE] is true and draws [TunnelSettings] inside it; the TUN work
 * owns this file.
 */
object TunnelSection {
    const val AVAILABLE = false
}

@Composable
fun ColumnScope.TunnelSettings() {
}
