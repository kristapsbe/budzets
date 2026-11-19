package lv.kristapsbe.budzets.domain.forecast

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.LedgerEntryType
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** Q55 and the surplus chart's forecast months. The month under way is January 2026. */
class SurplusForecastTest {

    private val january = ym("2026-01")
    private val february = ym("2026-02")
    private val march = ym("2026-03")

    /** Food steady at €300 and Fun at €100 for six months: €400 a month, no spread. */
    private val forecast = SpendingForecaster.forecast(
        (1L..6L).associate { january.minusMonths(it) to mapOf(FOOD to eur(300), FUN to eur(100)) },
        emptyMap(),
        listOf(CategoryRef(FOOD), CategoryRef(FUN), CategoryRef(TRAVEL)),
        january,
        january.atDay(10),
    )!!

    private fun planned(month: YearMonth, overall: Money?, vararg plans: Pair<CategoryId, Money>) =
        MonthSummary(month, overall, false, plans.associate { (id, amount) -> id to CategorySummary(id, amount, Money.ZERO, Money.ZERO) })

    @Test
    fun `a planned month gives its plan less the likely spending, one-offs added`() {
        val planned = mapOf(
            // €500 overall, €350 of it in categories: €150 not assigned.
            february to planned(february, eur(500), FOOD to eur(250), FUN to eur(100)),
            march to planned(march, eur(500), FOOD to eur(250), FUN to eur(100)),
        )
        val ledger = listOf(
            LedgerEntry(LedgerEntryId("e1"), LedgerEntryType.ONE_OFF_CREDIT, SurplusPocket.Unallocated, eur(40), FIXED_NOW, targetMonth = march),
        )
        val months = SurplusForecast.months(planned, forecast, ledger, start = eur(1000))

        assertEquals(eur(100), months[0].result!!.expected)
        assertEquals(eur(1100), months[0].runningTotal)
        assertEquals(eur(140), months[1].result!!.expected)
        assertEquals(eur(1240), months[1].runningTotal)
        // No plan after March, so nothing more is added.
        assertNull(months[2].result)
        assertEquals(eur(1240), months.last().runningTotal)
    }

    @Test
    fun `the month under way ends where closing now would leave it, less what's still to come`() {
        val current = MonthSurplus(january, eur(300), isClosed = false, runningTotal = eur(800))
        val end = SurplusForecast.monthEnd(current, Estimate(eur(200), eur(150), eur(260)))!!
        assertEquals(Estimate(eur(100), eur(40), eur(150)), end.result)
        assertEquals(eur(600), end.runningTotal)

        // Without a budget the month gives nothing, so there is nowhere to end.
        assertNull(SurplusForecast.monthEnd(current.copy(result = null), Estimate(eur(1), eur(1), eur(1))))
    }

    @Test
    fun `the forecast months stack by band, the folded categories together`() {
        val stacks = ForecastStacks.build(forecast, kept = setOf(FOOD), displayOrder = mapOf(FOOD to 0, FUN to 1), planned = mapOf(
            february to planned(february, eur(500)),
        ))
        val feb = stacks.first()
        assertEquals(february, feb.month)
        // Fun is folded (null); the smaller band is at the bottom.
        assertEquals(listOf(null, FOOD), feb.segments.map { it.series })
        assertEquals(eur(100), feb.segments[0].estimate.expected)
        assertEquals(eur(100), feb.segments[1].below)
        assertEquals(eur(400), feb.segments.last().top)
        assertEquals(eur(400), feb.total.expected)
        assertEquals(eur(500), feb.budget)
        assertNull(stacks[1].budget)
        assertEquals(12, stacks.size)
    }
}
