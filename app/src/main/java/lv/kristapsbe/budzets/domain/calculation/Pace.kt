package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.Money
import java.time.LocalDate
import java.time.YearMonth

enum class PaceStatus { NO_BUDGET, ON_TRACK, WATCH, OVER }

/**
 * @property expectedSpend pace guide: the effective budget × the fraction of days elapsed. It is a
 *   guide, not a daily allowance. null when there is no budget.
 */
data class Pace(
    val daysInMonth: Int,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val expectedSpend: Money?,
    val status: PaceStatus,
)

object PaceCalculator {
    /** "Watch" once spending is more than this percentage above the pace guide. */
    const val WATCH_THRESHOLD_PERCENT = 110L

    /** Today counts as elapsed. Past months are fully elapsed; future months have 0 days elapsed. */
    fun daysElapsed(month: YearMonth, today: LocalDate): Int {
        val current = YearMonth.from(today)
        return when {
            month < current -> month.lengthOfMonth()
            month > current -> 0
            else -> today.dayOfMonth
        }
    }

    fun pace(summary: MonthSummary, today: LocalDate): Pace {
        val days = summary.month.lengthOfMonth()
        val elapsed = daysElapsed(summary.month, today)
        val effective = summary.overallEffective
        if (!effective.isPositive) {
            return Pace(days, elapsed, days - elapsed, null, PaceStatus.NO_BUDGET)
        }
        // Rounded half-up: (2·E·elapsed + N) / 2N
        val expected = Money(
            Math.addExact(Math.multiplyExact(Math.multiplyExact(effective.minor, elapsed.toLong()), 2), days.toLong()) /
                (2L * days)
        )
        val spent = summary.totalSpent
        val status = when {
            summary.overallRemaining.isNegative -> PaceStatus.OVER
            Math.multiplyExact(spent.minor, 100) > Math.multiplyExact(expected.minor, WATCH_THRESHOLD_PERCENT) -> PaceStatus.WATCH
            else -> PaceStatus.ON_TRACK
        }
        return Pace(days, elapsed, days - elapsed, expected, status)
    }
}
