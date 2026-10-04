package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** NetBird's DNS on this device: its servers, the DNS proxy, lookups. (Stub until the DNS screen lands.) */
class DnsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { DnsScreen(onBack = { finish() }) } }
    }
}

@Composable
fun DnsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_menu_dns), onBack = onBack) }) { padding ->
        EmptyState(icon = Icons.Default.Dns, text = stringResource(R.string.nb_menu_dns), modifier = Modifier.fillMaxSize().padding(padding))
    }
}
