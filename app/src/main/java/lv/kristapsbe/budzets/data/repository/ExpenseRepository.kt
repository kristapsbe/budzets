package lv.kristapsbe.budzets.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.dateRange
import lv.kristapsbe.budzets.data.local.toDomain
import lv.kristapsbe.budzets.data.local.toEntity
import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Expense
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.domain.model.Money
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

class ExpenseRepository(
    private val db: BudzetsDatabase,
    private val clock: Clock,
    private val newId: () -> String,
) {
    private val dao = db.expenseDao()

    /** Whether amounts exist whose meaning depends on the currency's minor unit. */
    val hasMonetaryData: Flow<Boolean> = dao.observeHasMonetaryData()

    /** Expenses dated within [month], newest first, optionally limited to one category. */
    fun observeMonth(month: YearMonth, categoryId: CategoryId? = null): Flow<List<Expense>> {
        val (from, to) = month.dateRange()
        return dao.observeBetween(from, to).map { rows ->
            rows.map { it.toDomain() }.filter { categoryId == null || it.categoryId == categoryId }
        }
    }

    fun observeRecent(limit: Int = 50): Flow<List<Expense>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: ExpenseId): Expense? = dao.get(id.value)?.toDomain()

    /** Category of the most recently *added* expense (edits don't count), to preselect for the next one. */
    suspend fun lastUsedCategory(): CategoryId? = dao.lastAddedCategoryId()?.let(::CategoryId)

    suspend fun add(amount: Money, categoryId: CategoryId, date: LocalDate, note: String? = null): Expense {
        validate(amount)
        val now = clock.instant()
        val expense = Expense(ExpenseId(newId()), amount, categoryId, date, cleanNote(note), now, now)
        dao.insert(expense.toEntity())
        return expense
    }

    /** Changing the date or category moves the expense; totals are recomputed from the rows. */
    suspend fun update(id: ExpenseId, amount: Money, categoryId: CategoryId, date: LocalDate, note: String?): Expense {
        validate(amount)
        return db.withTransaction {
            val current = requireNotNull(dao.get(id.value)) { "Unknown expense" }.toDomain()
            val updated = current.copy(
                amount = amount,
                categoryId = categoryId,
                date = date,
                note = cleanNote(note),
                updatedAt = clock.instant(),
            )
            dao.update(updated.toEntity())
            updated
        }
    }

    /** @return the deleted expense, so the caller can offer undo via [restore]. */
    suspend fun delete(id: ExpenseId): Expense? = db.withTransaction {
        val current = dao.get(id.value)?.toDomain()
        if (current != null) dao.delete(id.value)
        current
    }

    /** Re-inserts a deleted expense unchanged (undo). */
    suspend fun restore(expense: Expense) {
        validate(expense.amount)
        dao.insert(expense.toEntity())
    }

    private fun validate(amount: Money) {
        require(amount.isPositive) { "Amount must be greater than zero" }
        require(amount.minor <= AmountParser.MAX_MINOR) { "Amount is too large" }
    }

    private fun cleanNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }
}
