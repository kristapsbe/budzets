package lv.kristapsbe.budzets.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // Every edit re-emits all of DataStore, so each value drops repeats: recording a backup must not
    // look like a currency change (which would, for one, refresh the widget).
    val currency: Flow<CurrencySpec> = dataStore.data.map { prefs ->
        prefs[CURRENCY]?.let { runCatching { CurrencySpec.of(it) }.getOrNull() } ?: CurrencySpec.DEFAULT
    }.distinctUntilChanged()

    val lastBackupAt: Flow<Instant?> =
        dataStore.data.map { prefs -> prefs[LAST_BACKUP_AT]?.let(Instant::ofEpochMilli) }.distinctUntilChanged()

    /** The user's own text shown under the amount when adding a one-off boost (Q49); null when not set. */
    val oneOffNote: Flow<String?> = dataStore.data.map { prefs -> prefs[ONE_OFF_NOTE] }.distinctUntilChanged()

    /** Trimmed, and cut to [MAX_ONE_OFF_NOTE_LENGTH]; blank or null removes it. */
    suspend fun setOneOffNote(text: String?) {
        val note = text?.trim()?.take(MAX_ONE_OFF_NOTE_LENGTH)?.takeIf { it.isNotEmpty() }
        dataStore.edit { prefs -> if (note == null) prefs.remove(ONE_OFF_NOTE) else prefs[ONE_OFF_NOTE] = note }
    }

    /** When the current data was put in place by a restore that can still be undone; null otherwise. */
    val lastRestoreAt: Flow<Instant?> =
        dataStore.data.map { prefs -> prefs[LAST_RESTORE_AT]?.let(Instant::ofEpochMilli) }.distinctUntilChanged()

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

    /**
     * For a restore: the restored amounts are in [currency]'s minor units, so it is set whatever
     * the current data, which the restore replaces anyway.
     */
    suspend fun replaceCurrency(currency: CurrencySpec) {
        dataStore.edit { it[CURRENCY] = currency.code }
    }

    suspend fun recordRestore(at: Instant?) {
        dataStore.edit { prefs ->
            if (at == null) prefs.remove(LAST_RESTORE_AT) else prefs[LAST_RESTORE_AT] = at.toEpochMilli()
        }
    }

    companion object {
        const val MAX_ONE_OFF_NOTE_LENGTH = 300

        private val CURRENCY = stringPreferencesKey("currency_code")
        private val ONE_OFF_NOTE = stringPreferencesKey("one_off_note")
        private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        private val LAST_RESTORE_AT = longPreferencesKey("last_restore_at")
    }
}
