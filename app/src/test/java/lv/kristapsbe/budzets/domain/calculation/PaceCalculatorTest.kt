package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
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
}
