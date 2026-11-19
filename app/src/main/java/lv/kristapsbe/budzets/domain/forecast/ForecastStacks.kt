package lv.kristapsbe.budzets.domain.forecast

import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import java.time.YearMonth

/**
 * One band of a forecast month's bar (Q56): a chart series' likely spending.
 *
 * @property series the category, or null for the categories folded together.
 * @property below the expected amounts of the segments underneath.
 */
data class ForecastSegment(val series: CategoryId?, val estimate: Estimate, val below: Money) {
    val top: Money get() = below + estimate.expected
}

/**
 * A forecast month's bar, stacked like a real month's: smallest at the bottom, largest on top.
 *
 * @property total the whole month's likely spending; its expected amount is the bar's height.
 * @property budget the month's planned budget, boosts already reserved for it included; null when none.
 */
data class ForecastStack(val month: YearMonth, val segments: List<ForecastSegment>, val total: Estimate, val budget: Money?)

object ForecastStacks {

    /**
     * Each forecast month split into the spending chart's bands: the categories in [kept] on their
     * own, the rest folded into one (series null), stacked as `SpendingHistory.stacks` stacks real
     * months. [planned] gives each month's budget.
     */
    fun build(
        forecast: SpendingForecast,
        kept: Set<CategoryId>,
        displayOrder: Map<CategoryId, Int>,
        planned: Map<YearMonth, MonthSummary>,
    ): List<ForecastStack> = forecast.months.map { month ->
        val ids = forecast.categoriesIn(month)
        val own = ids.filter { it in kept }.map { it as CategoryId? to forecast.estimate(month, listOf(it)) }
        val others = ids.filter { it !in kept }
        val parts = (if (others.isEmpty()) own else own + (null to forecast.estimate(month, others)))
            .filter { it.second.expected.isPositive }
        val orderOf = { id: CategoryId? -> id?.let { displayOrder[it] } ?: Int.MAX_VALUE }
        val sorted = parts.sortedWith(
            compareBy<Pair<CategoryId?, Estimate>> { it.second.expected }
                .thenBy { orderOf(it.first) }
                .thenBy { it.first?.value.orEmpty() },
        )
        var below = Money.ZERO
        val segments = sorted.map { (id, estimate) -> ForecastSegment(id, estimate, below).also { below += estimate.expected } }
        ForecastStack(month, segments, forecast.estimate(month, ids), planned[month]?.overallEffective?.takeIf { it.isPositive })
    }
}
