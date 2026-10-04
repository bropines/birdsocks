package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.PredictiveBackContainer
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.ui.settings.*
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * One row of the settings hub. [id] is what `openSection` stores (one of
 * [SettingsSections]), so it is a stable string and survives a `recreate()`.
 */
private data class SettingsCategory(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    val icon: ImageVector
)

/** The hub, in order. Tunnel mode sits after the account, as in TailSocks, once it exists. */
private val settingsCategories = listOfNotNull(
    SettingsCategory(SettingsSections.APPEARANCE, R.string.settings_cat_appearance, R.string.settings_cat_appearance_desc, Icons.Default.Palette),
    SettingsCategory(SettingsSections.ACCOUNT, R.string.settings_cat_account, R.string.settings_cat_account_desc, Icons.Default.AccountCircle),
    SettingsCategory(SettingsSections.TUNNEL, R.string.settings_cat_tunnel, R.string.settings_cat_tunnel_desc, Icons.Default.VpnLock)
        .takeIf { TunnelSection.AVAILABLE },
    SettingsCategory(SettingsSections.PROXIES, R.string.settings_cat_proxies, R.string.settings_cat_proxies_desc, Icons.Default.Lan),
    SettingsCategory(SettingsSections.DNS, R.string.settings_cat_dns, R.string.settings_cat_dns_desc, Icons.Default.Dns),
    SettingsCategory(SettingsSections.ACCESS, R.string.settings_cat_access, R.string.settings_cat_access_desc, Icons.Default.Shield),
    SettingsCategory(SettingsSections.CONNECTION, R.string.settings_cat_connection, R.string.settings_cat_connection_desc, Icons.Default.Cable),
    SettingsCategory(SettingsSections.BACKGROUND, R.string.settings_cat_background, R.string.settings_cat_background_desc, Icons.Default.Bolt),
    SettingsCategory(SettingsSections.DIAGNOSTICS, R.string.settings_cat_diagnostics, R.string.settings_cat_diagnostics_desc, Icons.Default.BugReport),
    SettingsCategory(SettingsSections.ABOUT, R.string.nb_settings_about, R.string.settings_cat_about_desc, Icons.Default.Info)
)

class SettingsActivity : ComponentActivity() {
    companion object {
        /** Open on this section instead of the hub: one of [SettingsSections]. */
        const val EXTRA_OPEN_SECTION = "open_section"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialSection = intent?.getStringExtra(EXTRA_OPEN_SECTION)
        setContent {
            val context = LocalContext.current
            var appTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
            var themePreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
            var dynamicColor by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }
            var amoled by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }
            BirdSocksTheme(appTheme = appTheme, themePreset = themePreset, dynamicColorEnabled = dynamicColor, amoledModeEnabled = amoled) {
                SettingsScreen(
                    onBack = { finish() },
                    initialSection = initialSection,
                    appearance = Appearance(
                        theme = appTheme, onTheme = { appTheme = it; GlobalSettings.setAppTheme(context, it) },
                        preset = themePreset, onPreset = { themePreset = it; GlobalSettings.setThemePreset(context, it) },
                        dynamicColor = dynamicColor, onDynamicColor = { dynamicColor = it; GlobalSettings.setDynamicColorEnabled(context, it) },
                        amoled = amoled, onAmoled = { amoled = it; GlobalSettings.setBoolean(context, "amoled_mode", it) }
                    )
                )
            }
        }
    }
}

fun generateRandomString(length: Int = 12): String {
    val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length).map { allowed.random() }.joinToString("")
}

/**
 * Where a settings list was left. Deliberately not snapshot state: it is read once, when a
 * list is composed, and written on every scrolled frame — as state it would recompose the
 * very list that is scrolling. The settings surface is composed more than once at a time
 * (the open section, the hub the back gesture uncovers underneath it, and for an instant
 * both hubs as the pop lands), so each instance restores the position from here rather than
 * sharing one LazyListState between two live LazyColumns.
 */
