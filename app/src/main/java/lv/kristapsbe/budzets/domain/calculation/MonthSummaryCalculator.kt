package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import java.time.YearMonth

data class CategorySummary(
    val categoryId: CategoryId,
    /** null when the category has no budget configured for the month. */
    val base: Money?,
    val boost: Money,
    val spent: Money,
) {
    val effective: Money get() = (base ?: Money.ZERO) + boost
    val remaining: Money get() = effective - spent
    val isOver: Boolean get() = remaining.isNegative
}

data class MonthSummary(
    val month: YearMonth,
    val overallBase: Money?,
    val overallDerived: Boolean,
    val categories: Map<CategoryId, CategorySummary>,
) {
    val totalBoosts: Money get() = categories.values.map { it.boost }.sumMoney()
    val categoryBaseTotal: Money get() = categories.values.mapNotNull { it.base }.sumMoney()

    /** Boosts raise the overall cap as well as the category's, because they are funded by saved surplus. */
    val overallEffective: Money get() = (overallBase ?: Money.ZERO) + totalBoosts
    val totalSpent: Money get() = categories.values.map { it.spent }.sumMoney()
    val overallRemaining: Money get() = overallEffective - totalSpent

    /** Display-only ratio (not money). null when there is no positive effective budget. */
    val spentRatio: Double?
        get() = if (overallEffective.isPositive) totalSpent.minor.toDouble() / overallEffective.minor else null

    /** Warning only; neither value is changed. */
    val categoryPlansExceedOverall: Boolean
        get() = !overallDerived && overallBase != null && categoryBaseTotal > overallBase

    /** Overall base not assigned to any category. Becomes unallocated surplus when the month closes. */
    val unallocatedBase: Money
        get() {
            if (overallDerived || overallBase == null) return Money.ZERO
            val rest = overallBase - categoryBaseTotal
            return if (rest.isPositive) rest else Money.ZERO
        }
}

object MonthSummaryCalculator {

    /**
     * Includes every category that has a plan, an active boost, or spending in the month. That
     * also covers archived categories and categories without a budget that still have expenses.
     */
    fun summarize(
        resolved: ResolvedBudget,
        boosts: Iterable<Boost>,
        spending: Map<CategoryId, Money>,
    ): MonthSummary {
        val month = resolved.month
        val boostByCategory = activeBoosts(boosts, month)

        val ids = LinkedHashSet<CategoryId>().apply {
            addAll(resolved.categoryPlans.keys)
            addAll(boostByCategory.keys)
            addAll(spending.keys)
        }
        val categories = ids.associateWith { id ->
            CategorySummary(
                categoryId = id,
                base = resolved.categoryPlans[id]?.amount,
                boost = boostByCategory[id] ?: Money.ZERO,
                spent = spending[id] ?: Money.ZERO,
            )
        }
        return MonthSummary(month, resolved.overallBase, resolved.overallDerived, categories)
    }

    /**
     * A closed month: its budgets are those of [snapshot], so later budget and category changes
     * don't reach it. Boosts and spending stay live, so a corrected expense still shows as a
     * change since closing (Q3).
     */
    fun summarizeClosed(
        snapshot: MonthSummary,
        boosts: Iterable<Boost>,
        spending: Map<CategoryId, Money>,
    ): MonthSummary {
        val plans = snapshot.categories.mapValues { (_, category) -> category.base }
        val boostByCategory = activeBoosts(boosts, snapshot.month)
        val categories = (plans.keys + boostByCategory.keys + spending.keys).associateWith { id ->
            CategorySummary(id, plans[id], boostByCategory[id] ?: Money.ZERO, spending[id] ?: Money.ZERO)
        }
        return snapshot.copy(categories = categories)
    }

    private fun activeBoosts(boosts: Iterable<Boost>, month: YearMonth): Map<CategoryId, Money> =
        boosts
            .filter { it.targetMonth == month && it.status == BoostStatus.ACTIVE }
            .groupBy { it.destination }
            .mapValues { (_, list) -> list.map { it.amount }.sumMoney() }
}
