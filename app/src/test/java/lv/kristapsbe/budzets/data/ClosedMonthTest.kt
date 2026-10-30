package lv.kristapsbe.budzets.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A closed month keeps the budgets it was closed with, whatever changes after it. */
class ClosedMonthTest : DbTest() {

    private val february = ym("2026-02")
    private val march = ym("2026-03")
    private val april = ym("2026-04")

    private lateinit var food: Category
    private lateinit var fun_: Category

    /** From January: overall 1000, Food 400, Fun 100. March has 150 spent on Food, and is closed. */
    private suspend fun closeMarch(): MonthSummary {
        food = categories.create("Food")
        fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-01"))
        expenses.add(eur(150), food.id, LocalDate.of(2026, 3, 5))
        surplus.closeMonth(march).ok()
        return budgets.loadMonthSummary(march)
    }

    private suspend fun assertMarchUnchanged(closed: MonthSummary) {
        assertEquals(closed, budgets.loadMonthSummary(march))
        assertEquals(closed, budgets.observeMonthSummary(march).first())
        assertFalse(surplus.hasChangesSinceClosing(march))
    }

    @Test
    fun `changing the overall budget from an earlier month skips the closed month`() = runTest {
        val closed = closeMarch()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), february)

        assertMarchUnchanged(closed)
        assertEquals(eur(2_000), budgets.loadMonthSummary(february).overallBase)
        assertEquals(eur(2_000), budgets.loadMonthSummary(april).overallBase)
    }

    @Test
    fun `saving a budget for the closed month itself only reaches later months`() = runTest {
        val closed = closeMarch()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), march)

        assertMarchUnchanged(closed)
        assertEquals(eur(2_000), budgets.loadMonthSummary(april).overallBase)
    }

    @Test
    fun `changing or clearing a category budget skips the closed month`() = runTest {
        val closed = closeMarch()
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 90_000), february)
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(), february)

        assertMarchUnchanged(closed)
        assertEquals(eur(900), budgets.loadMonthSummary(april).categories.getValue(food.id).base)
    }

    @Test
    fun `adding a category with a budget skips the closed month`() = runTest {
        val closed = closeMarch()
        val new = categories.create("New")
        budgets.setTarget(BudgetScope.Category(new.id), BudgetTarget(monthlyMinor = 5_000), february)

        assertMarchUnchanged(closed)
        assertEquals(eur(50), budgets.loadMonthSummary(april).categories.getValue(new.id).base)
    }

    @Test
    fun `archiving and unarchiving skip the closed month`() = runTest {
        val closed = closeMarch()
        categories.archive(food.id, february)
        assertMarchUnchanged(closed)
        assertFalse(food.id in budgets.loadMonthSummary(april).categories)

        categories.unarchive(food.id)
        assertMarchUnchanged(closed)
    }

    @Test
    fun `deleting a category skips the closed month`() = runTest {
        val closed = closeMarch()
        surplus.deleteCategory(food.id)
        surplus.deleteCategory(fun_.id)
        assertMarchUnchanged(closed)
    }

    @Test
    fun `a corrected expense still shows, and closing again keeps the budgets`() = runTest {
        val closed = closeMarch()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), february)
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 6))

        assertEquals(eur(160), budgets.loadMonthSummary(march).categories.getValue(food.id).spent)
        assertEquals(closed.overallBase, budgets.loadMonthSummary(march).overallBase)
        assertTrue(surplus.hasChangesSinceClosing(march))

        val again = surplus.closeMonth(march).ok()
        assertEquals(2, again.revision)
        assertFalse(surplus.hasChangesSinceClosing(march))
        assertEquals(eur(1_000), budgets.loadMonthSummary(march).overallBase)
        assertEquals(eur(240), surplus.balances.first().getValue(SurplusPocket.Category(food.id))) // 400 - 160
    }

    @Test
    fun `a month closed again reads its latest revision`() = runTest {
        closeMarch()
        surplus.reopenMonth(march).ok()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 120_000), march)
        surplus.closeMonth(march).ok()

        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), february)
        assertEquals(eur(1_200), budgets.loadMonthSummary(march).overallBase)
    }

    @Test
    fun `a reopened month goes back to the budgets as they stand`() = runTest {
        closeMarch()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), february)
        surplus.reopenMonth(march).ok()
        assertEquals(eur(2_000), budgets.loadMonthSummary(march).overallBase)
        assertEquals(eur(2_000), budgets.observeMonthSummary(march).first().overallBase)
    }

    @Test
    fun `a month closed with no budget and no categories stays that way`() = runTest {
        surplus.closeMonth(march).ok()
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), february)

        val summary = budgets.loadMonthSummary(march)
        assertEquals(null, summary.overallBase)
        assertTrue(summary.categories.isEmpty())
    }
}
