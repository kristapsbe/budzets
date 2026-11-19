package lv.kristapsbe.budzets.data.backup

import android.util.AtomicFile
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.entity.BoostEntity
import lv.kristapsbe.budzets.data.local.entity.BudgetConfigEntity
import lv.kristapsbe.budzets.data.local.entity.CategoryEntity
import lv.kristapsbe.budzets.data.local.entity.ExpenseEntity
import lv.kristapsbe.budzets.data.local.entity.LedgerEntryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureCategoryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureEntity
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * Export, restore and undo. A restore replaces everything in one transaction, so it either
 * fully happens or leaves the data as it was. Before replacing, the current data is saved to
 * [safetyCopy] (Q21); undoing the restore puts it back. The safety copy is private to the app
 * and lives in `noBackupFilesDir`, so Android never uploads it.
 *
 * The currency and the one-off note live in DataStore, outside the transaction; they are set
 * right after the data.
 * Screens and the widget follow along through Room's invalidation and the settings flow.
 */
class BackupRepository(
    private val db: BudzetsDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val appVersion: String,
    private val safetyCopy: File,
) {
    private val dao = db.backupDao()

    /**
     * When the last restore happened, while it can still be undone. The marker lives in settings,
     * which move to a new phone (Q23), but the safety copy doesn't, so the file decides.
     */
    val lastRestoreAt: Flow<Instant?> =
        settings.lastRestoreAt.map { at -> at?.takeIf { withContext(Dispatchers.IO) { safetyCopy.exists() } } }

    /** A consistent snapshot of all data, as a backup file. */
    suspend fun export(): ByteArray {
        val currency = settings.currency.first()
        val oneOffNote = settings.oneOffNote.first()
        val data = db.withTransaction {
            val closureCategories = dao.monthClosureCategories().groupBy { it.month to it.revision }
            BackupData(
                settings = BackupSettings(currency.code, oneOffNote),
                categories = dao.categories().map { it.toBackup() },
                budgetConfigs = dao.budgetConfigs().map { it.toBackup() },
                expenses = dao.expenses().map { it.toBackup() },
                ledger = dao.ledger().map { it.toBackup() },
                boosts = dao.boosts().map { it.toBackup() },
                monthClosures = dao.monthClosures().map { it.toBackup(closureCategories[it.month to it.revision].orEmpty()) },
            )
        }
        // Off the main thread: with years of data the file is a few MB of JSON and a checksum.
        return withContext(Dispatchers.Default) { BackupCodec.encode(data, clock.instant(), appVersion) }
    }

    /**
     * Replaces all data with [backup]. Throws if anything fails; the data is then unchanged
     * (a failure writing the safety copy stops the restore before it starts).
     */
    suspend fun restore(backup: Backup) {
        writeSafetyCopy(export())
        replaceWith(backup)
        settings.recordRestore(clock.instant())
    }

    /** Puts back the data from before the last restore. False if there is nothing to undo. */
    suspend fun undoRestore(): Boolean {
        val bytes = withContext(Dispatchers.IO) { if (safetyCopy.exists()) AtomicFile(safetyCopy).readFully() else null }
        val backup = bytes?.let { withContext(Dispatchers.Default) { (BackupCodec.decode(it) as? Outcome.Ok)?.value } }
        if (backup == null) {
            settings.recordRestore(null)
            return false
        }
        replaceWith(backup)
        settings.recordRestore(null)
        withContext(Dispatchers.IO) { AtomicFile(safetyCopy).delete() }
        return true
    }

    private suspend fun replaceWith(backup: Backup) {
        val data = backup.data
        db.withTransaction {
            // Children before parents on the way out, parents first on the way in.
            dao.clearMonthClosureCategories()
            dao.clearMonthClosures()
            dao.clearBoosts()
            dao.clearLedger()
            dao.clearExpenses()
            dao.clearBudgetConfigs()
            dao.clearCategories()

            dao.insertCategories(data.categories.map { it.toEntity() })
            dao.insertBudgetConfigs(data.budgetConfigs.map { it.toEntity() })
            dao.insertExpenses(data.expenses.map { it.toEntity() })
            dao.insertLedger(data.ledger.map { it.toEntity() })
            dao.insertBoosts(data.boosts.map { it.toEntity() })
            dao.insertMonthClosures(data.monthClosures.map { it.toEntity() })
            dao.insertMonthClosureCategories(data.monthClosures.flatMap { m -> m.categories.map { it.toEntity(m) } })
        }
        settings.replaceCurrency(CurrencySpec.of(data.settings.currencyCode))
        settings.setOneOffNote(data.settings.oneOffNote)
    }

    private suspend fun writeSafetyCopy(bytes: ByteArray) = withContext(Dispatchers.IO) {
        safetyCopy.parentFile?.mkdirs()
        val file = AtomicFile(safetyCopy)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }
}

