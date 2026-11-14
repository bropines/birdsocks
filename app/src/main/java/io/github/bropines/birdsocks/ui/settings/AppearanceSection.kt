package io.github.bropines.birdsocks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsSwitchItem

/** The theme the Activity draws with, and how to change it; held by the Activity so a change repaints at once. */
class Appearance(
    val theme: String, val onTheme: (String) -> Unit,
    val preset: String, val onPreset: (String) -> Unit,
    val dynamicColor: Boolean, val onDynamicColor: (Boolean) -> Unit,
    val amoled: Boolean, val onAmoled: (Boolean) -> Unit
)

data class PresetItem(val id: String, val color: Color, val name: String)

/** Settings → Appearance & language. */
@Composable
internal fun AppearanceSection(a: Appearance) {
    SettingsCard(stringResource(R.string.settings_sect_personalization)) {
        val themes = listOf(
            Triple("system", Icons.Default.Settings, stringResource(R.string.settings_theme_system)),
            Triple("light", Icons.Default.LightMode, stringResource(R.string.settings_theme_light)),
            Triple("dark", Icons.Default.DarkMode, stringResource(R.string.settings_theme_dark))
        )
        Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            items = themes.map { SegmentedChipItem(it.third, it.second) },
            selectedIndex = themes.indexOfFirst { it.first == a.theme }.coerceAtLeast(0),
            onOptionSelected = { a.onTheme(themes[it].first) },
            modifier = Modifier.fillMaxWidth(),
            height = 38.dp
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.settings_lang_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LanguagePicker()

        if (!a.dynamicColor || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            val presets = listOf(
                PresetItem("default", Color(0xFF6750A4), stringResource(R.string.settings_preset_default)),
                PresetItem("lavender", Color(0xFF704E9B), stringResource(R.string.settings_preset_lavender)),
                PresetItem("emerald", Color(0xFF006B54), stringResource(R.string.settings_preset_emerald)),
                PresetItem("sapphire", Color(0xFF005FAF), stringResource(R.string.settings_preset_sapphire)),
                PresetItem("amber", Color(0xFF825500), stringResource(R.string.settings_preset_amber)),
                PresetItem("monochrome", Color(0xFF1D2023), stringResource(R.string.settings_preset_monochrome)),
                PresetItem("tokionight", Color(0xFF7AA2F7), stringResource(R.string.settings_preset_tokionight))
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_palette_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                presets.forEach { item ->
                    val selected = a.preset == item.id
                    Box(
                        Modifier.size(36.dp).background(item.color, CircleShape).clickable { a.onPreset(item.id) }
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(12.dp))
            SettingsSwitchItem(stringResource(R.string.settings_dynamic_color_title), stringResource(R.string.settings_dynamic_color_desc), Icons.Default.Palette, a.dynamicColor, onCheckedChange = a.onDynamicColor)
        }
        SettingsSwitchItem(stringResource(R.string.settings_amoled_black_title), stringResource(R.string.settings_amoled_black_desc), Icons.Default.Contrast, a.amoled, onCheckedChange = a.onAmoled)
        AppIconRow()
    }
}
