package lv.kristapsbe.budzets.widget

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetStateTest {

    private val april = ym("2026-04")
    private val today = LocalDate.of(2026, 4, 15)
    private fun category(id: CategoryId, name: String) = Category(id, name, null, 0, null, FIXED_NOW, FIXED_NOW)
    private val categories = listOf(category(FOOD, "Food"), category(FUN, "Fun"), category(TRAVEL, "Travel"))

    private fun build(
        summary: MonthSummary,
        balances: Map<SurplusPocket, Money> = emptyMap(),
        cats: List<Category> = categories,
        spentToday: Money = Money.ZERO,
    ) = WidgetStates.build(summary, today, cats, balances, CurrencySpec.DEFAULT, spentToday)

    @Test
    fun `no categories means not set up`() {
        assertEquals(WidgetState.NotSetUp, build(MonthSummary(april, null, false, emptyMap()), cats = emptyList()))
    }

    @Test
    fun `only deleted categories means not set up`() {
        val deleted = category(FOOD, "Food").copy(archivedFrom = april, deletedAt = FIXED_NOW)
        assertEquals(WidgetState.NotSetUp, build(MonthSummary(april, null, false, emptyMap()), cats = listOf(deleted)))
    }

    @Test
    fun `budgeted month - remaining, progress, status and days left`() {
        val summary = MonthSummary(april, eur(600), false, mapOf(FOOD to CategorySummary(FOOD, eur(300), Money.ZERO, eur(150))))
        val state = build(summary, mapOf(FOOD_POCKET to eur(80), SurplusPocket.Unallocated to eur(-30))) as WidgetState.Month
        assertEquals(eur(450), state.remaining)
        assertEquals(0.25f, state.progress)
        assertEquals(PaceStatus.ON_TRACK, state.status)
        assertEquals(15, state.daysLeft) // today counts as elapsed
        assertEquals(eur(50), state.surplus)
        assertNull(state.topOver)
    }

    @Test
    fun `daily amount from what was left at the start of today`() {
        val summary = MonthSummary(april, eur(600), false, mapOf(FOOD to CategorySummary(FOOD, eur(300), Money.ZERO, eur(150))))
        // €130 before today, €20 today: €470 over the 16 days from the 15th.
        val daily = (build(summary, spentToday = eur(20)) as WidgetState.Month).daily!!
        assertEquals(eur(29, 37), daily.perDay)
        assertEquals(eur(9, 37), daily.leftToday)
    }

    @Test
    fun `no daily amount when over budget or without one`() {
        val over = MonthSummary(april, eur(100), false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, eur(120))))
        assertNull((build(over) as WidgetState.Month).daily)
        val none = MonthSummary(april, null, false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, eur(12))))
        assertNull((build(none) as WidgetState.Month).daily)
    }

    @Test
    fun `most over-budget category, ignoring spending in categories without a budget`() {
        val summary = MonthSummary(
            april, eur(100), false,
            mapOf(
                FOOD to CategorySummary(FOOD, eur(50), Money.ZERO, eur(60)), // over by 10
                FUN to CategorySummary(FUN, eur(20), eur(5), eur(55)), // over by 30, boost counted
                TRAVEL to CategorySummary(TRAVEL, null, Money.ZERO, eur(500)), // no budget: not "over"
            ),
        )
        val state = build(summary) as WidgetState.Month
        assertEquals(TopOver("Fun", eur(30)), state.topOver)
        assertEquals(1f, state.progress) // the bar is full once over
        assertTrue(state.isOver)
        assertEquals(PaceStatus.OVER, state.status)
    }

    @Test
    fun `no budget - spending only, no progress claims`() {
        val summary = MonthSummary(april, null, false, mapOf(FOOD to CategorySummary(FOOD, null, Money.ZERO, eur(12))))
        val state = build(summary) as WidgetState.Month
        assertFalse(state.hasBudget)
        assertFalse(state.isOver) // a full bar, but not shown as over
        assertEquals(PaceStatus.NO_BUDGET, state.status)
        assertEquals(eur(12), state.spent)
        assertEquals(0f, (build(MonthSummary(april, null, false, emptyMap())) as WidgetState.Month).progress)
    }

    @Test
    fun `refresh runs just after the next local midnight, including across a month end and a DST gap`() {
        val riga = ZoneId.of("Europe/Riga")
        val endOfOctober = ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, riga)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 1, 0, 0, riga).toInstant(), WidgetSchedule.nextRefreshAt(endOfOctober))

        // Chile skips 00:00-01:00 on 6 September 2026: the day starts at 01:00.
        val santiago = ZoneId.of("America/Santiago")
        val beforeGap = ZonedDateTime.of(2026, 9, 5, 22, 0, 0, 0, santiago)
        val at = WidgetSchedule.nextRefreshAt(beforeGap).atZone(santiago)
        assertEquals(LocalDate.of(2026, 9, 6), at.toLocalDate())
        assertEquals(1, at.hour)
        assertEquals(1, at.minute)
    }
}
