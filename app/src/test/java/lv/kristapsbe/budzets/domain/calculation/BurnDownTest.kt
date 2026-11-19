package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Q50–Q52: what is left of a month's budget, day by day. Today is 2026-04-15, in Riga. */
class BurnDownTest {

    private val zone = ZoneId.of("Europe/Riga")
    private val april = ym("2026-04") // 30 days
    private val today = LocalDate.of(2026, 4, 15)
    private fun day(d: Int) = LocalDate.of(2026, 4, d)

    /** Overall €1,000; Food €600 and Fun €400, with [boosts] on top as the summary sees them. */
    private fun summary(
        month: YearMonth = april,
        spent: Money = Money.ZERO,
        foodBoost: Money = Money.ZERO,
        overall: Money? = eur(1000),
    ) = MonthSummary(
        month, overall, false,
        mapOf(
            FOOD to CategorySummary(FOOD, eur(600), foodBoost, spent),
            FUN to CategorySummary(FUN, eur(400), Money.ZERO, Money.ZERO),
        ),
    )

    private var ids = 0
    private fun boost(amount: Money, createdAt: String, month: YearMonth = april, status: BoostStatus = BoostStatus.ACTIVE) = Boost(
        BoostId("b${++ids}"), month, FOOD, SurplusPocket.Unallocated, amount, status, LedgerEntryId("e$ids"), Instant.parse(createdAt),
    )

    private fun of(
        summary: MonthSummary,
        boosts: List<Boost> = emptyList(),
        daily: Map<LocalDate, Money> = emptyMap(),
        on: LocalDate = today,
    ) = BurnDownCalculator.of(summary, boosts, daily, on, zone)

    @Test
    fun `without boosts it starts at the budget and falls with each day's spending, to today`() {
        val daily = mapOf(day(3) to eur(40), day(10) to eur(60))
        val burnDown = of(summary(spent = eur(100)), daily = daily)!!

        assertEquals(eur(1000), burnDown.start)
        assertEquals(15, burnDown.days.size)
        assertEquals((1..15).toList(), burnDown.days.map { it.day })
        assertEquals(eur(1000), burnDown.days[1].left) // the 2nd
        assertEquals(eur(960), burnDown.days[2].left) // the 3rd
        assertEquals(eur(40), burnDown.days[2].spent)
        assertEquals(eur(900), burnDown.left)
        assertEquals(Money.ZERO, burnDown.boostsAdded)
        assertFalse(burnDown.isComplete)
        assertTrue(burnDown.days.all { it.available == eur(1000) })
    }

    @Test
    fun `a boost lifts the line on the day it was added`() {
        val boosts = listOf(boost(eur(50), "2026-04-10T08:00:00Z"))
        val burnDown = of(summary(spent = eur(100), foodBoost = eur(50)), boosts, mapOf(day(3) to eur(100)))!!

        assertEquals(eur(1000), burnDown.start)
        assertEquals(eur(1000), burnDown.days[8].available) // the 9th
        assertEquals(Money.ZERO, burnDown.days[8].boostAdded)
        assertEquals(eur(50), burnDown.days[9].boostAdded) // the 10th
        assertEquals(eur(1050), burnDown.days[9].available)
        assertEquals(eur(900), burnDown.days[8].left)
        assertEquals(eur(950), burnDown.days[9].left)
        assertEquals(eur(50), burnDown.boostsAdded)
        assertEquals(eur(1050), burnDown.available)
    }

    @Test
    fun `the day a boost was added is the day in the app's zone`() {
        // 01:30 on the 10th in Riga, still the 9th in UTC.
        val boosts = listOf(boost(eur(50), "2026-04-09T22:30:00Z"))
        val burnDown = of(summary(foodBoost = eur(50)), boosts)!!
        assertEquals(eur(50), burnDown.days[9].boostAdded)
    }

    @Test
    fun `a boost added before the month began is in the start`() {
        val boosts = listOf(boost(eur(50), "2026-03-28T10:00:00Z"), boost(eur(20), "2026-04-01T10:00:00Z"))
        val burnDown = of(summary(foodBoost = eur(70)), boosts)!!

        assertEquals(eur(1050), burnDown.start)
        assertEquals(eur(20), burnDown.days[0].boostAdded)
        assertEquals(eur(20), burnDown.boostsAdded)
        assertEquals(eur(1070), burnDown.left)
    }

