package lv.kristapsbe.budzets.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.canSwitchCurrency
import java.time.Instant

/**
 * Small preferences live in DataStore. This is the only source of truth for these settings,
 * and no budget records are kept here.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    /** Whether stored amounts exist whose meaning depends on the currency's minor unit. */
    private val hasMonetaryData: suspend () -> Boolean,
) {
    val currency: Flow<CurrencySpec> = dataStore.data.map { prefs ->
        prefs[CURRENCY]?.let { runCatching { CurrencySpec.of(it) }.getOrNull() } ?: CurrencySpec.DEFAULT
    }

    val lastBackupAt: Flow<Instant?> = dataStore.data.map { prefs -> prefs[LAST_BACKUP_AT]?.let(Instant::ofEpochMilli) }

    /** @return false if refused: amounts exist and the new currency has a different minor unit. */
    suspend fun setCurrency(to: CurrencySpec): Boolean {
        var applied = false
        dataStore.edit { prefs ->
            val from = prefs[CURRENCY]?.let { runCatching { CurrencySpec.of(it) }.getOrNull() } ?: CurrencySpec.DEFAULT
            if (canSwitchCurrency(from, to, hasMonetaryData())) {
                prefs[CURRENCY] = to.code
                applied = true
            }
        }
        return applied
    }

    /** Call only after the backup file write has succeeded. */
    suspend fun recordBackupSuccess(at: Instant) {
        dataStore.edit { it[LAST_BACKUP_AT] = at.toEpochMilli() }
    }

    private companion object {
        val CURRENCY = stringPreferencesKey("currency_code")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }
}
