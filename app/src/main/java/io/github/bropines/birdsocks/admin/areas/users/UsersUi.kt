package io.github.bropines.birdsocks.admin.areas.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessToken
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessTokenRequest
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.copySecret
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.secure.SecureWindow
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Pieces the Users and Service users areas share: the role picker, a secret
 * shown once, and a user's personal access tokens.
 */

/** The current role as a button that opens the choices; [enabled] false shows it only. */
@Composable
internal fun RolePicker(current: String, choices: List<String>, enabled: Boolean, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(current.ifEmpty { "—" })
            if (enabled) Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r) },
                    onClick = { open = false; if (r != current) onPick(r) },
                    leadingIcon = if (r == current) ({ Icon(Icons.Default.Check, null) }) else null,
                )
            }
        }
    }
}

/**
 * A secret the server hands out once — a token, an invite link: shown here
 * and nowhere else, out of screenshots, copied as sensitive. Nothing keeps it
 * after the dialog closes.
 */
@Composable
internal fun SecretOnceDialog(title: String, secret: String, note: String, copyLabel: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    SecureWindow()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(title) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer { Text(secret, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
                    }
                    TextButton(onClick = { copySecret(ctx, title, secret) }) { Text(copyLabel) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.admin_secret_done)) } },
    )
}

/** When a token stops working, in words. */
@Composable
internal fun tokenExpiry(t: ApiPersonalAccessToken, now: Long): String {
    val days = TokenRules.daysLeft(t, now)
    return when {
        days == null -> stringResource(R.string.admin_pat_never)
        TokenRules.isExpired(t, now) -> stringResource(R.string.admin_pat_expired)
        days == 0 -> stringResource(R.string.admin_pat_expires_today)
        else -> pluralStringResource(R.plurals.admin_pat_days_left, days, days)
    }
}

/**
 * [owner]'s personal access tokens, read when the section opens and never
 * cached: expiry, last use, a flag on those expiring soon and on the one
 * the panel signs in with; create (shown once) and delete through the gates.
 */
