package lv.kristapsbe.budzets.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummaryCalculator
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
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
        /** Base budget plus boosts, before any debt; zero when no budget is set. */
        val budget: Money,
        val status: PaceStatus,
        val daysLeft: Int,
        /** The category furthest over its budget, if any is over (Q16). */
        val topOver: TopOver?,
        /** The surplus pockets above zero, added up. Debt is kept apart in [debt], not netted. */
        val surplus: Money,
        /** What can be spent per day for the rest of the month (Q34); null with no budget or none left. */
        val daily: DailyAllowance? = null,
        /** What the surplus pockets below zero owe in all (Q43). */
        val debt: Money = Money.ZERO,
        /** The debt that comes out of this month: all of it while the month is open, none once it's closed. */
        val carried: Money = Money.ZERO,
    ) : WidgetState {
        /** Asked of the budget before debt: debt as large as the budget is "over", not "no budget". */
        val hasBudget: Boolean get() = budget.isPositive

        /** What the month can spend, after debt. Never shown below zero. */
        val available: Money get() = (budget - carried).let { if (it.isNegative) Money.ZERO else it }
        val remaining: Money get() = budget - carried - spent

        /** Over budget; spending with no budget set is never "over". */
        val isOver: Boolean get() = hasBudget && remaining.isNegative

        /** Share of what can be spent that is spent, clamped to the bar; a full bar once over. */
        val progress: Float
            get() = when {
                available.isPositive -> (spent.minor.toDouble() / available.minor).toFloat().coerceIn(0f, 1f)
                isOver || spent.isPositive -> 1f
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
        expenses: ExpenseRepository,
        today: LocalDate,
    ): Flow<WidgetState> =
        combine(
            budgets.observeMonthSummary(YearMonth.from(today)),
            categories.categories,
            // Paired to stay within combine's five typed flows.
            combine(surplus.balances, surplus.closedMonths, ::Pair),
            settings.currency,
            expenses.observeDailySpending(YearMonth.from(today)),
        ) { summary, all, (balances, closed), currency, byDay ->
            build(summary, today, all, balances, currency, spentToday = byDay[today] ?: Money.ZERO, closedMonths = closed)
        }
            .distinctUntilChanged()

    /**
     * As on the overview: debt comes out of the month while it is open (Q43), and in a month with
     * a budget a category with no amount set counts as zero, so it can be the one furthest over
     * (Q44). With no budget at all nothing is "over".
     */
    fun build(
        summary: MonthSummary,
        today: LocalDate,
        categories: List<Category>,
        balances: Map<SurplusPocket, Money>,
        currency: CurrencySpec,
        spentToday: Money = Money.ZERO,
        closedMonths: Set<YearMonth> = emptySet(),
    ): WidgetState {
        if (categories.all { it.isDeleted }) return WidgetState.NotSetUp
        val names = categories.associate { it.id to it.name }
        val month = MonthSummaryCalculator.withDebt(summary, balances, YearMonth.from(today), closedMonths)
        val pace = PaceCalculator.pace(month, today)
        val worst = month.categories.values
            .filter { month.overallEffective.isPositive && it.isOver }
            .minByOrNull { it.left }
        return WidgetState.Month(
            month = month.month,
            currency = currency,
            spent = month.totalSpent,
            budget = month.overallEffective,
            status = pace.status,
            daysLeft = pace.daysRemaining,
            topOver = worst?.let { TopOver(names[it.categoryId] ?: "?", -it.left) },
            surplus = SurplusLedger.saved(balances),
            daily = PaceCalculator.dailyAllowance(month, today, spentToday),
            debt = SurplusLedger.owed(balances),
            carried = month.debt,
        )
    }
}
