package net.jandev.financialanalyst.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farben mit fester Bedeutung, die es im Material-Schema nicht gibt. */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val warning: Color,
    /** Kategorische Palette für Diagramme, in fester Reihenfolge. */
    val chart: List<Color>,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F5B55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE8E1),
    onPrimaryContainer = Color(0xFF06302D),
    secondary = Color(0xFF4F635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1EAE6),
    onSecondaryContainer = Color(0xFF1B2A27),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE6BD),
    onTertiaryContainer = Color(0xFF2C1B00),
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF171C1B),
    surface = Color(0xFFF6F7F4),
    onSurface = Color(0xFF171C1B),
    surfaceVariant = Color(0xFFE3E8E4),
    onSurfaceVariant = Color(0xFF49524F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDF0EC),
    surfaceContainerHigh = Color(0xFFE6EAE6),
    surfaceContainerHighest = Color(0xFFDFE4E0),
    outline = Color(0xFF7A8480),
    outlineVariant = Color(0xFFD3D9D5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FD1C2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF134F47),
    onPrimaryContainer = Color(0xFFCDE8E1),
    secondary = Color(0xFFB4C9C3),
    onSecondary = Color(0xFF20342F),
    secondaryContainer = Color(0xFF2B3D39),
    onSecondaryContainer = Color(0xFFD5E5DF),
    tertiary = Color(0xFFF2C14E),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFBE6BD),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E4E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE1E4E1),
    surfaceVariant = Color(0xFF2A302E),
    onSurfaceVariant = Color(0xFFBFC8C4),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF191E1C),
    surfaceContainer = Color(0xFF1D2220),
    surfaceContainerHigh = Color(0xFF272D2B),
    surfaceContainerHighest = Color(0xFF323836),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3A423F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val LightFinance = FinanceColors(
    income = Color(0xFF1B7F4B),
    expense = Color(0xFFB3382C),
    warning = Color(0xFF9A6700),
    chart = listOf(
        Color(0xFF0F766E), Color(0xFFC2571A), Color(0xFF3B5BA9), Color(0xFF9C4F96),
        Color(0xFF7A8B1E), Color(0xFFB7791F), Color(0xFF2E86AB), Color(0xFF8D6E63),
    ),
)

private val DarkFinance = FinanceColors(
    income = Color(0xFF6FD39A),
    expense = Color(0xFFFF9A8C),
    warning = Color(0xFFF2C14E),
    chart = listOf(
        Color(0xFF5EC4B6), Color(0xFFF0955A), Color(0xFF8EA8F0), Color(0xFFD99AD3),
        Color(0xFFBCCB5A), Color(0xFFE5B35A), Color(0xFF79C4E0), Color(0xFFC4A79C),
    ),
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinance }

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalFinanceColors provides if (dark) DarkFinance else LightFinance) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content,
        )
    }
}
