package lv.kristapsbe.budzets.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OneOffBoostTest : DbTest() {

    private val march = ym("2026-03")
    private val april = ym("2026-04")

    private lateinit var food: Category

    @Before
    fun seed() = runTest {
        food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), march)
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), march)
    }

    private suspend fun unallocated() = surplus.balances.first()[SurplusPocket.Unallocated] ?: eur(0)

    @Test
    fun `the money is in Unallocated at once, and the month's budget doesn't change`() = runTest {
        val before = budgets.loadMonthSummary(april)
        surplus.addOneOff(april, eur(200), "Gift").ok()

        assertEquals(eur(200), unallocated())
        assertEquals(before, budgets.loadMonthSummary(april))
        val breakdown = SurplusLedger.breakdown(surplus.ledger.first()).getValue(SurplusPocket.Unallocated)
        assertEquals(eur(200), breakdown.oneOff)
        assertEquals(eur(200), breakdown.available)
    }

    @Test
    fun `boosting a category from it is what raises the month's budget`() = runTest {
        surplus.addOneOff(april, eur(200)).ok()
        surplus.allocateBoost(SurplusPocket.Unallocated, food.id, april, eur(150)).ok()

        assertEquals(eur(50), unallocated())
        val summary = budgets.loadMonthSummary(april)
        assertEquals(eur(550), summary.categories.getValue(food.id).effective)
        assertEquals(eur(1_150), summary.overallEffective)
    }

    @Test
    fun `any month that isn't closed can take one`() = runTest {
        surplus.addOneOff(march, eur(10)).ok()
        surplus.addOneOff(ym("2026-09"), eur(5)).ok()
        assertEquals(eur(15), unallocated())
    }

    @Test
    fun `a closed month and a zero amount are refused`() = runTest {
        surplus.closeMonth(march).ok()
        assertEquals(SurplusError.MonthClosed, surplus.addOneOff(march, eur(10)).err())
        assertEquals(SurplusError.NotPositive, surplus.addOneOff(april, Money.ZERO).err())
    }

    @Test
    fun `closing a month ignores it`() = runTest {
        surplus.addOneOff(april, eur(200)).ok()
        val preview = surplus.previewClose(april)
        surplus.closeMonth(april).ok()

        assertEquals(preview, SurplusLedger.closureNet(surplus.ledger.first(), april))
        assertFalse(surplus.hasChangesSinceClosing(april))
    }

    @Test
    fun `removing takes the money back out, once`() = runTest {
        val credit = surplus.addOneOff(april, eur(200)).ok()
        surplus.removeOneOff(credit.id).ok()

        assertEquals(eur(0), unallocated())
        assertTrue(SurplusLedger.activeOneOffs(surplus.ledger.first()).isEmpty())
        assertEquals(SurplusError.AlreadyRemoved, surplus.removeOneOff(credit.id).err())
    }

    @Test
    fun `removing is refused once the money has been used, or the month has closed`() = runTest {
        val credit = surplus.addOneOff(april, eur(200)).ok()
        surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(food.id), eur(150)).ok()
        assertEquals(SurplusError.InsufficientSurplus(eur(50)), surplus.removeOneOff(credit.id).err())

        surplus.transfer(SurplusPocket.Category(food.id), SurplusPocket.Unallocated, eur(150)).ok()
        surplus.closeMonth(april).ok()
        assertEquals(SurplusError.MonthClosed, surplus.removeOneOff(credit.id).err())
    }

    @Test
    fun `an entry that isn't a one-off can't be removed`() = runTest {
        surplus.closeMonth(march).ok()
        val entry = surplus.ledger.first().first()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { surplus.removeOneOff(entry.id) } }
    }
}