private fun Long.iso(): String = Instant.ofEpochMilli(this).toString()
private fun String.epochMillis(): Long = Instant.parse(this).toEpochMilli()

private fun CategoryEntity.toBackup() = BackupCategory(id, name, iconKey, sortOrder, archivedFrom, createdAt.iso(), updatedAt.iso(), deletedAt?.iso())
private fun BackupCategory.toEntity() = CategoryEntity(id, name, iconKey, sortOrder, archivedFrom, createdAt.epochMillis(), updatedAt.epochMillis(), deletedAt?.epochMillis())

private fun BudgetConfigEntity.toBackup() = BackupBudgetConfig(
    id = id,
    categoryId = categoryId,
    effectiveFrom = effectiveFrom,
    dailyTargetMinor = dailyTargetMinor,
    weeklyTargetMinor = weeklyTargetMinor,
    monthlyTargetMinor = monthlyTargetMinor,
    createdAt = createdAt.iso(),
    updatedAt = updatedAt.iso(),
)

private fun BackupBudgetConfig.toEntity() = BudgetConfigEntity(
    id = id,
    scopeKey = categoryId ?: BudgetConfigEntity.OVERALL_SCOPE,
    categoryId = categoryId,
    dailyTargetMinor = dailyTargetMinor,
    weeklyTargetMinor = weeklyTargetMinor,
    effectiveFrom = effectiveFrom,
    createdAt = createdAt.epochMillis(),
    updatedAt = updatedAt.epochMillis(),
    monthlyTargetMinor = monthlyTargetMinor,
)

private fun ExpenseEntity.toBackup() = BackupExpense(id, amountMinor, categoryId, expenseDate, note, createdAt.iso(), updatedAt.iso())
private fun BackupExpense.toEntity() = ExpenseEntity(id, amountMinor, categoryId, date, note, createdAt.epochMillis(), updatedAt.epochMillis())

private fun LedgerEntryEntity.toBackup() =
    BackupLedgerEntry(id, type, pocketCategoryId, amountMinor, sourceMonth, targetMonth, relatedEntryId, boostId, note, createdAt.iso())

private fun BackupLedgerEntry.toEntity() =
    LedgerEntryEntity(id, type, pocketCategoryId, amountMinor, sourceMonth, targetMonth, relatedEntryId, boostId, note, createdAt.epochMillis())

private fun BoostEntity.toBackup() = BackupBoost(
    id, targetMonth, destinationCategoryId, sourceCategoryId, amountMinor, status, reserveEntryId, createdAt.iso(), releasedAt?.iso(),
)

private fun BackupBoost.toEntity() = BoostEntity(
    id, targetMonth, destinationCategoryId, sourceCategoryId, amountMinor, status, reserveEntryId, createdAt.epochMillis(), releasedAt?.epochMillis(),
)

private fun MonthClosureEntity.toBackup(categories: List<MonthClosureCategoryEntity>) = BackupMonthClosure(
    month = month,
    revision = revision,
    closedAt = closedAt.iso(),
    reopenedAt = reopenedAt?.iso(),
    overallBaseMinor = overallBaseMinor,
    overallDerived = overallDerived,
    categories = categories.map { BackupClosureCategory(it.categoryId, it.baseMinor, it.boostMinor, it.spentMinor) },
)

private fun BackupMonthClosure.toEntity() =
    MonthClosureEntity(month, revision, closedAt.epochMillis(), reopenedAt?.epochMillis(), overallBaseMinor, overallDerived)

private fun BackupClosureCategory.toEntity(closure: BackupMonthClosure) =
    MonthClosureCategoryEntity(closure.month, closure.revision, categoryId, baseMinor, boostMinor, spentMinor)
