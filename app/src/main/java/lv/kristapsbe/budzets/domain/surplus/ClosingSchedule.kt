package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.CategoryRef
import java.time.YearMonth

/** Which months still need closing, for the overview's reminder (Q15). */
object ClosingSchedule {

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
