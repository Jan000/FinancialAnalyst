package net.jandev.financialanalyst

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import net.jandev.financialanalyst.ui.AppRoot
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.theme.FinanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinanceTheme {
                CompositionLocalProvider(LocalApp provides (application as FinanceApp).container) {
                    AppRoot()
                }
            }
        }
    }
}
