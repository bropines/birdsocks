package io.github.bropines.birdsocks.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Automation
import io.github.bropines.birdsocks.core.CompactTextField
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.openUrl

/**
 * Settings → Automation: the switch and the token that broadcasts and
 * token-carrying links need (core/Automation). Off by default; without a
 * token of [Automation.MIN_TOKEN_LENGTH] characters everything is refused.
 */
@Composable
internal fun AutomationSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Automation.isEnabled(context)) }
    var token by remember { mutableStateOf(Automation.token(context)) }
    var statusTo by remember { mutableStateOf(Automation.statusReceiver(context)) }

    fun saveToken(value: String) {
        token = value
        Automation.setToken(context, value)
        statusTo = Automation.statusReceiver(context)
    }

    SettingsCard(stringResource(R.string.automation_title)) {
        SettingsSwitchItem(stringResource(R.string.automation_enable), stringResource(R.string.automation_enable_desc), Icons.Default.SmartButton, enabled) {
            Automation.setEnabled(context, it)
            enabled = it
            statusTo = Automation.statusReceiver(context)
        }
        if (enabled) {
            CompactTextField(
                value = token,
                onValueChange = { saveToken(it.trim()) },
                label = stringResource(R.string.automation_token),
                leadingIcon = { Icon(Icons.Default.Key, null) },
                trailingIcon = if (token.isNotEmpty()) {
                    { IconButton(onClick = { saveToken("") }) { Icon(Icons.Default.Clear, stringResource(R.string.automation_token_clear)) } }
                } else null,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { saveToken(Automation.generateToken()) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Casino, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_generate), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { copySecret(context, token) },
                    enabled = token.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val usable = Automation.isTokenUsable(token)
            HelpText(
                if (usable) stringResource(R.string.automation_token_desc) else stringResource(R.string.automation_token_missing, Automation.MIN_TOKEN_LENGTH),
                Modifier.padding(top = 8.dp),
                color = if (usable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
            if (statusTo.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    HelpText(stringResource(R.string.automation_status_to, statusTo), Modifier.weight(1f))
                    TextButton(onClick = { Automation.setStatusReceiver(context, ""); statusTo = "" }) {
                        Text(stringResource(R.string.automation_status_stop))
                    }
                }
            }
        }
        val docs = stringResource(R.string.automation_docs_url)
        SettingsClickableItem(stringResource(R.string.automation_docs), stringResource(R.string.automation_docs_desc), Icons.AutoMirrored.Filled.MenuBook) {
            openUrl(context, docs)
        }
    }
}

/** To the clipboard, marked sensitive: Android 13+ then hides it in the "Copied" preview. */
private fun copySecret(context: Context, secret: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.automation_token), secret)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    // Android 13+ shows its own "Copied" overlay; a toast would double it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, context.getString(R.string.automation_token)), Toast.LENGTH_SHORT).show()
    }
}
