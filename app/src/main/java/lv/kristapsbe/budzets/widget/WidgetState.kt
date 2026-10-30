package lv.kristapsbe.budzets.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
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

/** What the home-screen widget shows. */
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

        /** Over budget; spending with no budget set is never "over". */
        val isOver: Boolean get() = hasBudget && remaining.isNegative

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
     * Live state for the month containing [today]: re-emits whenever anything the widget shows
     * changes. Equal states are dropped so a session doesn't push identical views.
     */
    fun observe(
        budgets: BudgetRepository,
        categories: CategoryRepository,
        surplus: SurplusRepository,
        settings: SettingsRepository,
        today: LocalDate,
    ): Flow<WidgetState> =
        combine(
            budgets.observeMonthSummary(YearMonth.from(today)),
            categories.categories,
            surplus.balances,
            settings.currency,
        ) { summary, all, balances, currency -> build(summary, today, all, balances, currency) }
            .distinctUntilChanged()

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
        if (categories.all { it.isDeleted }) return WidgetState.NotSetUp
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