private class ScrollAnchor(var index: Int = 0, var offset: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, appearance: Appearance, initialSection: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daemon by NetbirdState.daemon.collectAsState()
    val status by NetbirdState.status.collectAsState()
    val profile by NetbirdState.profile.collectAsState()
    val running = daemon == NetbirdState.Daemon.Running

    // Two-level navigation: null is the hub, otherwise the id of the open section.
    // rememberSaveable, because the language row calls recreate() and a plain
    // remember would drop the user back to the hub mid-edit. A link may name a
    // section this build does not show (Tunnel mode before it lands): the hub then.
    var openSection by rememberSaveable {
        mutableStateOf(initialSection?.takeIf { id -> settingsCategories.any { it.id == id } })
    }
    // Scroll positions of the two levels, kept outside the surfaces that draw them —
    // see ScrollAnchor. Without this the hub the finger uncovers during a back gesture is
    // a fresh LazyColumn at the top, and so is the one the pop lands on.
    val hubAnchor = remember { ScrollAnchor() }
    val sectionAnchors = remember { mutableMapOf<String, Int>() }

    // App settings the daemon reads when it starts: changing one while it runs
    // takes a restart, which the banner offers.
    var restartNeeded by rememberSaveable { mutableStateOf(false) }
    val startSetting: (() -> Unit) -> Unit = { block ->
        block()
        if (daemon != NetbirdState.Daemon.Stopped) restartNeeded = true
    }

    // The profile's settings, from the daemon; null until it answers, and read
    // again when the account sheet switches profiles.
    var config by remember { mutableStateOf<NbConfig?>(null) }
    LaunchedEffect(running, profile) { config = if (running) runCatching { Netbird.config() }.getOrNull() else null }
    val setConfig: (JsonObjectBuilder.() -> Unit) -> Unit = { fields ->
        scope.launch {
            runCatching {
                Netbird.setConfig(fields)
                config = Netbird.config()
                // Saved to the profile; the engine reads it when it connects.
                Netbird.reconnect()
            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    val env = SettingsEnv(running, status, profile ?: "default", config, startSetting, setConfig)

    // Back means "up one level": out of an open section to the hub, and only from
    // the hub out of the Activity. The toolbar arrow keeps the crossfade below;
    // the gesture draws its own transition and sets [poppedByGesture] so the
    // crossfade stands down instead of replaying a pop the finger already showed.
    var poppedByGesture by remember { mutableStateOf(false) }
    val popSection: () -> Unit = { openSection = null }
    val popSectionByGesture: () -> Unit = {
        poppedByGesture = true
        openSection = null
    }
    // Armed for exactly one transition. The reset runs after the composition that
    // consumed it, and transitionSpec is only consulted when the target changes,
    // so it cannot cancel the transition it just configured.
    LaunchedEffect(openSection) { poppedByGesture = false }

    // One whole rendering of the screen at a given level: the hub when [section] is
    // null, that section's page otherwise. It takes the level as a parameter instead
    // of reading `openSection` because the back gesture needs two levels on screen at
    // once — the section being dragged away, and the hub coming back underneath it.
    // backable: whether a section shows the arrow that returns to the hub. Beside the
    // hub, as the right pane of the wide layout, there is nothing to return to.
    // banner: whether this copy carries the restart banner; in the wide layout only
    // the right pane does, so it is not shown twice.
    val settingsSurface: @Composable (String?, Boolean, Boolean) -> Unit = { section, backable, banner ->
        val openCategory = settingsCategories.firstOrNull { it.id == section }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                AppTopBar(
                    title = stringResource(openCategory?.titleRes ?: R.string.menu_settings),
                    onBack = if (section == null || backable) {
                        { if (section != null) popSection() else onBack() }
                    } else null
                )
            }
        ) { padding ->
            if (section == null) {
                val hubState = rememberLazyListState(hubAnchor.index, hubAnchor.offset)
                // Every hub instance starts where the last one was left and writes back
                // where it is now, so the copy under the finger, the copy on top of it and
                // the copy that lands are all at the same place in the list.
                LaunchedEffect(hubState) {
                    snapshotFlow { hubState.firstVisibleItemIndex to hubState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            hubAnchor.index = index
                            hubAnchor.offset = offset
                        }
                }
                ReadableWidth {
                    LazyColumn(
                        state = hubState,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        if (banner && restartNeeded) item(key = "restart") { RestartBanner { restartNeeded = false } }
                        items(settingsCategories, key = { it.id }) { category ->
                            SettingsClickableItem(
                                title = stringResource(category.titleRes),
                                subtitle = stringResource(category.descRes),
                                icon = category.icon,
                                onClick = { openSection = category.id }
                            )
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }
            } else {
                // Hoisted for the same reason as the hub's: the back container swaps its own
                // structure the moment a pop lands, and a section that jumps back to the top
                // while it is still fading out is worse than the pop it is showing.
                val sectionScroll = rememberScrollState(sectionAnchors[section] ?: 0)
                LaunchedEffect(sectionScroll, section) {
                    snapshotFlow { sectionScroll.value }.collect { sectionAnchors[section] = it }
                }
                ReadableWidth {
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(sectionScroll)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (banner && restartNeeded) RestartBanner { restartNeeded = false }
                        when (section) {
                            SettingsSections.APPEARANCE -> AppearanceSection(appearance)
                            SettingsSections.ACCOUNT -> AccountSection(env)
                            // TUN: the section's content is the TUN work's (ui/TunnelSection.kt).
                            SettingsSections.TUNNEL -> TunnelSettings()
                            SettingsSections.PROXIES -> ProxiesSection(env)
                            SettingsSections.DNS -> DnsSection(env)
                            SettingsSections.ACCESS -> AccessSection(env)
                            SettingsSections.CONNECTION -> ConnectionSection(env)
                            SettingsSections.BACKGROUND -> BackgroundSection()
                            SettingsSections.DIAGNOSTICS -> DiagnosticsSection(env)
                            SettingsSections.ABOUT -> AboutSection(env)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    // The crossfade between the hub and a section: Material 3's own *default effects*
    // spring. Read here rather than inside `transitionSpec`, which is not a composable
    // lambda and so cannot reach `MaterialTheme` itself.
    val sectionFadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // Wide windows — a tablet, a phone on its side — show the hub and a section at
    // once: the list stays put and the right side follows it. Nothing to push or pop
    // there, so none of the transition or predictive-back machinery is involved; back
    // simply leaves, as it does from the hub. A foldable held open like a book gets
    // the two panes on its two halves whatever its width, with the hinge as the divider.
    val fold = rememberFold()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp || fold is Fold.Vertical) {
            val shown = openSection ?: settingsCategories.first().id
            Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (fold is Fold.Vertical) {
                    Box(modifier = Modifier.width(fold.start).fillMaxHeight()) { settingsSurface(null, true, false) }
                    Spacer(modifier = Modifier.width(fold.end - fold.start))
                } else {
                    Box(modifier = Modifier.width(340.dp).fillMaxHeight()) { settingsSurface(null, true, false) }
                    VerticalDivider()
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { settingsSurface(shown, false, true) }
            }
        } else {
            // The crossfade sits outside the back container, so that opening a section
            // still fades and each level keeps its own container: the one drawn for a
            // section installs the gesture, the one drawn for the hub does not.
            AnimatedContent(
                targetState = openSection,
                transitionSpec = {
                    if (poppedByGesture) {
                        // The finger already played this one: the section shrank away and
                        // the hub came back up underneath it. Fading them into each other
                        // on top of that would show the section again, full size.
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(sectionFadeSpec) togetherWith fadeOut(sectionFadeSpec)
                    }
                },
                label = "settings_section",
                // The two levels overlap while they crossfade, and half-transparent over
                // half-transparent would let the window background show through between
                // them; this is the ground they fade over.
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) { section ->
                PredictiveBackContainer(
                    // Only the section pop is ours to draw. On the hub back leaves the
                    // Activity, so nothing is intercepted there and the platform keeps the
                    // gesture and its real cross-activity animation. openSection as well as
                    // this slot's own section: AnimatedContent keeps the outgoing slot alive
                    // for the whole exit fade, and a handler keyed on its section alone stayed
                    // armed on a screen already leaving, swallowing a back that should close
                    // the Activity.
                    onBack = if (section != null && openSection != null) popSectionByGesture else null,
                    modifier = Modifier.fillMaxSize(),
                    // What the finger uncovers is the real hub — for the eye only: it is a
                    // second live copy of every row, and a screen reader reaches the hub
                    // through the one that lands.
                    previousContent = {
                        Box(Modifier.fillMaxSize().clearAndSetSemantics { }) { settingsSurface(null, true, true) }
                    }
                ) {
                    settingsSurface(section, true, true)
                }
            }
        }
    }
}

/** A start option changed while the daemon runs: it applies once BirdSocks restarts. */
@Composable
private fun RestartBanner(onRestart: () -> Unit) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nb_settings_restart_needed), Modifier.weight(1f))
            FilledTonalButton(onClick = {
                onRestart()
                NetbirdService.restart(context)
            }) { Text(stringResource(R.string.nb_settings_restart)) }
        }
    }
}
