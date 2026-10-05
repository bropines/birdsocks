package io.github.bropines.birdsocks.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import appctr.Appctr
import io.github.bropines.birdsocks.R

/**
 * The launcher icon the user picked (Settings → Appearance). Each icon is an
 * `<activity-alias>` of MainActivity named `.ui.Launcher_<id>`, with the
 * launcher filter and the launcher shortcuts; exactly one is enabled. The
 * manifest enables the default and disables the rest, so a new install shows
 * the default before the app has ever run, and the PackageManager state the
 * app writes stays the least that differs from the manifest:
 *  - the default alias is DEFAULT (on) when picked and DISABLED otherwise;
 *  - any other alias is ENABLED when picked and DEFAULT (off) otherwise.
 *
 * The preference ([PREF]) says which one is meant; [reconcile] makes
 * PackageManager agree with it at every start, after a restore, after an
 * update that drops an icon, after a crash between the two writes.
 */
object AppIcons {
    const val PREF = "app_icon"
    const val DEFAULT_ID = "hop_r5light"

    /** Alias names resolve against the namespace, not the application id: the debug build's are the same. */
    private const val ALIAS_PREFIX = "io.github.bropines.birdsocks.ui.Launcher_"

    /** The picker's sections. */
    enum class Group(@StringRes val title: Int) {
        BIRDSOCKS(R.string.icon_group_birdsocks),
        NETBIRD_ON_ORANGE(R.string.icon_group_netbird_orange),
        NETBIRD_ORANGE_BIRD(R.string.icon_group_netbird_bird),
    }

    enum class Concept(@StringRes val title: Int) {
        WREN(R.string.icon_concept_wren),
        WIRE(R.string.icon_concept_wire),
        COURIER(R.string.icon_concept_courier),
        KNEE(R.string.icon_concept_knee),
        HOP(R.string.icon_concept_hop),
        SIT(R.string.icon_concept_sit),
        WINDSOCK(R.string.icon_concept_windsock),
        SNUG(R.string.icon_concept_snug),
    }

    /** A colour set, named after the design round it came from, as the resources are. */
    enum class Style(val group: Group, @StringRes val label: Int) {
        BS(Group.BIRDSOCKS, R.string.icon_style_bs),
        R4ORANGE(Group.NETBIRD_ON_ORANGE, R.string.icon_style_r4orange),
        R4DARK(Group.NETBIRD_ON_ORANGE, R.string.icon_style_r4dark),
        R5LIGHT(Group.NETBIRD_ORANGE_BIRD, R.string.icon_style_r5light),
        R5DARK(Group.NETBIRD_ORANGE_BIRD, R.string.icon_style_r5dark),
    }

    /** One icon: its alias's id and the two layers the picker draws it from. */
    class Variant(
        val id: String,
        val concept: Concept,
        val style: Style,
        @DrawableRes val background: Int,
        @DrawableRes val foreground: Int,
    )

