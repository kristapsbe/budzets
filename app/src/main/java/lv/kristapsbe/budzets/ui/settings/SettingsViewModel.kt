package lv.kristapsbe.budzets.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.backup.Backup
import lv.kristapsbe.budzets.data.backup.BackupCodec
import lv.kristapsbe.budzets.data.backup.BackupDocuments
import lv.kristapsbe.budzets.data.backup.BackupProblem
import lv.kristapsbe.budzets.data.backup.BackupRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

enum class Busy { BACKING_UP, READING, RESTORING, UNDOING }

enum class SettingsMessage {
    BACKUP_SAVED,
    BACKUP_NOT_CONFIRMED,
    BACKUP_FAILED,
    NOT_A_BACKUP,
    BACKUP_TOO_NEW,
    BACKUP_DAMAGED,
    FILE_TOO_LARGE,
    FILE_UNREADABLE,
    RESTORED,
    RESTORE_FAILED,
    UNDONE,
    UNDO_FAILED,
    CURRENCY_REFUSED,
}

/** What the restore confirmation says about the chosen file. */
data class RestorePreview(
    val createdAt: Instant,
    val currency: CurrencySpec,
    val categories: Int,
    val expenses: Int,
    val closedMonths: Int,
    val firstExpense: LocalDate?,
    val lastExpense: LocalDate?,
)

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Error(val message: String?) : SettingsUiState
    data class Ready(
        val currency: CurrencySpec,
        /** Amounts exist, so only a currency with the same number of decimals can be chosen. */
        val currencyLocked: Boolean,
        val lastBackupAt: Instant?,
        /** Set while the last restore can be undone. */
        val lastRestoreAt: Instant?,
        val busy: Busy?,
        /** A valid file was chosen and awaits confirmation. */
        val pendingRestore: RestorePreview?,
        val appVersion: String,
    ) : SettingsUiState
}

/**
 * Backup, restore and its undo, plus the currency. A chosen backup is checked completely before
 * the confirmation is shown; nothing changes until the user confirms. The chosen file is held in
 * memory only: after process death the user picks it again, since the file permission is gone too.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    expenses: ExpenseRepository,
    private val backups: BackupRepository,
    private val documents: BackupDocuments,
    private val clock: Clock,
    private val appVersion: String,
) : ViewModel() {

    private val busy = MutableStateFlow<Busy?>(null)
    private val pending = MutableStateFlow<Backup?>(null)
    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    val uiState: StateFlow<SettingsUiState> =
        combine(settings.currency, expenses.hasMonetaryData, settings.lastBackupAt, settings.lastRestoreAt, combine(busy, pending, ::Pair)) {
                currency, hasData, lastBackup, lastRestore, (working, chosen) ->
            SettingsUiState.Ready(
                currency = currency,
                currencyLocked = hasData,
                lastBackupAt = lastBackup,
                lastRestoreAt = lastRestore,
                busy = working,
                pendingRestore = chosen?.let(::preview),
                appVersion = appVersion,
            ) as SettingsUiState
        }
            .catch { emit(SettingsUiState.Error(it.message)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState.Loading)

    /** Suggested name for a new backup; the picker adds " (1)" if it already exists. */
    fun backupFileName(): String = "budzets-backup-${LocalDate.now(clock)}.json"

    /** [uri] is where the user chose to save, or null if they cancelled the picker. */
    fun backUp(uri: Uri?) {
        if (uri == null) return
        operate(Busy.BACKING_UP, onError = SettingsMessage.BACKUP_FAILED) {
            if (documents.write(uri, backups.export())) {
                settings.recordBackupSuccess(clock.instant())
                SettingsMessage.BACKUP_SAVED
            } else {
                SettingsMessage.BACKUP_NOT_CONFIRMED
            }
        }
    }

    /** Reads and checks the chosen file; a valid one waits in [SettingsUiState.Ready.pendingRestore]. */
    fun openBackup(uri: Uri?) {
        if (uri == null) return
        operate(Busy.READING, onError = SettingsMessage.FILE_UNREADABLE) {
            val bytes = try {
                documents.read(uri, BackupCodec.MAX_BYTES + 1)
            } catch (e: IOException) {
                return@operate SettingsMessage.FILE_UNREADABLE
            } catch (e: SecurityException) {
                return@operate SettingsMessage.FILE_UNREADABLE
            }
            when (val result = BackupCodec.decode(bytes)) {
                is Outcome.Ok -> {
                    pending.value = result.value
                    null
                }
                is Outcome.Err -> when (result.error.problem) {
                    BackupProblem.NOT_A_BACKUP -> SettingsMessage.NOT_A_BACKUP
                    BackupProblem.TOO_NEW -> SettingsMessage.BACKUP_TOO_NEW
                    BackupProblem.DAMAGED -> SettingsMessage.BACKUP_DAMAGED
                    BackupProblem.TOO_LARGE -> SettingsMessage.FILE_TOO_LARGE
                }
            }
        }
    }

    fun cancelRestore() {
        pending.value = null
    }

    fun confirmRestore() {
        val backup = pending.value ?: return
        pending.value = null
        operate(Busy.RESTORING, onError = SettingsMessage.RESTORE_FAILED) {
            // Not cancelled by leaving the screen: the restore and its undo marker go together.
            withContext(NonCancellable) { backups.restore(backup) }
            SettingsMessage.RESTORED
        }
    }

    fun undoRestore() {
        operate(Busy.UNDOING, onError = SettingsMessage.UNDO_FAILED) {
            if (withContext(NonCancellable) { backups.undoRestore() }) SettingsMessage.UNDONE else SettingsMessage.UNDO_FAILED
        }
    }

    fun setCurrency(currency: CurrencySpec) {
        viewModelScope.launch {
            if (!settings.setCurrency(currency)) _message.value = SettingsMessage.CURRENCY_REFUSED
        }
    }

    fun messageShown(shown: SettingsMessage) {
        _message.compareAndSet(shown, null)
    }

    /** One operation at a time; [block] returns the message to show, if any. */
    private fun operate(kind: Busy, onError: SettingsMessage, block: suspend () -> SettingsMessage?) {
        if (busy.value != null) return
        busy.value = kind
        viewModelScope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError
            } finally {
                busy.value = null
            }
            result?.let { _message.value = it }
        }
    }

    private fun preview(backup: Backup): RestorePreview {
        val data = backup.data
        val dates = data.expenses.map { LocalDate.parse(it.date) }
        val closedMonths = data.monthClosures.groupBy { it.month }.values.count { revisions -> revisions.maxBy { it.revision }.reopenedAt == null }
        return RestorePreview(
            createdAt = backup.createdAt,
            currency = backup.currency,
            categories = data.categories.size,
            expenses = data.expenses.size,
            closedMonths = closedMonths,
            firstExpense = dates.minOrNull(),
            lastExpense = dates.maxOrNull(),
        )
    }

    companion object {
        /** The picker's filter. Some providers label JSON as plain text or as generic binary. */
        val BACKUP_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
        const val BACKUP_MIME_TYPE = "application/json"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                SettingsViewModel(container.settings, container.expenses, container.backups, container.backupDocuments, container.clock, container.appVersion)
            }
        }
    }
}
