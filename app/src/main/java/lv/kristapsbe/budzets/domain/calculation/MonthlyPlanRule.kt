package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import java.time.YearMonth

enum class TargetKind { DAILY, WEEKLY, MONTHLY }

/**
 * A budget's planned amount for one month, with every equivalent kept so the editor can
 * show how the amount was derived.
 *
 * @property determinedBy the kinds whose equivalent equals [amount]; more than one on a tie.
 */
data class MonthlyPlan(
    val amount: Money,
    val dailyEquivalent: Money?,
    val weeklyEquivalent: Money?,
    val monthlyEquivalent: Money?,
    val determinedBy: Set<TargetKind>,
)

/**
 * The single place that turns daily/weekly/monthly targets into a monthly amount.
 *
 * - daily: `D × N`
 * - weekly: `W × N ÷ 7`, divided once and rounded half-up to a minor unit. Because 7 is odd,
 *   an exact .5 tie can't occur.
 * - monthly: the amount itself.
 * - several set: the highest monthly equivalent. They are treated as alternative expressions
 *   of one limit, not added together.
 */
object MonthlyPlanRule {

    fun dailyEquivalent(dailyMinor: Long, month: YearMonth): Money =
        Money(Math.multiplyExact(dailyMinor, month.lengthOfMonth().toLong()))

    fun weeklyEquivalent(weeklyMinor: Long, month: YearMonth): Money {
        val exactTimesSeven = Math.multiplyExact(weeklyMinor, month.lengthOfMonth().toLong())
        // Half-up for non-negative n: floor(n/7 + 0.5) == (n + 3) / 7, since the remainder is an integer.
        return Money(Math.addExact(exactTimesSeven, 3) / 7)
    }

    /** @return null when the target is not configured. */
    fun plan(target: BudgetTarget, month: YearMonth): MonthlyPlan? {
        val daily = target.dailyMinor?.let { dailyEquivalent(it, month) }
        val weekly = target.weeklyMinor?.let { weeklyEquivalent(it, month) }
        val monthly = target.monthlyMinor?.let(::Money)
        val set = listOfNotNull(daily?.let { TargetKind.DAILY to it }, weekly?.let { TargetKind.WEEKLY to it }, monthly?.let { TargetKind.MONTHLY to it })
        val amount = set.maxOfOrNull { it.second } ?: return null
        return MonthlyPlan(amount, daily, weekly, monthly, set.filter { it.second == amount }.map { it.first }.toSet())
    }
}
