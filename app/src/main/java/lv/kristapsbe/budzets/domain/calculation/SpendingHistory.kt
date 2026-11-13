package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.YearMonth

/**
 * One category's part of a month's bar.
 *
 * @property series the category, or null for the categories folded together (see [SpendingHistory]).
 * @property below the total of the segments underneath, so the segment spans [below] to [below] + [amount].
 */
data class StackSegment(val series: CategoryId?, val amount: Money, val below: Money) {
    val top: Money get() = below + amount
}

/**
 * One month's bar in the spending chart (Q35–Q38).
 *
 * @property segments bottom to top, smallest to largest, so the largest category is on top.
 * @property budget the month's budget including boosts; null when there is none.
 */
data class MonthStack(val month: YearMonth, val segments: List<StackSegment>, val budget: Money?) {
    val total: Money get() = segments.lastOrNull()?.top ?: Money.ZERO
}

data class SpendingStacks(
    val months: List<MonthStack>,
    /** The categories with their own band, in display order. */
    val series: List<CategoryId>,
    /** Whether some categories were folded into one band (series null). */
    val hasOthers: Boolean,
)

object SpendingHistory {

    /** As many bands as the chart palette has colours; colours are never generated or reused. */
    const val MAX_SERIES = 8

    /**
     * Stacks each month's spending by category, the largest on top. Equal amounts go by
     * [displayOrder], so they keep their places from month to month. Past [maxSeries] categories,
     * those with the largest total over all [summaries] keep their own band and the rest share one.
     */
    fun stacks(
        summaries: List<MonthSummary>,
        displayOrder: Map<CategoryId, Int>,
        maxSeries: Int = MAX_SERIES,
    ): SpendingStacks {
        val orderOf = { id: CategoryId -> displayOrder[id] ?: Int.MAX_VALUE }
        val totals = HashMap<CategoryId, Money>()
        for (summary in summaries) {
            for ((id, category) in summary.categories) {
                if (category.spent.isPositive) totals[id] = (totals[id] ?: Money.ZERO) + category.spent
            }
        }
        val kept = if (totals.size <= maxSeries) totals.keys
        else totals.entries
            .sortedWith(compareByDescending<Map.Entry<CategoryId, Money>> { it.value }.thenBy { orderOf(it.key) }.thenBy { it.key.value })
            .take(maxSeries)
            .map { it.key }
            .toSet()
        val hasOthers = kept.size < totals.size

        val months = summaries.map { summary ->
            val spent = summary.categories.values.filter { it.spent.isPositive }
            val own = spent.filter { it.categoryId in kept }.map { it.categoryId as CategoryId? to it.spent }
            val others = spent.filter { it.categoryId !in kept }.map { it.spent }.sumMoney()
            val parts = if (others.isPositive) own + (null to others) else own
            // Smallest first, so the largest ends up on top. The folded band sorts after a
            // category of the same amount; categories of equal amounts go by display order.
            val sorted = parts.sortedWith(
                compareBy<Pair<CategoryId?, Money>> { it.second }
                    .thenBy { (id, _) -> id?.let(orderOf) ?: Int.MAX_VALUE }
                    .thenBy { (id, _) -> id?.value.orEmpty() },
            )
            var below = Money.ZERO
            val segments = sorted.map { (id, amount) -> StackSegment(id, amount, below).also { below += amount } }
            MonthStack(summary.month, segments, summary.overallEffective.takeIf { it.isPositive })
        }
        val series = kept.sortedWith(compareBy<CategoryId> { orderOf(it) }.thenBy { it.value })
        return SpendingStacks(months, series, hasOthers)
    }
}
