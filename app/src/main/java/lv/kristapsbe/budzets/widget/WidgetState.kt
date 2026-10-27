package lv.kristapsbe.budzets.widget

import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.LocalDate
import java.time.YearMonth

/** What the home-screen widget shows: a snapshot taken when it is updated. */
sealed interface WidgetState {
    /** No categories yet, so there is nothing to budget against. */
    data object NotSetUp : WidgetState

    data class Month(
        val month: YearMonth,
        val currency: CurrencySpec,
        val spent: Money,
        /** Base budget plus boosts; zero when no budget is set. */
        val budget: Money,
        val status: PaceStatus,
        val daysLeft: Int,
        /** The category furthest over its budget, if any is over (Q16). */
        val topOver: TopOver?,
        /** Net of all surplus pockets; may be negative. */
        val surplus: Money,
    ) : WidgetState {
        val hasBudget: Boolean get() = budget.isPositive
        val remaining: Money get() = budget - spent

        /** Share of the budget spent, clamped to the bar; a full bar once over. */
        val progress: Float
            get() = when {
                hasBudget -> (spent.minor.toDouble() / budget.minor).toFloat().coerceIn(0f, 1f)
                spent.isPositive -> 1f
                else -> 0f
            }
    }
}

data class TopOver(val name: String, val overBy: Money)

object WidgetStates {

    /**
     * Only categories with a budget (or a boost) count as "over"; spending in a category without
     * one is shown as plain spending, as on the overview.
     */
    fun build(
        summary: MonthSummary,
        today: LocalDate,
        categories: List<Category>,
        balances: Map<SurplusPocket, Money>,
        currency: CurrencySpec,
    ): WidgetState {
        if (categories.isEmpty()) return WidgetState.NotSetUp
        val names = categories.associate { it.id to it.name }
        val pace = PaceCalculator.pace(summary, today)
        val worst = summary.categories.values
            .filter { (it.base != null || it.boost.isPositive) && it.isOver }
            .minByOrNull { it.remaining }
        return WidgetState.Month(
            month = summary.month,
            currency = currency,
            spent = summary.totalSpent,
            budget = summary.overallEffective,
            status = pace.status,
            daysLeft = pace.daysRemaining,
            topOver = worst?.let { TopOver(names[it.categoryId] ?: "?", -it.remaining) },
            surplus = balances.values.sumMoney(),
        )
    }
}
