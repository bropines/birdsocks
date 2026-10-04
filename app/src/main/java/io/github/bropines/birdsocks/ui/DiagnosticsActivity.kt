package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** The connection's health and NetBird's events, with the diagnostic tools. (Stub until the screen lands.) */
class DiagnosticsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_CONNECTION = 0
        const val PAGE_EVENTS = 1
        fun intent(context: Context, page: Int = PAGE_CONNECTION) =
            Intent(context, DiagnosticsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getIntExtra(EXTRA_PAGE, PAGE_CONNECTION)
        setContent { BirdSocksTheme { DiagnosticsScreen(page, onBack = { finish() }) } }
    }
}

@Composable
fun DiagnosticsScreen(initialPage: Int, onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_menu_diagnostics), onBack = onBack) }) { padding ->
        EmptyState(icon = Icons.Default.MonitorHeart, text = stringResource(R.string.nb_menu_diagnostics), modifier = Modifier.fillMaxSize().padding(padding))
    }
}
