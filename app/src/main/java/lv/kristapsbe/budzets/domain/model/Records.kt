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
    /**
     * Set when a category with history is deleted. The row stays for closed months, old expenses
     * and the ledger, but the category is gone from lists and pickers, and its surplus pocket
     * counts as Unallocated. A deleted category always has [archivedFrom].
     */
    val deletedAt: Instant? = null,
) {
    val isArchived: Boolean get() = archivedFrom != null
    val isDeleted: Boolean get() = deletedAt != null

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
)
