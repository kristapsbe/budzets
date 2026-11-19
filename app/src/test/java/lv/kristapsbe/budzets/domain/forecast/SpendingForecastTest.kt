package lv.kristapsbe.budzets.domain.forecast

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Q54–Q56. The month under way is January 2026 unless a test says otherwise. */
class SpendingForecastTest {

    private val january = ym("2026-01")
    private val refs = listOf(CategoryRef(FOOD), CategoryRef(FUN), CategoryRef(TRAVEL))

    /** [values] (whole euros) for [id], one per month, ending the month before [current]. */
    private fun series(id: CategoryId, values: List<Long>, current: YearMonth = january): Map<YearMonth, Map<CategoryId, Money>> =
        values.indices.associate { i -> current.minusMonths((values.size - i).toLong()) to mapOf(id to eur(values[i])) }

    private fun merge(vararg maps: Map<YearMonth, Map<CategoryId, Money>>): Map<YearMonth, Map<CategoryId, Money>> =
        maps.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, parts) -> parts.reduce { a, b -> a + b } }

    private fun forecast(
        spending: Map<YearMonth, Map<CategoryId, Money>>,
        current: YearMonth = january,
        daily: Map<LocalDate, Money> = emptyMap(),
        today: LocalDate = current.atDay(10),
        categories: List<CategoryRef> = refs,
    ) = SpendingForecaster.forecast(spending, daily, categories, current, today)

    private fun Money.units(): Double = minor / 100.0

    @Test
    fun `nothing is forecast before 3 months are finished`() {
        assertNull(forecast(series(FOOD, listOf(100, 120))))
        assertEquals(2, SpendingForecaster.finishedMonths(series(FOOD, listOf(100, 120)), january))
        assertEquals(0, SpendingForecaster.finishedMonths(emptyMap(), january))
        // The month under way doesn't count.
        assertEquals(0, SpendingForecaster.finishedMonths(mapOf(january to mapOf(FOOD to eur(5))), january))
    }

    @Test
    fun `with 3 to 5 months a typical month is the median, and one odd month doesn't move it`() {
        val f = forecast(series(FOOD, listOf(100, 100, 900, 100)))!!
        assertEquals(ForecastBasis.TYPICAL_MONTH, f.basis)
        assertEquals(4, f.finishedMonths)
        assertEquals(eur(100), f.estimate(ym("2026-02")).expected)
        assertEquals(eur(100), f.estimate(ym("2026-12")).expected)
    }

    @Test
    fun `from 6 months a trend is followed, and it flattens further ahead`() {
        val f = forecast(series(FOOD, (0L..9L).map { 100 + 10 * it }))!!
        assertEquals(ForecastBasis.TREND, f.basis)
        // Level €190 in October; one month on, 0.9 of the €10 slope.
        assertEquals(199.0, f.estimate(january).expected.units(), 0.01)
        val a = f.estimate(ym("2026-02")).expected.units()
        val b = f.estimate(ym("2026-03")).expected.units()
        val far = f.estimate(ym("2027-01")).expected.units()
        assertEquals(10 * 0.81, a - 199.0, 0.01)
        assertTrue("each step is smaller", b - a < a - 199.0)
        assertTrue("12 steps of €10 undamped would reach €320", far < 260)
    }

    @Test
    fun `with 12 months the yearly pattern comes through at half its size, with 24 at two thirds`() {
        // Flat at €100, December €150 and June €50.
        fun year() = (1..12).map { month -> when (month) { 12 -> 150L; 6 -> 50L; else -> 100L } }
        val one = forecast(series(FOOD, year()))!!
        assertEquals(ForecastBasis.TREND_AND_SEASON, one.basis)
        assertEquals(125.0, one.estimate(ym("2026-12")).expected.units(), 0.5)
        assertEquals(75.0, one.estimate(ym("2026-06")).expected.units(), 0.5)
        assertEquals(100.0, one.estimate(ym("2026-03")).expected.units(), 0.5)

        val two = forecast(series(FOOD, year() + year()))!!
        assertEquals(100.0 + 50.0 * 2 / 3, two.estimate(ym("2026-12")).expected.units(), 0.5)
    }

    @Test
    fun `a new category is flat at its mean, and an archived one stops`() {
        val spending = merge(
            series(FOOD, listOf(100, 100, 100, 100, 100, 100)),
            series(FUN, listOf(40, 60)),
            series(TRAVEL, listOf(50, 50, 50, 50, 50, 50)),
        )
        val categories = listOf(CategoryRef(FOOD), CategoryRef(FUN), CategoryRef(TRAVEL, archivedFrom = ym("2026-04")))
        val f = forecast(spending, categories = categories)!!
        assertEquals(eur(50), f.estimate(ym("2026-02"), listOf(FUN)).expected)
        assertEquals(setOf(FOOD, FUN, TRAVEL), f.categoriesIn(ym("2026-03")))
        assertEquals(setOf(FOOD, FUN), f.categoriesIn(ym("2026-04")))
        assertEquals(eur(150), f.estimate(ym("2026-04")).expected)
    }

    @Test
    fun `groups add up to the cent, and a group is less uncertain than its parts added up`() {
        // Food and Fun swing opposite ways, so together they hardly move.
        val food = listOf(100L, 120, 80, 100, 120, 80, 100, 120)
        val fun_ = food.map { 200 - it }
        val f = forecast(merge(series(FOOD, food), series(FUN, fun_)))!!
        val month = ym("2026-03")
        val foodOnly = f.estimate(month, listOf(FOOD))
        val funOnly = f.estimate(month, listOf(FUN))
        val both = f.estimate(month)
        assertEquals(foodOnly.expected + funOnly.expected, both.expected)
        val parts = (foodOnly.high - foodOnly.low) + (funOnly.high - funOnly.low)
        assertTrue("together ${both.high - both.low} < apart $parts", (both.high - both.low) < parts)
        assertEquals(f.categoriesIn(month).map { f.estimate(month, listOf(it)).expected }.reduce { a, b -> a + b }, both.expected)
    }

    @Test
    fun `the range widens further ahead and never goes below zero`() {
        val f = forecast(series(FOOD, listOf(10, 200, 15, 180, 5, 220, 20, 190)))!!
        val near = f.estimate(ym("2026-02"))
        val far = f.estimate(ym("2027-01"))
        assertTrue(far.high - far.low > near.high - near.low)
        assertTrue(near.low >= Money.ZERO && far.low >= Money.ZERO)
        assertTrue(near.low <= near.expected && near.expected <= near.high)
    }

    @Test
    fun `rent on the 1st means most of a month is spent early, and the month end says so`() {
        val months = (1L..6L).map { january.minusMonths(it) }
        // €500 on the 1st, then €10 a day.
        val daily = months.flatMap { m -> (1..m.lengthOfMonth()).map { d -> m.atDay(d) to if (d == 1) eur(500) else eur(10) } }.toMap() +
            mapOf(january.atDay(1) to eur(500))
        val spending = months.associateWith { m -> mapOf(FOOD to eur(500 + 10L * (m.lengthOfMonth() - 1))) }
        val f = forecast(spending, daily = daily, today = january.atDay(1))!!
        val end = f.monthEnd!!
        assertEquals(eur(500), end.spentSoFar)
        assertEquals(1, end.today)
        assertEquals(31, end.path.size)
        // An even month would still have 30/31 of ~€800 to come; this one has about €300.
        assertTrue("rest ${end.rest.expected}", end.rest.expected.units() in 250.0..350.0)
        assertEquals(end.spentSoFar + end.rest.expected, end.total.expected)
        assertEquals(end.spentSoFar, end.path.first().expected)
        assertTrue(end.total.low <= end.total.expected && end.total.expected <= end.total.high)
    }

    @Test
    fun `without enough months that spent anything, a month is taken to run evenly`() {
        val spending = series(FOOD, listOf(310, 310, 310))
        val f = forecast(spending, daily = mapOf(january.atDay(1) to eur(10)), today = january.atDay(15))!!
        val end = f.monthEnd!!
        // €310 over 31 days, 16 of them still to come.
        assertEquals(160.0, end.rest.expected.units(), 0.01)
    }

    @Test
    fun `there is no month end once today is past the month under way`() {
        val f = forecast(series(FOOD, listOf(100, 100, 100)), today = LocalDate.of(2026, 2, 1))
        assertNotNull(f)
        assertNull(f!!.monthEnd)
    }

    @Test
    fun `twelve months are forecast after the one under way`() {
        val f = forecast(series(FOOD, listOf(100, 100, 100)))!!
        assertEquals(ym("2026-02"), f.months.first())
        assertEquals(ym("2027-01"), f.months.last())
        assertEquals(12, f.months.size)
    }
}
