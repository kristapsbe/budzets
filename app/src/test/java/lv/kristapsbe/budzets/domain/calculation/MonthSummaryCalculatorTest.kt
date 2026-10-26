package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.TRAVEL_POCKET
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.ExpenseLine
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.spendingByCategory
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthSummaryCalculatorTest {

    private val april = ym("2026-04")

    private fun plan(amount: Money) = MonthlyPlan(amount, amount, null, PlanSource.DAILY)

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
        assertEquals(eur(450), s.overallRemaining)
        assertEquals(0.55, s.spentRatio!!, 1e-9)
    }

    @Test
    fun `totals reconcile exactly with the expenses`() {
        val expenses = listOf(
            ExpenseLine(FOOD, LocalDate.of(2026, 4, 1), Money(1_234)),
            ExpenseLine(FOOD, LocalDate.of(2026, 4, 30), Money(1)),
            ExpenseLine(FUN, LocalDate.of(2026, 4, 15), Money(9_999)),
            ExpenseLine(FUN, LocalDate.of(2026, 5, 1), Money(50_000)), // other month
        )
        val s = MonthSummaryCalculator.summarize(resolved(), emptyList(), spendingByCategory(april, expenses))
        assertEquals(Money(1_234 + 1 + 9_999), s.totalSpent)
        assertEquals(Money(1_235), s.categories.getValue(FOOD).spent)
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
    fun `no budget gives no spent ratio`() {
        val s = MonthSummaryCalculator.summarize(resolved(overall = null, cats = emptyMap()), emptyList(), mapOf(FOOD to eur(5)))
        assertNull(s.spentRatio)
        assertEquals(eur(-5), s.overallRemaining)
    }

    @Test
    fun `editing an expense into another category and month recalculates both months`() {
        val before = listOf(ExpenseLine(FOOD, LocalDate.of(2026, 4, 30), eur(80)))
        val after = listOf(ExpenseLine(FUN, LocalDate.of(2026, 5, 1), eur(80)))
        val may = ym("2026-05")

        val aprilBefore = MonthSummaryCalculator.summarize(resolved(), emptyList(), spendingByCategory(april, before))
        val aprilAfter = MonthSummaryCalculator.summarize(resolved(), emptyList(), spendingByCategory(april, after))
        val mayAfter = MonthSummaryCalculator.summarize(resolved(month = may), emptyList(), spendingByCategory(may, after))

        assertEquals(eur(80), aprilBefore.categories.getValue(FOOD).spent)
        assertEquals(Money.ZERO, aprilAfter.totalSpent)
        assertEquals(eur(80), mayAfter.categories.getValue(FUN).spent)
        assertEquals(Money.ZERO, mayAfter.categories.getValue(FOOD).spent)
    }

    @Test
    fun `expense month comes from local date - year boundary`() {
        val nye = ExpenseLine(FOOD, LocalDate.of(2025, 12, 31), eur(10))
        val ny = ExpenseLine(FOOD, LocalDate.of(2026, 1, 1), eur(20))
        assertEquals(mapOf(FOOD to eur(10)), spendingByCategory(ym("2025-12"), listOf(nye, ny)))
        assertEquals(mapOf(FOOD to eur(20)), spendingByCategory(ym("2026-01"), listOf(nye, ny)))
    }
}
