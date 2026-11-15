package io.github.bropines.birdsocks.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.areas.AdminAreas
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.home.AdminHome
import io.github.bropines.birdsocks.admin.home.HomeInfo
import io.github.bropines.birdsocks.admin.safety.SafetyHost
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.DaemonStoppedState
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.openUrl
import io.github.bropines.birdsocks.ui.readableWidth
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * The admin panel: the active account's network through NetBird's management
 * API — a home screen (what waits for you, the account, the areas) and one
 * screen per area ([AdminAreas]). A FragmentActivity for BiometricPrompt,
 * which every change goes through. Opened through [AdminPanel].
 */
class AdminActivity : FragmentActivity() {
    private val model: AdminViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(AdminPanel.EXTRA_PAGE)
        if (savedInstanceState == null) model.start()
        setContent { BirdSocksTheme { AdminScreen(model, page, firstLaunch = savedInstanceState == null, onBack = { finish() }) } }
    }
}

@Composable
private fun AdminScreen(model: AdminViewModel, initialPage: String?, firstLaunch: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val daemon by NetbirdState.daemon.collectAsState()
    // The credential screen on demand, over a working panel.
    var editingCredential by rememberSaveable { mutableStateOf(initialPage == AdminPanel.PAGE_SETUP) }
    // The deep link's area, opened once the panel is ready.
    var pendingPage by rememberSaveable { mutableStateOf(if (firstLaunch) initialPage else null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(daemon) {
        if (daemon == NetbirdState.Daemon.Running && model.phase == AdminViewModel.Phase.NO_DAEMON) model.start()
    }
    LaunchedEffect(model.phase, pendingPage) {
        if (model.phase == AdminViewModel.Phase.READY && pendingPage != null) {
            AdminAreas.forPage(pendingPage)?.let { model.navigate(AdminRoute(it.id)) }
            pendingPage = null
        }
    }
    model.note?.let { n ->
        val text = n.message(context)
        val actionLabel = n.action?.label?.invoke(context)
        LaunchedEffect(n.id) {
            val r = snackbar.showSnackbar(text, actionLabel = actionLabel, withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) n.action?.run?.invoke()
            if (model.note?.id == n.id) model.note = null
        }
    }

    val showSetup = model.phase == AdminViewModel.Phase.SETUP || (editingCredential && model.phase == AdminViewModel.Phase.READY)
    Box(Modifier.fillMaxSize()) {
        if (showSetup) {
            AdminSetupScreen(
                model = model,
                onBack = { if (editingCredential && model.phase == AdminViewModel.Phase.READY) editingCredential = false else onBack() },
                onSaved = { editingCredential = false },
            )
        } else {
            val route = model.route
            val area = if (model.phase == AdminViewModel.Phase.READY && !route.isHome) AdminAreas.byId(route.areaId) else null
            if (area != null) {
                BackHandler { model.back() }
                area.content(AreaScope(model, area, route.arg, onBack = { model.back() }))
            } else {
                HomeFrame(model, onBack, onCredential = { editingCredential = true })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    SafetyHost(
        step = model.flow.step,
        onConfirm = { model.flow.confirm(it) },
        onCancel = { model.flow.cancel() },
        onUnlockResult = { model.flow.onUnlock(it) },
    )
}

@Composable
private fun HomeFrame(model: AdminViewModel, onBack: () -> Unit, onCredential: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    title = stringResource(R.string.admin_title),
                    subtitle = model.account?.let { a -> listOfNotNull(a.host, model.user?.role?.takeIf { it.isNotEmpty() }).joinToString(" · ") },
                    onBack = onBack,
                    actions = {
                        // What the API does not offer is on NetBird's web dashboard.
                        model.account?.dashboardUrl?.let { url ->
                            IconButton(onClick = { openUrl(context, url) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.admin_open_dashboard)) }
                        }
                        if (model.phase == AdminViewModel.Phase.READY) {
                            IconButton(onClick = { model.refreshLoaded() }, enabled = !model.refreshing) { Icon(Icons.Default.Refresh, stringResource(R.string.admin_refresh)) }
                            IconButton(onClick = onCredential) { Icon(Icons.Default.VpnKey, stringResource(R.string.admin_credential_title)) }
                        }
                    },
                )
                if (model.refreshing || model.changeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (model.phase) {
                AdminViewModel.Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                AdminViewModel.Phase.NO_DAEMON -> DaemonStoppedState(onStarted = { model.start() })
                AdminViewModel.Phase.BAD_URL -> EmptyState(Icons.Default.GppBad, stringResource(R.string.admin_bad_url, model.account?.managementUrl.orEmpty()))
                AdminViewModel.Phase.SETUP -> Unit
                AdminViewModel.Phase.READY -> Box(Modifier.fillMaxSize().readableWidth()) {
                    AdminHome(
                        model,
                        HomeInfo(
                            readOnlyReason = model.readOnlyReason,
                            signedInWithDevice = model.authMode == AdminViewModel.AuthMode.DEVICE,
                            restricted = model.caps?.restricted == true,
                        ),
                    )
                }
            }
        }
    }
}
