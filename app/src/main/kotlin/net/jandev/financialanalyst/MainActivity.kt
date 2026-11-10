package net.jandev.financialanalyst

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.jandev.financialanalyst.ui.AppRoot
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.theme.FinanceTheme

class MainActivity : FragmentActivity() {
    private val container get() = (application as FinanceApp).container

    private val lock get() = container.lock
    private var lockEnabled = false
    private lateinit var prompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Einmal synchron lesen, damit beim Start nichts Ungeschütztes aufblitzt.
        applyLockSetting(runBlocking { container.settings.settings.first() }.appLock, initial = true)
        // Hier und nicht erst beim Entsperren anlegen: so erreicht das Ergebnis einer Abfrage, die beim Drehen
        // des Bildschirms gerade offen war, auch die neu entstandene Activity.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), unlockCallback)
        lifecycleScope.launch {
            container.settings.settings.collect { applyLockSetting(it.appLock, initial = false) }
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FinanceTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    if (lock.locked) {
                        LockScreen(
                            onUnlock = {
                                lock.promptShowing = false
                                promptUnlock()
                            },
                        )
                    } else {
                        AppRoot()
                    }
                }
            }
        }
    }

    private fun applyLockSetting(enabled: Boolean, initial: Boolean) {
        if (enabled == lockEnabled && !initial) return
        lockEnabled = enabled
        if (enabled) {
            // Kein Inhalt in der App-Übersicht und keine Screenshots, solange die Sperre aktiv ist.
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Nur beim Start des Prozesses sperren, nicht bei jeder neu entstandenen Activity (Bildschirm gedreht).
            if (initial && !lock.initialized) lock.locked = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            lock.locked = false
        }
        lock.initialized = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) lock.leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (lockEnabled && lock.leftAt != 0L && SystemClock.elapsedRealtime() - lock.leftAt > RELOCK_AFTER_MS) lock.locked = true
        lock.leftAt = 0L
        if (lock.locked) promptUnlock()
    }

    private val unlockCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            lock.promptShowing = false
            lock.locked = false
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            lock.promptShowing = false
            // Ohne eingerichtete Gerätesperre gibt es nichts, womit man entsperren könnte: nicht aussperren.
            if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
            ) {
                lock.locked = false
            }
        }
    }

    private fun promptUnlock() {
        if (lock.promptShowing) return
        lock.promptShowing = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Finanzanalyst entsperren")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Eine geteilte oder geöffnete Datei landet im Import-Bildschirm, der Rücksprung der Bank bei den Bankverbindungen. */
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

    companion object {
        /** Kurz die App wechseln (TAN-App, Browser) soll nicht jedes Mal neu sperren. */
        private const val RELOCK_AFTER_MS = 60_000L
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Finanzanalyst ist gesperrt", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onUnlock) { Text("Entsperren") }
        }
    }
}
