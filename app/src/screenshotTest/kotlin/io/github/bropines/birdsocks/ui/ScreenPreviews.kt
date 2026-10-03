package io.github.bropines.birdsocks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

// Whole screens, rendered as they are. The renderer has no daemon and no
// native bridge, so each shows its empty or not-running state — which is
// exactly the state whose layout is easiest to get wrong and hardest to reach
// on a device where the service is up.

@PreviewTest @Geometries @Composable
fun MainScreenPreview() = BirdSocksTheme { MainScreen(showAccountSwitcher = remember { mutableStateOf(false) }) }

@PreviewTest @Geometries @Composable
fun PeersScreenPreview() = BirdSocksTheme { PeersScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun NetcheckScreenPreview() = BirdSocksTheme { NetcheckScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun DnsScreenPreview() = BirdSocksTheme { DnsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun LogsScreenPreview() = BirdSocksTheme { LogsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun ConsoleScreenPreview() = BirdSocksTheme { ConsoleScreen(initialCmd = "", onBack = {}) }

@PreviewTest @Geometries @Composable
fun FilesScreenPreview() = BirdSocksTheme { FilesScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun ServeScreenPreview() = BirdSocksTheme { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest @Geometries @Composable
fun TailcatScreenPreview() = BirdSocksTheme { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @Geometries @Composable
fun TaildriveScreenPreview() = BirdSocksTheme { TaildriveScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun PermissionsScreenPreview() = BirdSocksTheme { PermissionsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun TunExcludedAppsScreenPreview() = BirdSocksTheme { TunExcludedAppsScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun SettingsScreenPreview() = BirdSocksTheme {
    SettingsScreen(
        onBack = {},
        currentTheme = "system", onThemeChange = {},
        currentPreset = "default", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = false, onAmoledModeChange = {}
    )
}

@PreviewTest @Geometries @Composable
fun AdminApiScreenPreview() = BirdSocksTheme { io.github.bropines.birdsocks.admin.AdminApiMainScreen(onBack = {}) }

@PreviewTest @Geometries @Composable
fun FirstStartScreenPreview() = BirdSocksTheme { FirstStartScreen(onFinished = {}) }