@Composable
internal fun TokensSection(host: AdminHost, owner: ApiUser, now: Long = System.currentTimeMillis()) {
    val ctx = LocalContext.current
    var tokens by remember(owner.id) { mutableStateOf(Loadable<Listing<ApiPersonalAccessToken>>()) }
    var creating by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun reload() {
        val api = host.api ?: return
        tokens = tokens.copy(loading = true, error = null)
        host.scope.launch {
            tokens = try {
                Loadable(api.tokens(owner.id), loadedAt = System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tokens.copy(loading = false, error = e)
            }
        }
    }
    LaunchedEffect(owner.id) { reload() }

    val ownUserId = host.user?.id
    val tokenAuth = host.api?.usesToken == true
    val listing = tokens.value
    // The list was just read with the panel's own token, if it is this user's: that one is marked used now.
    val ownTokenId = remember(listing, ownUserId) {
        if (listing != null && owner.id == ownUserId) TokenRules.guessOwn(listing.items, tokens.loadedAt) else null
    }
    val canCreate = host.can(Capabilities.PATS, Op.CREATE)
    val canDelete = host.can(Capabilities.PATS, Op.DELETE)

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.admin_pat_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (tokens.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        if (canCreate) IconButton(enabled = !host.changeBusy, onClick = { creating = true }) { Icon(Icons.Default.Add, stringResource(R.string.admin_pat_create)) }
    }
    tokens.error?.let { ErrorCard(it, onRetry = ::reload) }
    when {
        listing == null -> Unit
        listing.items.isEmpty() -> HelpText(stringResource(R.string.admin_pat_empty))
        else -> listing.items.sortedBy { it.expirationDate.orEmpty() }.forEach { t ->
            val maybeOwn = TokenRules.maybeOwn(t, owner.id, ownUserId, ownTokenId, tokenAuth)
            TokenRow(t, now, isOwn = maybeOwn && ownTokenId == t.id, maybeOwn = maybeOwn, canDelete = canDelete && !host.changeBusy) {
                host.propose(UserChanges.removeToken(ctx, owner, t, maybeOwn, onDeleted = ::reload))
            }
        }
    }
    if (listing != null && listing.items.any { TokenRules.expiringSoon(it, now) }) {
        HelpText(stringResource(R.string.admin_pat_soon_note, TokenRules.SOON_DAYS), color = MaterialTheme.colorScheme.tertiary)
    }

    if (creating) NewTokenDialog(
        busy = host.changeBusy,
        onDismiss = { creating = false },
        onCreate = { name, days ->
            host.propose(UserChanges.newToken(ctx, owner, name, days) { gen ->
                creating = false
                created = (gen.personalAccessToken.name.ifEmpty { name }) to gen.plainToken
                reload()
            })
        },
    )
    created?.let { (name, plain) ->
        SecretOnceDialog(
            title = ctx.getString(R.string.admin_pat_new_title, name),
            secret = plain,
            note = ctx.getString(R.string.admin_pat_new_once),
            copyLabel = ctx.getString(R.string.action_copy),
            onDismiss = { created = null },
        )
    }
}

@Composable
private fun TokenRow(t: ApiPersonalAccessToken, now: Long, isOwn: Boolean, maybeOwn: Boolean, canDelete: Boolean, onDelete: () -> Unit) {
    val expired = TokenRules.isExpired(t, now)
    val soon = TokenRules.expiringSoon(t, now)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Default.Key, null, tint = if (expired) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(t.name.ifEmpty { t.id }) },
        supportingContent = {
            Text(
                listOfNotNull(
                    tokenExpiry(t, now),
                    formatApiDate(t.lastUsed).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_pat_last_used, it) } ?: stringResource(R.string.admin_pat_never_used),
                    formatApiDate(t.createdAt).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_pat_created, it) },
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isOwn) StatusBadge(stringResource(R.string.admin_pat_in_use), MaterialTheme.colorScheme.primaryContainer)
                else if (maybeOwn) StatusBadge(stringResource(R.string.admin_pat_maybe_in_use), MaterialTheme.colorScheme.secondaryContainer)
                if (expired) StatusBadge(stringResource(R.string.admin_pat_expired), MaterialTheme.colorScheme.errorContainer)
                else if (soon) StatusBadge(stringResource(R.string.admin_pat_soon), MaterialTheme.colorScheme.tertiaryContainer)
                if (canDelete) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.admin_pat_delete), tint = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun NewTokenDialog(busy: Boolean, onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var name by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("90") }
    val d = days.toIntOrNull()
    val daysOk = d != null && d in ApiPersonalAccessTokenRequest.MIN_DAYS..ApiPersonalAccessTokenRequest.MAX_DAYS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_pat_create)) },
        text = {
            parent.Provide {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_pat_name)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        days, { days = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_pat_days)) },
                        supportingText = { Text(stringResource(R.string.admin_pat_days_hint)) },
                        isError = !daysOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && daysOk && !busy, onClick = { onCreate(name.trim(), d!!) }) { Text(ctx.getString(R.string.admin_pat_create_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/**
 * A card for the token this panel signs in with when it expires soon or
 * already has: read once, from the signed-in user's own token list.
 */
@Composable
internal fun OwnTokenNotice(host: AdminHost, now: Long = System.currentTimeMillis()) {
    val me = host.user ?: return
    val api = host.api ?: return
    if (!api.usesToken) return
    var token by remember(me.id) { mutableStateOf<ApiPersonalAccessToken?>(null) }
    LaunchedEffect(me.id) {
        token = runCatching {
            val list = api.tokens(me.id).items
            TokenRules.guessOwn(list, System.currentTimeMillis())?.let { id -> list.firstOrNull { it.id == id } }
        }.getOrNull()
    }
    val t = token ?: return
    if (!TokenRules.expiringSoon(t, now) && !TokenRules.isExpired(t, now)) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.admin_pat_own_expiring, t.name.ifEmpty { t.id }, tokenExpiry(t, now)),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview
@Composable
private fun TokenRowPreview() = BirdSocksTheme {
    Surface {
        Column {
            TokenRow(ApiPersonalAccessToken("t1", "ci", expirationDate = "2099-01-01T00:00:00Z", lastUsed = "2026-11-01T00:00:00Z"), 0, isOwn = true, maybeOwn = true, canDelete = true) {}
            TokensSection(PreviewAdminHost.demo(), PreviewAdminHost.demo().user!!)
        }
    }
}
