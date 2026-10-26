package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import java.time.YearMonth

enum class PlanSource { DAILY, WEEKLY, BOTH_EQUAL }

/**
 * A budget's planned amount for one month, with both equivalents kept so the editor can
 * show how the amount was derived.
 */
data class MonthlyPlan(
    val amount: Money,
    val dailyEquivalent: Money?,
    val weeklyEquivalent: Money?,
    val determinedBy: PlanSource,
)

/**
 * The single place that turns daily/weekly targets into a monthly amount.
 *
 * - daily: `D × N`
 * - weekly: `W × N ÷ 7`, divided once and rounded half-up to a minor unit. Because 7 is odd,
 *   an exact .5 tie can't occur.
 * - both set: the higher monthly equivalent. They are treated as two expressions of one limit,
 *   not added together.
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
        return when {
            daily != null && weekly != null -> MonthlyPlan(
                amount = maxOf(daily, weekly),
                dailyEquivalent = daily,
                weeklyEquivalent = weekly,
                determinedBy = when {
                    daily > weekly -> PlanSource.DAILY
                    weekly > daily -> PlanSource.WEEKLY
                    else -> PlanSource.BOTH_EQUAL
                },
            )
            daily != null -> MonthlyPlan(daily, daily, null, PlanSource.DAILY)
            weekly != null -> MonthlyPlan(weekly, null, weekly, PlanSource.WEEKLY)
            else -> null
        }
    }
}
