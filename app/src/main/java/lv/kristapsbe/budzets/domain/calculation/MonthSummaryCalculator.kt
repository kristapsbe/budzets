package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import java.time.YearMonth

data class CategorySummary(
    val categoryId: CategoryId,
    /** null when no amount is set for the month. Every figure counts that as zero (Q44). */
    val base: Money?,
    val boost: Money,
    val spent: Money,
    /** What the category's surplus pocket owes, when that comes out of this month (Q43); see [MonthSummary.carrying]. */
    val debt: Money = Money.ZERO,
) {
    val effective: Money get() = (base ?: Money.ZERO) + boost

    /** The budget less spending. Closing the month posts this, so debt is not part of it. */
    val remaining: Money get() = effective - spent

    /** What the month can spend here: the budget less the debt carried in. Below zero when the debt is larger. */
    val available: Money get() = effective - debt

    /** What is left to spend; negative when over. */
    val left: Money get() = available - spent
    val isOver: Boolean get() = left.isNegative
}

data class MonthSummary(
    val month: YearMonth,
    val overallBase: Money?,
    val overallDerived: Boolean,
    val categories: Map<CategoryId, CategorySummary>,
    /** Debt carried in that belongs to no category: the unallocated pocket's. */
    val otherDebt: Money = Money.ZERO,
) {
    val totalBoosts: Money get() = categories.values.map { it.boost }.sumMoney()
    val categoryBaseTotal: Money get() = categories.values.mapNotNull { it.base }.sumMoney()

    /** Boosts raise the overall cap as well as the category's, because they are funded by saved surplus. */
    val overallEffective: Money get() = (overallBase ?: Money.ZERO) + totalBoosts
    val totalSpent: Money get() = categories.values.map { it.spent }.sumMoney()
    val overallRemaining: Money get() = overallEffective - totalSpent

    /** All the debt that comes out of this month; zero unless it came through [carrying]. */
    val debt: Money get() = categories.values.map { it.debt }.sumMoney() + otherDebt

    /** What the month can spend: the budget and boosts, less [debt]. Below zero when the debt is larger. */
    val overallAvailable: Money get() = overallEffective - debt

    /** What is left to spend; negative when over. */
    val overallLeft: Money get() = overallAvailable - totalSpent

    /**
     * This month with [debts] taken out of it (Q43): each category's own, and the unallocated
     * pocket's under [otherDebt]. A category that owes but has no plan, boost or spending here gets
     * a row, so its debt shows where it bites. Only for showing what can still be spent: closing
     * reads [CategorySummary.remaining], which debt doesn't touch.
     */
    fun carrying(debts: Map<SurplusPocket, Money>): MonthSummary {
        if (debts.isEmpty()) return this
        val owed = debts.mapNotNull { (pocket, amount) -> (pocket as? SurplusPocket.Category)?.let { it.id to amount } }.toMap()
        val rows = (categories.keys + owed.keys).associateWith { id ->
            (categories[id] ?: CategorySummary(id, null, Money.ZERO, Money.ZERO)).copy(debt = owed[id] ?: Money.ZERO)
        }
        return copy(categories = rows, otherDebt = debts[SurplusPocket.Unallocated] ?: Money.ZERO)
    }

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
     * also covers archived categories and categories with no amount set that still have expenses.
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

    /**
     * [summary] as the screens that show what can still be spent see it. Debt comes out of the
     * month under way until that month is closed (Q43). A month with no budget has nothing to take
     * it from, and every other month is returned as it is. [balances] are per pocket, with deleted
     * categories already counted as Unallocated.
     */
    fun withDebt(
        summary: MonthSummary,
        balances: Map<SurplusPocket, Money>,
        currentMonth: YearMonth,
        closedMonths: Set<YearMonth>,
    ): MonthSummary {
        val carries = summary.month == currentMonth && summary.month !in closedMonths && summary.overallEffective.isPositive
        return if (carries) summary.carrying(SurplusLedger.debts(balances)) else summary
    }

    private fun activeBoosts(boosts: Iterable<Boost>, month: YearMonth): Map<CategoryId, Money> =
        boosts
            .filter { it.targetMonth == month && it.status == BoostStatus.ACTIVE }
            .groupBy { it.destination }
            .mapValues { (_, list) -> list.map { it.amount }.sumMoney() }
}
