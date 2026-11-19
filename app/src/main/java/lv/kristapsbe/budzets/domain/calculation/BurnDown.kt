package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.forecast.Estimate
import lv.kristapsbe.budzets.domain.forecast.MonthEnd
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * One day of a [BurnDown].
 *
 * @property available what the month can spend once this day's boosts are in: the start plus every
 *   boost added up to and including this day.
 * @property boostAdded the boosts added on this day; already part of [available].
 * @property spent what was spent on this day.
 * @property left what is left at the end of the day: [available] less everything spent so far.
 */
data class BurnDownDay(
    val day: Int,
    val available: Money,
    val boostAdded: Money,
    val spent: Money,
    val left: Money,
)

/**
 * What is left of a month's budget, day by day (Q50–Q52): it starts at what the month can spend on
 * its first day, falls with each day's spending and rises on the day a boost is added.
 *
 * @property start what the month could spend before its first day: the base budget and the boosts
 *   added before the month began, less [debt].
 * @property debt the debt taken out of the month (Q43); zero except in the open month under way.
 * @property days from day 1 to today in the month under way, or to the month's end in a past month.
 * @property projection in the month under way, what will likely be left at the end of each day
 *   from the last one in [days] to the month's end (Q53); null without a forecast.
 */
data class BurnDown(
    val month: YearMonth,
    val start: Money,
    val debt: Money,
    val days: List<BurnDownDay>,
    val projection: List<Estimate>? = null,
) {
    val daysInMonth: Int get() = month.lengthOfMonth()

    /** What is left after the last day shown; the Overview's "left" in the month under way. */
    val left: Money get() = days.lastOrNull()?.left ?: start

    /** Boosts added during the month, after it started. */
    val boostsAdded: Money get() = days.map { it.boostAdded }.sumMoney()

    /** What the month can spend now: the start and every boost since. */
    val available: Money get() = days.lastOrNull()?.available ?: start

    /** Whether every day of the month is shown. */
    val isComplete: Boolean get() = days.size == daysInMonth

    /**
     * The pace guide as an amount left (Q51): what would be left after [elapsed] days spending
     * [available] evenly over the month. On today it is the Overview's pace guide taken from what
     * the month can spend, rounded the same way, so the two agree on whether spending is ahead.
     */
    fun guide(available: Money, elapsed: Int): Money =
        available - PaceCalculator.expectedSpend(available, elapsed, daysInMonth)

    /** What will likely be left at the month's end; null without a [projection]. */
    val likelyLeft: Estimate? get() = projection?.lastOrNull()

    /**
     * With [monthEnd]'s spending to come taken off what is [left] now. Spending more leaves less,
     * so each range turns round. Only for the month [monthEnd] is about, from its last day shown.
     */
    fun withProjection(monthEnd: MonthEnd): BurnDown {
        if (monthEnd.month != month || monthEnd.today != days.size) return this
        val spent = monthEnd.spentSoFar
        return copy(projection = monthEnd.path.map { Estimate(left - (it.expected - spent), left - (it.high - spent), left - (it.low - spent)) })
    }
}

object BurnDownCalculator {

    /**
     * [summary]'s month as a [BurnDown], or null when it has no budget (as the pace guide has
     * none). [summary] is the month as the Overview sees it, debt already taken out where Q43 says
     * so (`MonthSummaryCalculator.withDebt`), so the last day ends at its `overallLeft`.
     *
     * A boost counts from the day it was added in [zone]. One added before the month is in the
     * start; one dated after the last day shown (only after a clock change) counts on that day.
     * Released boosts are left out, as the summary leaves them out. One-off boosts change no budget
     * figure (Q29), so they aren't here at all.
     *
     * [dailySpending] may hold other months' days; only [summary]'s are read. Spending dated after
     * today counts on today, as the daily amount counts it as already spent (Q34). A month after
     * today's has no days yet.
     */
    fun of(
        summary: MonthSummary,
        boosts: Iterable<Boost>,
        dailySpending: Map<LocalDate, Money>,
        today: LocalDate,
        zone: ZoneId,
    ): BurnDown? {
        if (!summary.overallEffective.isPositive) return null
        val month = summary.month
        val lastDay = PaceCalculator.daysElapsed(month, today)
        val first = month.atDay(1)

        // Indexed by day of the month; index 0 is unused.
        val added = MutableList(lastDay + 1) { Money.ZERO }
        val spent = MutableList(lastDay + 1) { Money.ZERO }
        if (lastDay > 0) {
            for (boost in boosts) {
                if (boost.targetMonth != month || boost.status != BoostStatus.ACTIVE) continue
                val date = boost.createdAt.atZone(zone).toLocalDate()
                if (date < first) continue // in the start
                val day = if (YearMonth.from(date) == month) minOf(date.dayOfMonth, lastDay) else lastDay
                added[day] += boost.amount
            }
            for ((date, amount) in dailySpending) {
                if (YearMonth.from(date) != month) continue
                val day = minOf(date.dayOfMonth, lastDay)
                spent[day] += amount
            }
        }

        // What the month can spend now, less what was added during it, is what it started with.
        val start = summary.overallAvailable - added.sumMoney()
        var available = start
        var left = start
        val days = (1..lastDay).map { day ->
            available += added[day]
            left += added[day] - spent[day]
            BurnDownDay(day, available, added[day], spent[day], left)
        }
        return BurnDown(month, start, summary.debt, days)
    }
}
