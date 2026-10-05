package io.github.bropines.birdsocks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.birdsocks.ui.settings.Appearance

/*
 * The screenshots in the README and the store listings, rendered from the
 * made-up network in Showcase.kt, once in English and once in Russian.
 * docs/screenshots/ holds the copies that are published; regenerate with
 *
 *   ./gradlew :app:updateDebugScreenshotTest
 *   python3 scripts/readme_shots.py
 *
 * Preview names may not contain dots: the file name is cut at the first one.
 */

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"
private const val PHONE_LANDSCAPE = "spec:width=891dp,height=411dp,dpi=420"
private const val TABLET = "spec:width=1280dp,height=800dp,dpi=240"

private val appearance = Appearance(
    theme = "dark", onTheme = {},
    preset = "amber", onPreset = {},
    dynamicColor = false, onDynamicColor = {},
    amoled = false, onAmoled = {},
)

@Composable
private fun Settings(section: String? = null) =
    SettingsScreen(onBack = {}, appearance = appearance, initialSection = section)

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeMain() = Showcase { MainScreen() }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeMainLight() = Showcase(light = true) { MainScreen() }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmePeers() = Showcase { PeersScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmePeersLight() = Showcase(light = true) { PeersScreen(onBack = {}) }

/** The sheet draws in a window of its own, which the renderer keeps apart: readme_shots.py lays it over ReadmePeers. */
@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmePeerSheet() = Showcase(DemoNet.data.copy(openPeer = DemoNet.VPS_FRA_KEY)) { PeersScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeNetworks() = Showcase { NetworksScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeDns() = Showcase(DemoNet.data.copy(firstItem = 2)) { DnsScreen(onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeDiagnostics() = Showcase { DiagnosticsScreen(DiagnosticsActivity.PAGE_CONNECTION, onBack = {}) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeSettings() = Showcase { Settings() }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeConnection() = Showcase { Settings(SettingsSections.CONNECTION) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeVpn() = Showcase(DemoNet.vpnData, DemoNet.vpnPrefs) { Settings(SettingsSections.TUNNEL) }

@PreviewTest
@Preview(name = "en", device = PHONE, locale = "en")
@Preview(name = "ru", device = PHONE, locale = "ru")
@Composable
fun ReadmeAddAccount() = Showcase {
    MainScreen()
    AddAccountDialog(
        existing = DemoNet.accounts.map { it.profile.name } + "default",
        onDismiss = {},
        initialServer = "https://netbird.homelab.example",
        onDone = { _, _, _ -> }
    )
}

@PreviewTest
@Preview(name = "en", device = PHONE_LANDSCAPE, locale = "en")
@Preview(name = "ru", device = PHONE_LANDSCAPE, locale = "ru")
@Composable
fun ReadmeSettingsWide() = Showcase { Settings(SettingsSections.ACCOUNT) }

@PreviewTest
@Preview(name = "en", device = TABLET, locale = "en")
@Preview(name = "ru", device = TABLET, locale = "ru")
@Composable
fun ReadmeTablet() = Showcase { Settings(SettingsSections.CONNECTION) }
