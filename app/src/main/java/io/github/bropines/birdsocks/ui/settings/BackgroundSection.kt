package io.github.bropines.birdsocks.ui.settings

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.ui.PermissionsActivity
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem

/** Settings → Background & permissions. Each of these applies at once. */
@Composable
internal fun BackgroundSection() {
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.nb_settings_background)) {
        // The one place for this switch: the events page reads it, it does not offer it.
        var eventNotes by remember { mutableStateOf(GlobalSettings.isEventNotifications(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_settings_event_notifications), stringResource(R.string.nb_settings_event_notifications_desc), Icons.Default.NotificationsActive, eventNotes) {
            GlobalSettings.setEventNotifications(context, it); eventNotes = it
        }
        var autoStart by remember { mutableStateOf(GlobalSettings.isAutoStartEnabled(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_settings_boot), stringResource(R.string.nb_settings_boot_desc), Icons.Default.RestartAlt, autoStart) {
            GlobalSettings.setAutoStartEnabled(context, it); autoStart = it
        }
        SettingsClickableItem(stringResource(R.string.nb_settings_permissions), stringResource(R.string.nb_settings_permissions_desc), Icons.Default.BatteryChargingFull) {
            context.startActivity(Intent(context, PermissionsActivity::class.java))
        }
    }
}
