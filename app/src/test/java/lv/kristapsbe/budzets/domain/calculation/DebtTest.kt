package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.TRAVEL_POCKET
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.domain.surplus.closingEffect
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Q43: a pocket below zero is debt, and debt comes out of the month under way. */
class DebtTest {

    private val april = ym("2026-04") // 30 days
    private fun day(d: Int) = LocalDate.of(2026, 4, d)

    /** Overall €1,000: Food €500 with €200 spent, Fun €300 with €100 spent, €200 unassigned. */
    private val month = MonthSummary(
        april, eur(1000), false,
        mapOf(
            FOOD to CategorySummary(FOOD, eur(500), Money.ZERO, eur(200)),
            FUN to CategorySummary(FUN, eur(300), Money.ZERO, eur(100)),
        ),
    )

    private val balances = mapOf(FOOD_POCKET to eur(80), FUN_POCKET to eur(-40), SurplusPocket.Unallocated to eur(-15), TRAVEL_POCKET to Money.ZERO)

    @Test
    fun `debts are the pockets below zero, and savings aren't netted against them`() {
        assertEquals(mapOf(FUN_POCKET to eur(40), SurplusPocket.Unallocated to eur(15)), SurplusLedger.debts(balances))
        assertEquals(eur(80), SurplusLedger.saved(balances))
        assertEquals(eur(55), SurplusLedger.owed(balances))
        assertEquals(Money.ZERO, SurplusLedger.owed(emptyMap()))
        assertEquals(Money.ZERO, SurplusLedger.saved(emptyMap()))
    }

    @Test
    fun `a category's debt comes out of its own amount and the overall one`() {
        val carried = month.carrying(SurplusLedger.debts(balances))
        val fun_ = carried.categories.getValue(FUN)
        assertEquals(eur(40), fun_.debt)
        assertEquals(eur(260), fun_.available)
        assertEquals(eur(160), fun_.left)
        assertFalse(fun_.isOver)
        assertEquals(Money.ZERO, carried.categories.getValue(FOOD).debt) // its savings change nothing here

        // The unallocated pocket's debt belongs to no category: it counts against the overall only.
        assertEquals(eur(15), carried.otherDebt)
        assertEquals(eur(55), carried.debt)
        assertEquals(eur(945), carried.overallAvailable)
        assertEquals(eur(645), carried.overallLeft)
    }

    @Test
    fun `debt larger than what's left puts the category over, with nothing spent this month`() {
        val carried = month.carrying(mapOf(FUN_POCKET to eur(250)))
        val fun_ = carried.categories.getValue(FUN)
        assertEquals(eur(-50), fun_.left) // 300 − 250 − 100
        assertTrue(fun_.isOver)

        val owing = month.carrying(mapOf(TRAVEL_POCKET to eur(60)))
        val travel = owing.categories.getValue(TRAVEL) // not in the month: it gets a row, so the debt shows
        assertNull(travel.base)
        assertEquals(eur(-60), travel.left)
        assertTrue(travel.isOver)
        assertEquals(eur(940), owing.overallAvailable)
    }

    @Test
    fun `what closing posts is the same with or without debt`() {
        val carried = month.carrying(mapOf(FUN_POCKET to eur(40), TRAVEL_POCKET to eur(60), SurplusPocket.Unallocated to eur(15)))
        assertEquals(eur(200), carried.categories.getValue(FUN).remaining)
        assertEquals(closingEffect(month), closingEffect(carried))
    }

    @Test
    fun `no debt leaves the month as it is`() {
        assertSame(month, month.carrying(emptyMap()))
        assertEquals(month.overallEffective, month.overallAvailable)
        assertEquals(month.overallEffective - month.totalSpent, month.overallLeft)
        val food = month.categories.getValue(FOOD)
        assertEquals(food.remaining, food.left)
    }

    @Test
    fun `debt comes out of the month under way while it is open and has a budget`() {
        fun withDebt(summary: MonthSummary, current: String, closed: Set<String> = emptySet()) =
            MonthSummaryCalculator.withDebt(summary, balances, ym(current), closed.map(::ym).toSet())

        assertEquals(eur(55), withDebt(month, "2026-04").debt)
        // A month already closed, one that's over, and one not begun are returned as they are.
        assertSame(month, withDebt(month, "2026-04", closed = setOf("2026-04")))
        assertSame(month, withDebt(month, "2026-05"))
        assertSame(month, withDebt(month, "2026-03"))
        // An earlier month being closed changes nothing.
        assertEquals(eur(55), withDebt(month, "2026-04", closed = setOf("2026-03")).debt)

        // No budget, nothing to take it from. A boost alone is a budget.
        val unbudgeted = MonthSummary(april, null, false, mapOf(FUN to CategorySummary(FUN, null, Money.ZERO, eur(10))))
        assertSame(unbudgeted, withDebt(unbudgeted, "2026-04"))
        val boosted = MonthSummary(april, null, false, mapOf(FUN to CategorySummary(FUN, null, eur(50), eur(10))))
        assertEquals(eur(40), withDebt(boosted, "2026-04").categories.getValue(FUN).debt)
    }

    @Test
    fun `pace is measured against what's left after debt`() {
        // €300 budget, €60 debt: €240 to spend, so €80 by day 10.
        val summary = MonthSummary(april, eur(300), false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, eur(80))))
        val pace = PaceCalculator.pace(summary.carrying(mapOf(SurplusPocket.Unallocated to eur(60))), day(10))
        assertEquals(eur(80), pace.expectedSpend)
        assertEquals(PaceStatus.ON_TRACK, pace.status)
        assertEquals(PaceStatus.WATCH, PaceCalculator.pace(summary.carrying(mapOf(SurplusPocket.Unallocated to eur(100))), day(10)).status)
        // Within the budget, but not within what debt leaves of it.
        assertEquals(PaceStatus.OVER, PaceCalculator.pace(summary.carrying(mapOf(SurplusPocket.Unallocated to eur(230))), day(10)).status)
    }

    @Test
    fun `debt as large as the budget is over budget, not no budget`() {
        val summary = MonthSummary(april, eur(300), false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, Money.ZERO)))
        val swallowed = PaceCalculator.pace(summary.carrying(mapOf(SurplusPocket.Unallocated to eur(450))), day(10))
        assertEquals(PaceStatus.OVER, swallowed.status)
        assertEquals(Money.ZERO, swallowed.expectedSpend) // never negative
        assertNull(PaceCalculator.dailyAllowance(summary.carrying(mapOf(SurplusPocket.Unallocated to eur(450))), day(10), Money.ZERO))
    }

    @Test
    fun `the daily amount divides what's left after debt`() {
        // €600 budget, €100 debt, €130 spent before today: €370 over the 16 days from the 15th.
        val summary = MonthSummary(april, eur(600), false, mapOf(FOOD to CategorySummary(FOOD, eur(600), Money.ZERO, eur(150))))
        val daily = PaceCalculator.dailyAllowance(summary.carrying(mapOf(FOOD_POCKET to eur(100))), day(15), spentToday = eur(20))!!
        assertEquals(eur(23, 12), daily.perDay) // 23.125 rounded down
        assertEquals(eur(3, 12), daily.leftToday)
    }
}
