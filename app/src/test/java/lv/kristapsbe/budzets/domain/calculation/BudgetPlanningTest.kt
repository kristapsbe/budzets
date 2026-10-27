package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPlanningTest {
    private val food = BudgetScope.Category(FOOD)
    private val categories = listOf(CategoryRef(FOOD), CategoryRef(FUN))
    private val configs = listOf(
        BudgetConfig(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000), ym("2026-01")), // 600 in April
        BudgetConfig(food, BudgetTarget(dailyMinor = 1_000), ym("2026-01")),
        BudgetConfig(food, BudgetTarget(dailyMinor = 1_500), ym("2026-06")),
    )

    @Test
    fun `next change is the earliest later version of the same scope`() {
        assertEquals(ym("2026-06"), BudgetPlanning.nextChangeAfter(configs, food, ym("2026-04")))
        assertNull(BudgetPlanning.nextChangeAfter(configs, food, ym("2026-06")))
        assertNull(BudgetPlanning.nextChangeAfter(configs, BudgetScope.Overall, ym("2026-04")))
    }

    @Test
    fun `preview applies the draft to that month only and flags categories above the overall`() {
        val april = ym("2026-04") // 30 days
        val fine = BudgetPlanning.previewWith(april, configs, categories, BudgetScope.Category(FUN), BudgetTarget(monthlyMinor = 30_000))
        assertEquals(eur(600), BudgetPlanning.categoryTotal(fine)) // 300 + 300
        assertFalse(BudgetPlanning.categoryPlansExceedOverall(fine))

        val over = BudgetPlanning.previewWith(april, configs, categories, BudgetScope.Category(FUN), BudgetTarget(monthlyMinor = 30_001))
        assertTrue(BudgetPlanning.categoryPlansExceedOverall(over))

        // A version saved for the same month is replaced, not duplicated.
        val replaced = BudgetPlanning.previewWith(ym("2026-06"), configs, categories, food, BudgetTarget(dailyMinor = 100))
        assertEquals(eur(30), replaced.categoryPlans.getValue(FOOD).amount)
    }

    @Test
    fun `removing the overall in a preview falls back to the derived sum`() {
        val resolved = BudgetPlanning.previewWith(ym("2026-04"), configs, categories, BudgetScope.Overall, BudgetTarget())
        assertTrue(resolved.overallDerived)
        assertEquals(eur(300), resolved.overallBase)
        assertFalse(BudgetPlanning.categoryPlansExceedOverall(resolved))
    }

    @Test
    fun `target fields - empty is unset, zero is real, errors per field`() {
        val eurSpec = CurrencySpec.DEFAULT
        assertEquals(BudgetTarget(dailyMinor = 1_050, monthlyMinor = 0), BudgetPlanning.parseTargets("10,5", " ", "0", eurSpec).ok())
        assertEquals(BudgetTarget(), BudgetPlanning.parseTargets("", "", "", eurSpec).ok())

        val errors = BudgetPlanning.parseTargets("1.234", "abc", "5", eurSpec).err()
        assertEquals(TargetErrors(AmountError.TOO_MANY_DECIMALS, AmountError.NOT_A_NUMBER, null), errors)
        assertEquals(AmountError.TOO_LARGE, BudgetPlanning.parseTargets("", "", "9999999999", eurSpec).err().monthly)
    }
}