    /** In the picker's order. Each id has an alias in the manifest and an ic_launcher_<id> mipmap. */
    val ALL: List<Variant> = listOf(
        Variant("wren_bs", Concept.WREN, Style.BS, R.drawable.ic_launcher_wren_bs_background, R.drawable.ic_launcher_wren_bs_foreground),
        Variant("wire_bs", Concept.WIRE, Style.BS, R.drawable.ic_launcher_wire_bs_background, R.drawable.ic_launcher_wire_bs_foreground),
        Variant("courier_bs", Concept.COURIER, Style.BS, R.drawable.ic_launcher_courier_bs_background, R.drawable.ic_launcher_courier_bs_foreground),
        Variant("knee_bs", Concept.KNEE, Style.BS, R.drawable.ic_launcher_knee_bs_background, R.drawable.ic_launcher_knee_bs_foreground),
        Variant("hop_bs", Concept.HOP, Style.BS, R.drawable.ic_launcher_hop_bs_background, R.drawable.ic_launcher_hop_bs_foreground),
        Variant("sit_bs", Concept.SIT, Style.BS, R.drawable.ic_launcher_sit_bs_background, R.drawable.ic_launcher_sit_bs_foreground),
        Variant("windsock_bs", Concept.WINDSOCK, Style.BS, R.drawable.ic_launcher_windsock_bs_background, R.drawable.ic_launcher_windsock_bs_foreground),
        Variant("snug_bs", Concept.SNUG, Style.BS, R.drawable.ic_launcher_snug_bs_background, R.drawable.ic_launcher_snug_bs_foreground),
        Variant("wren_r4orange", Concept.WREN, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_wren_r4orange_foreground),
        Variant("wren_r4dark", Concept.WREN, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_wren_r4dark_foreground),
        Variant("wire_r4orange", Concept.WIRE, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_wire_r4orange_foreground),
        Variant("wire_r4dark", Concept.WIRE, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_wire_r4dark_foreground),
        Variant("courier_r4orange", Concept.COURIER, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_courier_r4orange_foreground),
        Variant("courier_r4dark", Concept.COURIER, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_courier_r4dark_foreground),
        Variant("knee_r4orange", Concept.KNEE, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_knee_r4orange_foreground),
        Variant("knee_r4dark", Concept.KNEE, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_knee_r4dark_foreground),
        Variant("hop_r4orange", Concept.HOP, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_hop_r4orange_foreground),
        Variant("hop_r4dark", Concept.HOP, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_hop_r4dark_foreground),
        Variant("sit_r4orange", Concept.SIT, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_sit_r4orange_foreground),
        Variant("sit_r4dark", Concept.SIT, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_sit_r4dark_foreground),
        Variant("windsock_r4orange", Concept.WINDSOCK, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_windsock_r4orange_foreground),
        Variant("windsock_r4dark", Concept.WINDSOCK, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_windsock_r4dark_foreground),
        Variant("snug_r4orange", Concept.SNUG, Style.R4ORANGE, R.drawable.ic_launcher_orange_background, R.drawable.ic_launcher_snug_r4orange_foreground),
        Variant("snug_r4dark", Concept.SNUG, Style.R4DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_snug_r4dark_foreground),
        Variant("wren_r5light", Concept.WREN, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_wren_r5light_foreground),
        Variant("wren_r5dark", Concept.WREN, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_wren_r5dark_foreground),
        Variant("wire_r5light", Concept.WIRE, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_wire_r5light_foreground),
        Variant("wire_r5dark", Concept.WIRE, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_wire_r5dark_foreground),
        Variant("courier_r5light", Concept.COURIER, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_courier_r5light_foreground),
        Variant("courier_r5dark", Concept.COURIER, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_courier_r5dark_foreground),
        Variant("knee_r5light", Concept.KNEE, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_knee_r5light_foreground),
        Variant("knee_r5dark", Concept.KNEE, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_knee_r5dark_foreground),
        Variant("hop_r5light", Concept.HOP, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_hop_r5light_foreground),
        Variant("hop_r5dark", Concept.HOP, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_hop_r5dark_foreground),
        Variant("sit_r5light", Concept.SIT, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_sit_r5light_foreground),
        Variant("sit_r5dark", Concept.SIT, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_sit_r5dark_foreground),
        Variant("windsock_r5light", Concept.WINDSOCK, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_windsock_r5light_foreground),
        Variant("windsock_r5dark", Concept.WINDSOCK, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_windsock_r5dark_foreground),
        Variant("snug_r5light", Concept.SNUG, Style.R5LIGHT, R.drawable.ic_launcher_light_background, R.drawable.ic_launcher_snug_r5light_foreground),
        Variant("snug_r5dark", Concept.SNUG, Style.R5DARK, R.drawable.ic_launcher_dark_background, R.drawable.ic_launcher_snug_r5dark_foreground)
    )

    val DEFAULT: Variant = ALL.first { it.id == DEFAULT_ID }

    /** The icon the preference names; the default when it names none, or one this build dropped. */
    fun current(context: Context): Variant {
        val id = GlobalSettings.getString(context, PREF, DEFAULT_ID)
        return ALL.firstOrNull { it.id == id } ?: DEFAULT
    }

    /** Remembers [variant] and switches the launcher to it. Binder calls: not on the main thread. */
    fun select(context: Context, variant: Variant) {
        GlobalSettings.setString(context, PREF, variant.id)
        apply(context, variant)
    }

    /** Makes PackageManager show the icon the preference names. Cheap when they agree: reads only. */
    fun reconcile(context: Context) = apply(context, current(context))

    private fun component(context: Context, v: Variant) = ComponentName(context.packageName, ALIAS_PREFIX + v.id)

    private fun wanted(v: Variant, picked: Variant): Int = when {
        v === picked -> if (v === DEFAULT) COMPONENT_ENABLED_STATE_DEFAULT else COMPONENT_ENABLED_STATE_ENABLED
        else -> if (v === DEFAULT) COMPONENT_ENABLED_STATE_DISABLED else COMPONENT_ENABLED_STATE_DEFAULT
    }

    @Synchronized
    private fun apply(context: Context, picked: Variant) {
        val pm = context.packageManager
        val changes = ALL.mapNotNull { v ->
            val cn = component(context, v)
            val want = wanted(v, picked)
            runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull()?.takeIf { it != want }?.let { cn to want }
        }
        if (changes.isEmpty()) return
        // The new alias first: the package is never without a launcher entry,
        // which would make a launcher drop it from the home screen, and the
        // static shortcuts pass to the new alias under the same ids instead of
        // being removed, which would disable their pinned copies. DONT_KILL_APP:
        // the switch happens while the user looks at the picker.
        val ordered = changes.sortedBy { (cn, _) -> cn.className != ALIAS_PREFIX + picked.id }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // One call, one PACKAGE_CHANGED: the launcher redraws once.
                pm.setComponentEnabledSettings(ordered.map { (cn, state) ->
                    PackageManager.ComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
                })
            } else {
                for ((cn, state) in ordered) pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            }
            log("INFO", "Launcher icon: ${picked.id}")
        }.onFailure { log("ERROR", "Launcher icon ${picked.id} not applied: ${it.message}") }
    }

    private fun log(level: String, message: String) = runCatching { Appctr.logAndroid(level, "CORE", message) }
}
