package lv.kristapsbe.budzets.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class Category(
    val id: CategoryId,
    val name: String,
    val iconKey: String?,
    val sortOrder: Int,
    /** null while active; otherwise the first month the category is excluded from plans. */
    val archivedFrom: YearMonth?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isArchived: Boolean get() = archivedFrom != null

    fun toRef() = CategoryRef(id, archivedFrom)
}

data class Expense(
    val id: ExpenseId,
    val amount: Money,
    val categoryId: CategoryId,
    val date: LocalDate,
    val note: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toLine() = ExpenseLine(categoryId, date, amount)
}
