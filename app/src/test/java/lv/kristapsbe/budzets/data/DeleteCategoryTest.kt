package lv.kristapsbe.budzets.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.ClosureError
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DeleteCategoryTest : DbTest() {

    private val march = ym("2026-03")
    private val april = ym("2026-04")
    private val may = ym("2026-05")

    private lateinit var food: Category
    private lateinit var clothes: Category
    private val clothesPocket get() = SurplusPocket.Category(clothes.id)

    @Before
    fun seed() = runTest {
        food = categories.create("Food")
        clothes = categories.create("Clothes")
    }

    private suspend fun balance(pocket: SurplusPocket) = surplus.balances.first()[pocket] ?: eur(0)
    private suspend fun clothesRow() = categories.categories.first().single { it.id == clothes.id }

    /** March: Clothes budget 100 with [spent] on it, closed. */
    private suspend fun closeMarchWithClothes(spent: Long) {
        budgets.setTarget(BudgetScope.Category(clothes.id), BudgetTarget(monthlyMinor = 10_000), march)
        expenses.add(eur(spent), clothes.id, LocalDate.of(2026, 3, 5))
        surplus.closeMonth(march).ok()
    }

    @Test
    fun `a category without history is removed with its budgets`() = runTest {
        budgets.setTarget(BudgetScope.Category(clothes.id), BudgetTarget(monthlyMinor = 10_000), april)
        surplus.deleteCategory(clothes.id)
        assertEquals(listOf("Food"), categories.categories.first().map { it.name })
        assertTrue(budgets.configs.first().isEmpty())
    }

    @Test
    fun `a category with history is hidden, leaves plans from the first open month, and its expenses stay`() = runTest {
        expenses.add(eur(30), clothes.id, LocalDate.of(2026, 4, 2))
        surplus.deleteCategory(clothes.id)

        val row = clothesRow()
        assertTrue(row.isDeleted)
        assertEquals(april, row.archivedFrom)
        assertEquals(eur(30), budgets.loadMonthSummary(april).totalSpent)
    }

    @Test
    fun `with the current month closed early, it stays in that month`() = runTest {
        closeMarchWithClothes(spent = 10)
        surplus.closeMonth(april).ok()
        surplus.deleteCategory(clothes.id)
        assertEquals(may, clothesRow().archivedFrom)
    }

    @Test
    fun `an earlier archive month is kept`() = runTest {
        expenses.add(eur(30), clothes.id, LocalDate.of(2026, 2, 2))
        categories.archive(clothes.id, march)
        surplus.deleteCategory(clothes.id)
        assertEquals(march, clothesRow().archivedFrom)
    }

    @Test
    fun `its surplus joins Unallocated and can be used from there`() = runTest {
        closeMarchWithClothes(spent = 40)
        assertEquals(eur(60), balance(clothesPocket))

        surplus.deleteCategory(clothes.id)
        val balances = surplus.balances.first()
        assertFalse(clothesPocket in balances)
        assertEquals(eur(60), balances.getValue(SurplusPocket.Unallocated))

        surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(food.id), eur(60)).ok()
        assertEquals(eur(60), balance(SurplusPocket.Category(food.id)))
    }

    @Test
    fun `a debt joins Unallocated too`() = runTest {
        closeMarchWithClothes(spent = 130)
        surplus.deleteCategory(clothes.id)
        assertEquals(eur(-30), balance(SurplusPocket.Unallocated))
        assertTrue(surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(food.id), eur(1)).err() is SurplusError.InsufficientSurplus)
    }

    @Test
    fun `boosts into it for open months are released, boosts for closed months stay`() = runTest {
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 20_000), march)
        surplus.closeMonth(march).ok() // Food +200
        val foodPocket = SurplusPocket.Category(food.id)
        val open = surplus.allocateBoost(foodPocket, clothes.id, may, eur(50)).ok()
        val closed = surplus.allocateBoost(foodPocket, clothes.id, april, eur(20)).ok()
        surplus.closeMonth(april).ok()

        surplus.deleteCategory(clothes.id)

        val boosts = surplus.boosts.first().associateBy { it.id }
        assertEquals(BoostStatus.RELEASED, boosts.getValue(open.id).status)
        assertEquals(BoostStatus.ACTIVE, boosts.getValue(closed.id).status)
        // March's 200 plus April's 200, less the 20 still in April's boost. The 50 came back.
        assertEquals(eur(380), balance(foodPocket))
    }

    @Test
    fun `a boost funded from it returns to Unallocated when released`() = runTest {
        closeMarchWithClothes(spent = 40)
        val boost = surplus.allocateBoost(clothesPocket, food.id, may, eur(25)).ok()
        surplus.deleteCategory(clothes.id)
        assertEquals(eur(35), balance(SurplusPocket.Unallocated))

        surplus.releaseBoost(boost.id).ok()
        assertEquals(eur(60), balance(SurplusPocket.Unallocated))
    }

    @Test
    fun `closing an open month with spending on it counts against Unallocated`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 20_000), april)
        budgets.setTarget(BudgetScope.Category(clothes.id), BudgetTarget(monthlyMinor = 10_000), april)
        expenses.add(eur(30), clothes.id, LocalDate.of(2026, 4, 2))
        surplus.deleteCategory(clothes.id)

        surplus.closeMonth(april).ok()
        // Its budget dropped out, so all 200 is unassigned; its 30 of spending comes off that.
        assertEquals(eur(170), balance(SurplusPocket.Unallocated))
        assertFalse(surplus.hasChangesSinceClosing(april))
    }

    @Test
    fun `a closed month with its surplus can still be reopened and closed again`() = runTest {
        closeMarchWithClothes(spent = 40)
        surplus.deleteCategory(clothes.id)

        surplus.reopenMonth(march).ok()
        assertEquals(eur(0), balance(SurplusPocket.Unallocated))

        surplus.closeMonth(march).ok()
        assertEquals(eur(60), balance(SurplusPocket.Unallocated))
        assertFalse(surplus.hasChangesSinceClosing(march))
    }

    @Test
    fun `reopening is still refused once that surplus has been used`() = runTest {
        closeMarchWithClothes(spent = 40)
        surplus.deleteCategory(clothes.id)
        surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(food.id), eur(50)).ok()

        val refused = surplus.reopenMonth(march).err() as ClosureError.WouldGoNegative
        assertEquals(mapOf(SurplusPocket.Unallocated to eur(-50)), refused.pockets)
    }

    @Test
    fun `starter categories work again once every category is deleted`() = runTest {
        expenses.add(eur(1), food.id, LocalDate.of(2026, 4, 2))
        expenses.add(eur(1), clothes.id, LocalDate.of(2026, 4, 2))
        assertFalse(categories.createStarterCategories(listOf("Rent")))

        surplus.deleteCategory(food.id)
        surplus.deleteCategory(clothes.id)
        assertTrue(categories.createStarterCategories(listOf("Rent")))
        assertEquals(listOf("Rent"), categories.categories.first().filterNot { it.isDeleted }.map { it.name })
    }
}
