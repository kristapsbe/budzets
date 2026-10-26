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
}

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

    @Insert
    suspend fun insertClosure(closure: MonthClosureEntity)

    @Insert
    suspend fun insertClosureCategories(rows: List<MonthClosureCategoryEntity>)

    @Update
    suspend fun updateClosure(closure: MonthClosureEntity)
}
