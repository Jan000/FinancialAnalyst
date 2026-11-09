package net.jandev.financialanalyst.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    /** Tag des Monats, an dem der Budgetzeitraum beginnt (1 = Kalendermonat, sonst z. B. der Gehaltstag). */
    val budgetStartDay: Int = 1,
    /** Betrag, der am Ende des Budgetzeitraums übrig bleiben soll. */
    val reserveCents: Long = 0,
    val appLock: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val startDay = intPreferencesKey("budget_start_day")
    private val reserve = longPreferencesKey("reserve_cents")
    private val appLock = booleanPreferencesKey("app_lock")

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            budgetStartDay = it[startDay] ?: 1,
            reserveCents = it[reserve] ?: 0,
            appLock = it[appLock] ?: false,
        )
    }

    suspend fun update(settings: Settings) {
        context.dataStore.edit {
            it[startDay] = settings.budgetStartDay.coerceIn(1, 31)
            it[reserve] = settings.reserveCents.coerceAtLeast(0)
            it[appLock] = settings.appLock
        }
    }
}
