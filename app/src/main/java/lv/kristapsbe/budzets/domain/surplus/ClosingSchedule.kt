package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.CategoryRef
import java.time.YearMonth

/** Which months are open: the first one still open, and those the overview reminds about (Q15). */
object ClosingSchedule {

    /**
     * The first month from [currentMonth] on that isn't closed. A category leaves plans from here
     * rather than from [currentMonth], so one that was closed early keeps it if it's reopened.
     */
    fun firstOpenMonth(currentMonth: YearMonth, closedMonths: Set<YearMonth>): YearMonth =
        generateSequence(currentMonth) { it.plusMonths(1) }.first { it !in closedMonths }

    /**
     * Past months (before [currentMonth]) that had a positive budget and aren't closed, newest
     * first. Months with no budget are skipped: closing them would only turn spending into debits.
     * The search starts at the earliest budget version, so it is bounded by the user's history.
     */
    fun unclosedBudgetedPastMonths(
        configs: List<BudgetConfig>,
        categories: List<CategoryRef>,
        closedMonths: Set<YearMonth>,
        currentMonth: YearMonth,
    ): List<YearMonth> {
        val first = configs.minOfOrNull { it.effectiveFrom } ?: return emptyList()
        return generateSequence(currentMonth.minusMonths(1)) { it.minusMonths(1) }
            .takeWhile { it >= first }
            .filter { it !in closedMonths }
            .filter { BudgetResolver.resolve(it, configs, categories).overallBase?.isPositive == true }
            .toList()
    }
}
