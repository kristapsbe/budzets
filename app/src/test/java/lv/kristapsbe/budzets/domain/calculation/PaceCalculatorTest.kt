package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PaceCalculatorTest {

    private val april = ym("2026-04") // 30 days

    private fun summary(overall: Money?, spent: Money) =
        MonthSummary(april, overall, false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, spent)))

    private fun day(d: Int) = LocalDate.of(2026, 4, d)

    @Test
    fun `no budget is never on track`() {
        val p = PaceCalculator.pace(summary(null, Money.ZERO), day(10))
        assertEquals(PaceStatus.NO_BUDGET, p.status)
        assertNull(p.expectedSpend)
        assertEquals(PaceStatus.NO_BUDGET, PaceCalculator.pace(summary(Money.ZERO, eur(5)), day(10)).status)
    }

    @Test
    fun `pace guide and days`() {
        val p = PaceCalculator.pace(summary(eur(300), eur(100)), day(10))
        assertEquals(10, p.daysElapsed)
        assertEquals(20, p.daysRemaining)
        assertEquals(eur(100), p.expectedSpend)
        assertEquals(PaceStatus.ON_TRACK, p.status)
    }

    @Test
    fun `watch above 110 percent of pace, over when remaining negative`() {
        assertEquals(PaceStatus.ON_TRACK, PaceCalculator.pace(summary(eur(300), eur(110)), day(10)).status)
        assertEquals(PaceStatus.WATCH, PaceCalculator.pace(summary(eur(300), Money(11_001)), day(10)).status)
        assertEquals(PaceStatus.OVER, PaceCalculator.pace(summary(eur(300), Money(30_001)), day(30)).status)
    }

    @Test
    fun `first and last day, past and future months`() {
        val first = PaceCalculator.pace(summary(eur(300), Money.ZERO), day(1))
        assertEquals(1, first.daysElapsed)
        assertEquals(eur(10), first.expectedSpend)
        val last = PaceCalculator.pace(summary(eur(300), eur(300)), day(30))
        assertEquals(0, last.daysRemaining)
        assertEquals(PaceStatus.ON_TRACK, last.status)

        assertEquals(30, PaceCalculator.daysElapsed(april, LocalDate.of(2026, 6, 1)))
        assertEquals(0, PaceCalculator.daysElapsed(april, LocalDate.of(2026, 3, 31)))
    }

    @Test
    fun `expected spend rounds half-up`() {
        // 1.00 over 30 days, 1 day elapsed: 100/30 = 3.33 -> 3 ; 5 days: 16.67 -> 17
        assertEquals(Money(3), PaceCalculator.pace(summary(Money(100), Money.ZERO), day(1)).expectedSpend)
        assertEquals(Money(17), PaceCalculator.pace(summary(Money(100), Money.ZERO), day(5)).expectedSpend)
    }

    private fun daily(summary: MonthSummary, day: Int, spentToday: Money) =
        PaceCalculator.dailyAllowance(summary, day(day), spentToday)

    @Test
    fun `daily amount is what was left at the start of today over the days left`() {
        // €300 for April's 30 days, nothing spent: €10 a day.
        val start = daily(summary(eur(300), Money.ZERO), 1, Money.ZERO)!!
        assertEquals(eur(10), start.perDay)
        assertEquals(eur(10), start.leftToday)
        // Day 11, €100 spent before today: €200 over 20 days.
        assertEquals(eur(10), daily(summary(eur(300), eur(100)), 11, Money.ZERO)!!.perDay)
    }

    @Test
    fun `daily amount holds steady while today's spending comes in`() {
        val coffee = daily(summary(eur(300), eur(4)), 1, eur(4))!!
        assertEquals(eur(10), coffee.perDay)
        assertEquals(eur(6), coffee.leftToday)
        val more = daily(summary(eur(300), eur(13)), 1, eur(13))!!
        assertEquals(eur(10), more.perDay)
        assertEquals(eur(-3), more.leftToday)
    }

    @Test
    fun `daily amount rounds down`() {
        // €200 over the last 3 days: 66.666… → 66.66, so spending it each day stays within budget.
        assertEquals(eur(66, 66), daily(summary(eur(200), Money.ZERO), 28, Money.ZERO)!!.perDay)
    }

    @Test
    fun `on the last day the daily amount is everything that's left`() {
        assertEquals(eur(42, 50), daily(summary(eur(300), eur(257, 50)), 30, Money.ZERO)!!.perDay)
    }

    @Test
    fun `no daily amount without a budget or with nothing left at the start of today`() {
        assertNull(daily(summary(null, eur(5)), 10, Money.ZERO))
        assertNull(daily(summary(Money.ZERO, eur(5)), 10, Money.ZERO))
        assertNull(daily(summary(eur(300), eur(300)), 10, Money.ZERO))
        assertNull(daily(summary(eur(300), eur(310)), 10, Money.ZERO))
    }

    @Test
    fun `today's spending can take the month over while the daily amount stays`() {
        // €290 before today, €20 today: €10 was left at the start of day 30.
        val over = daily(summary(eur(300), eur(310)), 30, eur(20))!!
        assertEquals(eur(10), over.perDay)
        assertEquals(eur(-10), over.leftToday)
    }

    @Test
    fun `no daily amount outside the current month`() {
        val s = summary(eur(300), Money.ZERO)
        assertNull(PaceCalculator.dailyAllowance(s, LocalDate.of(2026, 5, 1), Money.ZERO))
        assertNull(PaceCalculator.dailyAllowance(s, LocalDate.of(2026, 3, 31), Money.ZERO))
    }

    @Test
    fun `boosts count towards the daily amount`() {
        val boosted = MonthSummary(
            april, eur(300), false,
            mapOf(
                FOOD to CategorySummary(FOOD, eur(200), eur(30), Money.ZERO),
                FUN to CategorySummary(FUN, null, Money.ZERO, eur(40)),
            ),
        )
        // €300 base + €30 boost, €40 spent before day 2: €290 over 29 days.
        assertEquals(eur(10), daily(boosted, 2, Money.ZERO)!!.perDay)
    }

    @Test
    fun `spending dated after today counts as spent`() {
        // €50 dated later in the month (after a clock change) is in the total but not today's.
        // €250 over 30 days: 833.33… cents → €8.33.
        assertEquals(eur(8, 33), daily(summary(eur(300), eur(50)), 1, Money.ZERO)!!.perDay)
    }
}