    @Test
    fun `released boosts, other months' boosts and other months' spending are left out`() {
        val boosts = listOf(
            boost(eur(30), "2026-04-05T10:00:00Z", status = BoostStatus.RELEASED),
            boost(eur(40), "2026-04-05T10:00:00Z", month = ym("2026-05")),
        )
        val daily = mapOf(LocalDate.of(2026, 3, 31) to eur(5), LocalDate.of(2026, 5, 1) to eur(7))
        val burnDown = of(summary(), boosts, daily)!!
        assertEquals(eur(1000), burnDown.start)
        assertEquals(eur(1000), burnDown.left)
        assertTrue(burnDown.days.all { it.boostAdded.isZero && it.spent.isZero })
    }

    @Test
    fun `debt carried into the month comes off the start, and the end is the Overview's left`() {
        val carried = MonthSummaryCalculator.withDebt(
            summary(spent = eur(100), foodBoost = eur(50)),
            mapOf(FUN_POCKET to eur(-120), SurplusPocket.Unallocated to eur(30)),
            currentMonth = april,
            closedMonths = emptySet(),
        )
        val boosts = listOf(boost(eur(50), "2026-04-12T10:00:00Z"))
        val burnDown = of(carried, boosts, mapOf(day(2) to eur(100)))!!

        assertEquals(eur(120), burnDown.debt)
        assertEquals(eur(880), burnDown.start)
        assertEquals(carried.overallLeft, burnDown.left)
        assertEquals(eur(830), burnDown.left)
    }

    @Test
    fun `debt larger than the budget starts below zero but still has a line`() {
        val carried = summary().carrying(mapOf(FUN_POCKET to eur(1200)))
        val burnDown = of(carried)!!
        assertEquals(eur(-200), burnDown.start)
        assertEquals(eur(-200), burnDown.left)
        // Nothing to spend evenly: the guide stays where the start is.
        assertEquals(eur(-200), burnDown.guide(burnDown.available, 15))
    }

    @Test
    fun `the guide on today is the Overview's pace guide as an amount left`() {
        val month = summary(spent = eur(333), foodBoost = eur(77, 77))
        val boosts = listOf(boost(eur(77, 77), "2026-04-06T10:00:00Z"))
        val burnDown = of(month, boosts, mapOf(day(4) to eur(333)))!!
        val pace = PaceCalculator.pace(month, today)

        assertEquals(month.overallAvailable - pace.expectedSpend!!, burnDown.guide(burnDown.available, burnDown.days.size))
        // It starts at the start and reaches zero at the month's end.
        assertEquals(burnDown.start, burnDown.guide(burnDown.start, 0))
        assertEquals(Money.ZERO, burnDown.guide(burnDown.available, 30))
    }

    @Test
    fun `spending dated after today counts on today`() {
        val daily = mapOf(day(3) to eur(10), day(20) to eur(25))
        val burnDown = of(summary(spent = eur(35)), daily = daily)!!
        assertEquals(15, burnDown.days.size)
        assertEquals(eur(25), burnDown.days.last().spent)
        assertEquals(eur(965), burnDown.left)
    }

    @Test
    fun `a past month runs to its last day, February's 28th included`() {
        val february = ym("2026-02")
        val daily = mapOf(LocalDate.of(2026, 2, 28) to eur(80))
        val burnDown = of(summary(month = february, spent = eur(80)), daily = daily)!!
        assertEquals(28, burnDown.days.size)
        assertTrue(burnDown.isComplete)
        assertEquals(eur(920), burnDown.left)
        assertEquals(Money.ZERO, burnDown.guide(burnDown.available, 28))
    }

    @Test
    fun `a month after today's has no days yet`() {
        val burnDown = of(summary(month = ym("2026-05")))!!
        assertTrue(burnDown.days.isEmpty())
        assertEquals(burnDown.start, burnDown.left)
    }

    @Test
    fun `a month without a budget has none`() {
        assertNull(of(summary(overall = null).copy(categories = emptyMap())))
        // A budget made of category amounts alone (derived overall) counts.
        assertNotNull(of(MonthSummary(april, eur(10), true, mapOf(FOOD to CategorySummary(FOOD, eur(10), Money.ZERO, Money.ZERO)))))
    }
}
