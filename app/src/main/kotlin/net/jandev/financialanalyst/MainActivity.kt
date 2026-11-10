package net.jandev.financialanalyst

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.IntentCompat
import net.jandev.financialanalyst.ui.AppRoot
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.theme.FinanceTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as FinanceApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FinanceTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Eine geteilte oder geöffnete Datei landet im Import-Bildschirm. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "finanzanalyst") {
            container.pendingBankCallback.value = data
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            else -> null
        }
        if (uri != null) container.pendingImport.value = uri
    }
}
