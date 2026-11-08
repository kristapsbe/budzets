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

/**
 * What can be spent each day from today to the end of the month and still finish within the
 * budget (Q34). Set from what was left at the start of today, so it holds steady while today's
 * expenses come in; [leftToday] is what moves.
 */
data class DailyAllowance(val perDay: Money, val spentToday: Money) {
    /** Negative once today's spending passes [perDay]. */
    val leftToday: Money get() = perDay - spentToday
}

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

    /**
     * What was left at the start of today ÷ the days from today to the month's end, today
     * included. Rounded down, so spending it every day never goes over; everything else rounds
     * half-up. Spending before today is the month's total less [spentToday], so a future-dated
     * expense counts as already spent.
     *
     * null outside the month containing [today], with no budget, or when nothing was left at the
     * start of today.
     */
    fun dailyAllowance(summary: MonthSummary, today: LocalDate, spentToday: Money): DailyAllowance? {
        if (summary.month != YearMonth.from(today)) return null
        val effective = summary.overallEffective
        if (!effective.isPositive) return null
        val leftAtStart = effective - (summary.totalSpent - spentToday)
        if (!leftAtStart.isPositive) return null
        val days = summary.month.lengthOfMonth() - today.dayOfMonth + 1
        return DailyAllowance(Money(leftAtStart.minor / days), spentToday)
    }
}
