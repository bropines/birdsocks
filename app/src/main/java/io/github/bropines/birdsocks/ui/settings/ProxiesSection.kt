package io.github.bropines.birdsocks.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.NetAddr
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsEditItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.copyText
import io.github.bropines.birdsocks.ui.generateRandomString

/**
 * The proxy as a link SOCKS clients import (NekoBox, v2rayNG, SagerNet):
 * socks5://user:pass@host:port#BirdSocks. [host] is where the client is:
 * null for an app on this phone, the Wi-Fi address for another device.
 */
private fun socksLink(context: Context, host: String?): String {
    val user = GlobalSettings.getSocksUser(context)
    val pass = GlobalSettings.getSocksPass(context)
    // Both or neither, as the proxy itself takes them.
    val creds = if (user.isNotEmpty() && pass.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(pass)}@" else ""
    // Shared, the proxy listens on 0.0.0.0 and 127.0.0.1 reaches it; otherwise only its own loopback address does.
    val local = if (GlobalSettings.isSocksLanShared(context)) NetAddr.LOOPBACK_V4 else GlobalSettings.getSocksHost(context)
    return "socks5://$creds${host ?: local}:${GlobalSettings.getSocksPort(context)}#BirdSocks"
}

/** Settings → Local proxies: the SOCKS5 proxy, its link, and sharing it on the Wi-Fi last. */
@Composable
internal fun ProxiesSection(env: SettingsEnv) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var socksAddress by remember { mutableStateOf(GlobalSettings.getSocksAddress(context)) }
    var user by remember { mutableStateOf(GlobalSettings.getSocksUser(context)) }
    var pass by remember { mutableStateOf(GlobalSettings.getSocksPass(context)) }
    var lan by remember { mutableStateOf(GlobalSettings.isSocksLanShared(context)) }
    // The Wi-Fi, Ethernet or hotspot address. Not the NetBird one: the proxies
    // stay off the overlay (patch 07), so a peer could not use it there.
    val lanIp = remember(lan) { if (lan) NetAddr.lanIpv4() else null }

    // Android 13 and later confirm a copy themselves; older ones get a toast.
    fun copyLink(host: String?) {
        clipboard.copyText(scope, socksLink(context, host))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, context.getString(R.string.settings_sagernet_copied), Toast.LENGTH_SHORT).show()
    }

    SettingsCard(stringResource(R.string.nb_socks_title), note = stringResource(R.string.settings_note_restart)) {
        SettingsEditItem(stringResource(R.string.nb_settings_socks_address), socksAddress, Icons.Default.SettingsEthernet,
            placeholder = GlobalSettings.DEFAULT_SOCKS_ADDRESS, description = stringResource(R.string.nb_settings_loopback_desc),
            onAction = { GlobalSettings.randomLoopback(GlobalSettings.getSocksPort(context)) }, actionIcon = Icons.Default.Casino
        ) { v ->
            if (!GlobalSettings.isLoopbackAddress(v.trim())) {
                Toast.makeText(context, context.getString(R.string.nb_settings_bad_loopback), Toast.LENGTH_SHORT).show()
            } else env.startSetting { GlobalSettings.setSocksAddress(context, v); socksAddress = GlobalSettings.getSocksAddress(context) }
        }
        SettingsEditItem(stringResource(R.string.nb_settings_socks_user), user, Icons.Default.Person,
            description = stringResource(R.string.nb_settings_socks_auth_desc),
            onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino
        ) { env.startSetting { GlobalSettings.setSocksUser(context, it.trim()); user = it.trim() } }
        SettingsEditItem(stringResource(R.string.nb_settings_socks_pass), if (pass.isEmpty()) "" else "••••••••", Icons.Default.Password,
            description = stringResource(R.string.nb_settings_socks_auth_desc),
            onAction = { generateRandomString(16) }, actionIcon = Icons.Default.Casino
        ) { env.startSetting { GlobalSettings.setSocksPass(context, it.trim()); pass = it.trim() } }
        // Authentication takes both fields or neither; one alone is ignored,
        // and saying so here beats a proxy that quietly accepts anyone while
        // the user believes it is locked.
        if (user.isBlank() != pass.isBlank()) {
            Text(
                stringResource(R.string.settings_socks5_auth_needs_both),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!lan) {
            OutlinedButton(onClick = { copyLink(null) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_sagernet_copy))
            }
        } else {
            // Open to the network: the link is for another device, so it
            // carries the address that device can reach.
            Text(
                stringResource(R.string.settings_sagernet_copy_for),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyLink(null) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.settings_sagernet_copy_local), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { lanIp?.let { copyLink(it) } }, enabled = lanIp != null, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.settings_lan_endpoint_lan), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    // Last in the section on purpose: the one switch here that opens the
    // proxy above to anyone on the same Wi-Fi.
    SettingsCard(stringResource(R.string.settings_sect_lan), note = stringResource(R.string.settings_note_restart)) {
        SettingsSwitchItem(
            stringResource(R.string.nb_settings_socks_lan),
            if (lan) stringResource(R.string.settings_lan_access_listening) else stringResource(R.string.nb_settings_socks_lan_desc),
            Icons.Default.Wifi,
            lan
        ) { env.startSetting { GlobalSettings.setSocksLanShared(context, it); lan = it } }

        if (lan) {
            val copiedFmt = stringResource(R.string.settings_lan_copied)
            LanEndpoints(
                networks = listOf(stringResource(R.string.settings_lan_endpoint_lan) to lanIp),
                ports = listOf("SOCKS5" to GlobalSettings.getSocksPort(context)),
                onCopy = { text ->
                    clipboard.copyText(scope, text)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copiedFmt.format(text), Toast.LENGTH_SHORT).show()
                }
            )
            if (user.isEmpty() || pass.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), MaterialTheme.shapes.small)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.settings_lan_access_no_auth_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * The proxy's addresses while it is shared: one block per network with the
 * host on its own line and a chip per port. Tapping the host copies it,
 * tapping a chip copies host:port.
 */
@Composable
private fun LanEndpoints(
    networks: List<Pair<String, String?>>,
    ports: List<Pair<String, Int>>,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((label, host) in networks) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (host == null) {
                        Text(
                            stringResource(R.string.settings_lan_endpoint_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onCopy(host) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                host,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(vertical = 2.dp)
                            )
                            Icon(
                                Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy),
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((name, port) in ports) {
                                AssistChip(
                                    onClick = { onCopy("$host:$port") },
                                    label = { Text("$name  :$port", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) },
                                    trailingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
