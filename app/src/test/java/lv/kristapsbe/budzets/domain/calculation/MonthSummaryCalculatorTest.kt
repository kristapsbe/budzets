package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.TRAVEL_POCKET
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MonthSummaryCalculatorTest {

    private val april = ym("2026-04")

    private fun plan(amount: Money) = MonthlyPlan(amount, amount, null, null, setOf(TargetKind.DAILY))

    private fun resolved(
        month: YearMonth = april,
        overall: Money? = eur(1000),
        derived: Boolean = false,
        cats: Map<CategoryId, Money> = mapOf(FOOD to eur(500), FUN to eur(300)),
    ) = ResolvedBudget(month, overall, null, derived, cats.mapValues { plan(it.value) })

    private fun boost(dest: CategoryId, amount: Money, month: YearMonth = april, status: BoostStatus = BoostStatus.ACTIVE) =
        Boost(BoostId("b-$dest-$amount"), month, dest, TRAVEL_POCKET, amount, status, LedgerEntryId("e"), FIXED_NOW)

    @Test
    fun `category and overall remaining`() {
        val s = MonthSummaryCalculator.summarize(resolved(), emptyList(), mapOf(FOOD to eur(200), FUN to eur(350)))
        assertEquals(eur(300), s.categories.getValue(FOOD).remaining)
        assertEquals(eur(-50), s.categories.getValue(FUN).remaining)
        assertTrue(s.categories.getValue(FUN).isOver)
        assertFalse(s.categories.getValue(FOOD).isOver)
        assertEquals(eur(550), s.totalSpent)
        assertEquals(eur(450), s.overallLeft)
    }

    @Test
    fun `boost adds to category and overall effective but not base`() {
        val s = MonthSummaryCalculator.summarize(
            resolved(),
            listOf(
                boost(FOOD, eur(100)),
                boost(FOOD, eur(20)),
                boost(FUN, eur(999), month = ym("2026-05")), // other month, ignored
                boost(FUN, eur(999), status = BoostStatus.RELEASED), // released, ignored
            ),
            mapOf(FOOD to eur(550)),
        )
        val food = s.categories.getValue(FOOD)
        assertEquals(eur(500), food.base)
        assertEquals(eur(120), food.boost)
        assertEquals(eur(620), food.effective)
        assertEquals(eur(70), food.remaining)
        assertEquals(eur(1000), s.overallBase)
        assertEquals(eur(120), s.totalBoosts)
        assertEquals(eur(1120), s.overallEffective)
    }

    @Test
    fun `category without budget but with spending or boost is included`() {
        val s = MonthSummaryCalculator.summarize(resolved(), listOf(boost(TRAVEL, eur(40))), mapOf(CategoryId("misc") to eur(10)))
        val misc = s.categories.getValue(CategoryId("misc"))
        assertNull(misc.base)
        assertEquals(eur(-10), misc.remaining)
        assertEquals(eur(40), s.categories.getValue(TRAVEL).effective)
    }

    @Test
    fun `category plans above overall raise warning, below gives unallocated base`() {
        val over = MonthSummaryCalculator.summarize(
            resolved(overall = eur(700)), emptyList(), emptyMap(),
        )
        assertTrue(over.categoryPlansExceedOverall)
        assertEquals(Money.ZERO, over.unallocatedBase)

        val under = MonthSummaryCalculator.summarize(resolved(), emptyList(), emptyMap())
        assertFalse(under.categoryPlansExceedOverall)
        assertEquals(eur(200), under.unallocatedBase)
    }

    @Test
    fun `derived overall never warns and has no unallocated base`() {
        val s = MonthSummaryCalculator.summarize(resolved(overall = eur(800), derived = true), emptyList(), emptyMap())
        assertFalse(s.categoryPlansExceedOverall)
        assertEquals(Money.ZERO, s.unallocatedBase)
    }

    @Test
    fun `with no budget everything spent is over`() {
        val s = MonthSummaryCalculator.summarize(resolved(overall = null, cats = emptyMap()), emptyList(), mapOf(FOOD to eur(5)))
        assertEquals(eur(-5), s.overallLeft)
    }
}
