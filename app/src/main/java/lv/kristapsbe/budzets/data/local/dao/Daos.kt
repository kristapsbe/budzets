package lv.kristapsbe.budzets.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import lv.kristapsbe.budzets.data.local.entity.BoostEntity
import lv.kristapsbe.budzets.data.local.entity.BudgetConfigEntity
import lv.kristapsbe.budzets.data.local.entity.CategoryEntity
import lv.kristapsbe.budzets.data.local.entity.ExpenseEntity
import lv.kristapsbe.budzets.data.local.entity.LedgerEntryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureCategoryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureEntity

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE deletedAt IS NULL)")
    suspend fun hasCategories(): Boolean

    @Query("SELECT id FROM categories WHERE deletedAt IS NOT NULL")
    fun observeDeletedIds(): Flow<List<String>>

    @Query("SELECT id FROM categories WHERE deletedAt IS NOT NULL")
    suspend fun getDeletedIds(): List<String>

    @Insert
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM expenses WHERE categoryId = :id)
            OR EXISTS(SELECT 1 FROM surplus_ledger WHERE pocketCategoryId = :id)
            OR EXISTS(SELECT 1 FROM boosts WHERE destinationCategoryId = :id OR sourceCategoryId = :id)
            OR EXISTS(SELECT 1 FROM month_closure_categories WHERE categoryId = :id)
        """
    )
    suspend fun hasHistory(id: String): Boolean

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface BudgetConfigDao {
    @Query("SELECT * FROM budget_configs ORDER BY effectiveFrom")
    fun observeAll(): Flow<List<BudgetConfigEntity>>

    @Query("SELECT * FROM budget_configs ORDER BY effectiveFrom")
    suspend fun getAll(): List<BudgetConfigEntity>

    @Query("SELECT * FROM budget_configs WHERE scopeKey = :scopeKey AND effectiveFrom = :month")
    suspend fun find(scopeKey: String, month: String): BudgetConfigEntity?

    @Insert
    suspend fun insert(config: BudgetConfigEntity)

    @Update
    suspend fun update(config: BudgetConfigEntity)
}

data class CategorySpendRow(val categoryId: String, val totalMinor: Long)

/** [date] is the ISO date. */
data class DateSpendRow(val date: String, val totalMinor: Long)

/** [month] is "YYYY-MM". */
data class MonthCategorySpendRow(val month: String, val categoryId: String, val totalMinor: Long)

@Dao
interface ExpenseDao {
    /** Dates are ISO strings, so lexical comparison is chronological. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE expenseDate >= :fromDate AND expenseDate < :toDateExclusive
        ORDER BY expenseDate DESC, createdAt DESC
        """
    )
    fun observeBetween(fromDate: String, toDateExclusive: String): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT categoryId, SUM(amountMinor) AS totalMinor FROM expenses
        WHERE expenseDate >= :fromDate AND expenseDate < :toDateExclusive
        GROUP BY categoryId
        """
    )
    fun observeSpending(fromDate: String, toDateExclusive: String): Flow<List<CategorySpendRow>>

    @Query(
        """
        SELECT expenseDate AS date, SUM(amountMinor) AS totalMinor FROM expenses
        WHERE expenseDate >= :fromDate AND expenseDate < :toDateExclusive
        GROUP BY expenseDate
        """
    )
    fun observeDailySpending(fromDate: String, toDateExclusive: String): Flow<List<DateSpendRow>>

    /** Every month's total per category. The first 7 characters of an ISO date are its month. */
    @Query(
        """
        SELECT substr(expenseDate, 1, 7) AS month, categoryId, SUM(amountMinor) AS totalMinor FROM expenses
        GROUP BY month, categoryId
        """
    )
    fun observeMonthlySpending(): Flow<List<MonthCategorySpendRow>>

    @Query(
        """
        SELECT categoryId, SUM(amountMinor) AS totalMinor FROM expenses
        WHERE expenseDate >= :fromDate AND expenseDate < :toDateExclusive
        GROUP BY categoryId
        """
    )
    suspend fun getSpending(fromDate: String, toDateExclusive: String): List<CategorySpendRow>

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: String): ExpenseEntity?

    @Query("SELECT categoryId FROM expenses ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun lastAddedCategoryId(): String?

    @Insert
    suspend fun insert(expense: ExpenseEntity)

    @Update
    suspend fun update(expense: ExpenseEntity): Int

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Whether any data exists whose meaning depends on the currency's minor unit. */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM expenses)
            OR EXISTS(SELECT 1 FROM budget_configs)
            OR EXISTS(SELECT 1 FROM surplus_ledger)
        """
    )
    suspend fun hasMonetaryData(): Boolean

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM expenses)
            OR EXISTS(SELECT 1 FROM budget_configs)
            OR EXISTS(SELECT 1 FROM surplus_ledger)
        """
    )
    fun observeHasMonetaryData(): Flow<Boolean>
}

/** One line of a closed month's snapshot; the category columns are null if it has no categories. */
data class ClosureSnapshotRow(
    val month: String,
    val overallBaseMinor: Long?,
    val overallDerived: Boolean,
    val categoryId: String?,
    val baseMinor: Long?,
    val boostMinor: Long?,
    val spentMinor: Long?,
)

@Dao
interface SurplusDao {
    @Query("SELECT * FROM surplus_ledger ORDER BY createdAt, rowid")
    fun observeLedger(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM surplus_ledger ORDER BY createdAt, rowid")
    suspend fun getLedger(): List<LedgerEntryEntity>

    @Insert
    suspend fun insertEntries(entries: List<LedgerEntryEntity>)

    @Query("SELECT * FROM boosts ORDER BY targetMonth, createdAt")
    fun observeBoosts(): Flow<List<BoostEntity>>

    @Query("SELECT * FROM boosts WHERE targetMonth = :month")
    fun observeBoostsFor(month: String): Flow<List<BoostEntity>>

    @Query("SELECT * FROM boosts WHERE targetMonth = :month")
    suspend fun getBoostsFor(month: String): List<BoostEntity>

    @Query("SELECT * FROM boosts WHERE id = :id")
    suspend fun getBoost(id: String): BoostEntity?

    @Query("SELECT * FROM boosts WHERE destinationCategoryId = :categoryId AND status = 'ACTIVE'")
    suspend fun getActiveBoostsInto(categoryId: String): List<BoostEntity>

    @Insert
    suspend fun insertBoost(boost: BoostEntity)

    @Update
    suspend fun updateBoost(boost: BoostEntity)

    @Query("SELECT * FROM month_closures ORDER BY month, revision")
    fun observeClosures(): Flow<List<MonthClosureEntity>>

    @Query("SELECT * FROM month_closures WHERE month = :month ORDER BY revision DESC LIMIT 1")
    suspend fun getLatestClosure(month: String): MonthClosureEntity?

    @Query("SELECT * FROM month_closure_categories WHERE month = :month AND revision = :revision")
    suspend fun getClosureCategories(month: String, revision: Int): List<MonthClosureCategoryEntity>

    /** Months whose latest closure is still in force. */
    @Query(
        """
        SELECT c.month FROM month_closures c
        WHERE c.reopenedAt IS NULL
          AND c.revision = (SELECT MAX(revision) FROM month_closures WHERE month = c.month)
        """
    )
    suspend fun getClosedMonths(): List<String>

    @Query(
        """
        SELECT c.month FROM month_closures c
        WHERE c.reopenedAt IS NULL
          AND c.revision = (SELECT MAX(revision) FROM month_closures WHERE month = c.month)
        """
    )
    fun observeClosedMonths(): Flow<List<String>>

    /**
     * The snapshot of the closure in force for [month]; empty unless the month is closed. One
     * query, so a close or reopen can never be seen half done.
     */
    @Query(
        """
        SELECT c.month, c.overallBaseMinor, c.overallDerived, r.categoryId, r.baseMinor, r.boostMinor, r.spentMinor
        FROM month_closures c
        LEFT JOIN month_closure_categories r ON r.month = c.month AND r.revision = c.revision
        WHERE c.month = :month AND c.reopenedAt IS NULL
          AND c.revision = (SELECT MAX(revision) FROM month_closures WHERE month = c.month)
        """
    )
    fun observeClosureSnapshot(month: String): Flow<List<ClosureSnapshotRow>>

    @Query(
        """
        SELECT c.month, c.overallBaseMinor, c.overallDerived, r.categoryId, r.baseMinor, r.boostMinor, r.spentMinor
        FROM month_closures c
        LEFT JOIN month_closure_categories r ON r.month = c.month AND r.revision = c.revision
        WHERE c.month = :month AND c.reopenedAt IS NULL
          AND c.revision = (SELECT MAX(revision) FROM month_closures WHERE month = c.month)
        """
    )
    suspend fun getClosureSnapshot(month: String): List<ClosureSnapshotRow>

    /** The snapshots of every closed month, as [observeClosureSnapshot] gives them one month at a time. */
    @Query(
        """
        SELECT c.month, c.overallBaseMinor, c.overallDerived, r.categoryId, r.baseMinor, r.boostMinor, r.spentMinor
        FROM month_closures c
        LEFT JOIN month_closure_categories r ON r.month = c.month AND r.revision = c.revision
        WHERE c.reopenedAt IS NULL
          AND c.revision = (SELECT MAX(revision) FROM month_closures WHERE month = c.month)
        """
    )
    fun observeClosureSnapshots(): Flow<List<ClosureSnapshotRow>>

    @Insert
    suspend fun insertClosure(closure: MonthClosureEntity)

    @Insert
    suspend fun insertClosureCategories(rows: List<MonthClosureCategoryEntity>)

    @Update
    suspend fun updateClosure(closure: MonthClosureEntity)
}

/**
 * Whole-table reads and writes for backup and restore. Reads are in insertion (rowid) order, so
 * a restore re-inserts rows in the same order and ties in "newest first" lists stay the same.
 * Callers run these in one transaction.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM categories ORDER BY rowid")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM budget_configs ORDER BY rowid")
    suspend fun budgetConfigs(): List<BudgetConfigEntity>

    @Query("SELECT * FROM expenses ORDER BY rowid")
    suspend fun expenses(): List<ExpenseEntity>

    @Query("SELECT * FROM surplus_ledger ORDER BY rowid")
    suspend fun ledger(): List<LedgerEntryEntity>

    @Query("SELECT * FROM boosts ORDER BY rowid")
    suspend fun boosts(): List<BoostEntity>

    @Query("SELECT * FROM month_closures ORDER BY rowid")
    suspend fun monthClosures(): List<MonthClosureEntity>

    @Query("SELECT * FROM month_closure_categories ORDER BY rowid")
    suspend fun monthClosureCategories(): List<MonthClosureCategoryEntity>

    @Insert
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert
    suspend fun insertBudgetConfigs(rows: List<BudgetConfigEntity>)

    @Insert
    suspend fun insertExpenses(rows: List<ExpenseEntity>)

    @Insert
    suspend fun insertLedger(rows: List<LedgerEntryEntity>)

    @Insert
    suspend fun insertBoosts(rows: List<BoostEntity>)

    @Insert
    suspend fun insertMonthClosures(rows: List<MonthClosureEntity>)

    @Insert
    suspend fun insertMonthClosureCategories(rows: List<MonthClosureCategoryEntity>)

    @Query("DELETE FROM month_closure_categories")
    suspend fun clearMonthClosureCategories()

    @Query("DELETE FROM month_closures")
    suspend fun clearMonthClosures()

    @Query("DELETE FROM boosts")
    suspend fun clearBoosts()

    @Query("DELETE FROM surplus_ledger")
    suspend fun clearLedger()

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    @Query("DELETE FROM budget_configs")
    suspend fun clearBudgetConfigs()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()
}
