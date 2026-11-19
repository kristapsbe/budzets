package lv.kristapsbe.budzets.domain.model

import java.time.YearMonth

/**
 * Daily, weekly and/or monthly spending targets in minor units. `null` means "not set"; `0` is
 * a real zero target. A target with every value null means the budget is not configured.
 */
data class BudgetTarget(val dailyMinor: Long? = null, val weeklyMinor: Long? = null, val monthlyMinor: Long? = null) {
    init {
        require(dailyMinor == null || dailyMinor >= 0) { "daily target must be >= 0" }
        require(weeklyMinor == null || weeklyMinor >= 0) { "weekly target must be >= 0" }
        require(monthlyMinor == null || monthlyMinor >= 0) { "monthly target must be >= 0" }
    }

    val isConfigured get() = dailyMinor != null || weeklyMinor != null || monthlyMinor != null
}

sealed interface BudgetScope {
    data object Overall : BudgetScope
    data class Category(val id: CategoryId) : BudgetScope
}

/** One version of a budget; applies from [effectiveFrom] until a later version of the same scope. */
data class BudgetConfig(
    val scope: BudgetScope,
    val target: BudgetTarget,
    val effectiveFrom: YearMonth,
)

/** A category as the budget engine sees it. Archived categories drop out of plans from [archivedFrom]. */
data class CategoryRef(val id: CategoryId, val archivedFrom: YearMonth? = null) {
    fun isActiveIn(month: YearMonth) = archivedFrom == null || month < archivedFrom
}
