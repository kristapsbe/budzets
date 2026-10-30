package lv.kristapsbe.budzets.ui.budgets

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.TargetKind
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class BudgetsViewModelTest : ViewModelTest() {

    private lateinit var vm: BudgetsViewModel

    @Before
    fun createViewModel() {
        vm = BudgetsViewModel(SavedStateHandle(), budgets, categories, surplus, settings, clock)
    }

    private suspend fun awaitReady(predicate: (BudgetsUiState.Ready) -> Boolean = { true }): BudgetsUiState.Ready =
        vm.uiState.awaitFirst { it is BudgetsUiState.Ready && predicate(it) } as BudgetsUiState.Ready

    @Test
    fun `each month shows the version in force, when it started and when it changes`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-06"))

        val april = awaitReady { it.overall.plan != null }
        assertEquals(ym("2026-04"), april.month)
        assertEquals(eur(600), april.overall.plan!!.amount) // 30 days
        assertEquals(BudgetTarget(dailyMinor = 2_000), april.overall.target)
        assertEquals(ym("2026-01"), april.overall.since)
        assertEquals(ym("2026-06"), april.overall.nextChange)

        vm.nextMonth(); vm.nextMonth()
        val june = awaitReady { it.month == ym("2026-06") }
        assertEquals(eur(500), june.overall.plan!!.amount)
        assertEquals(setOf(TargetKind.MONTHLY), june.overall.plan!!.determinedBy)
        assertNull(june.overall.since) // started this month
        assertNull(june.overall.nextChange)

        vm.goToCurrentMonth()
        repeat(4) { vm.previousMonth() }
        assertNull(awaitReady { it.month == ym("2025-12") }.overall.plan) // before any budget
    }

    @Test
    fun `derived overall, then unassigned budget, then categories above the overall`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000)) // 300

        val derived = awaitReady { it.categories.any { r -> r.budget.plan != null } }
        assertTrue(derived.overallDerived)
        assertNull(derived.overall.plan)
        assertEquals(eur(300), derived.overallBase)
        assertEquals(Money.ZERO, derived.unallocated)
        assertEquals(BudgetTarget(dailyMinor = 1_000), derived.categories[0].budget.target)
        assertNull(derived.categories[1].budget.plan) // Fun: no budget, still listed

        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000))
        val withOverall = awaitReady { !it.overallDerived }
        assertEquals(eur(200), withOverall.unallocated)
        assertFalse(withOverall.exceedsOverall)

        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(monthlyMinor = 30_000))
        val over = awaitReady { it.exceedsOverall }
        assertEquals(eur(600), over.categoryTotal)
        assertEquals(Money.ZERO, over.unallocated)
    }

    @Test
    fun `add, rename and reorder categories`() = runTest {
        vm.addCategory("Food")
        vm.addCategory("Fun")
        vm.addCategory("Travel")
        val added = awaitReady { it.categories.size == 3 }
        assertEquals(listOf("Food", "Fun", "Travel"), added.categories.map { it.name })
        assertEquals(listOf(false, true, true), added.categories.map { it.canMoveUp })
        assertEquals(listOf(true, true, false), added.categories.map { it.canMoveDown })

        val travel = added.categories.last().id
        vm.renameCategory(travel, "Trips")
        vm.move(travel, up = true)
        assertEquals(listOf("Food", "Trips", "Fun"), awaitReady { it.categories.getOrNull(1)?.name == "Trips" }.categories.map { it.name })
    }

    @Test
    fun `archive keeps the category in earlier months, unarchive brings it back`() = runTest {
        val food = categories.create("Food")
        categories.create("Fun")
        awaitReady { it.categories.size == 2 }

        vm.archive(food.id)
        val april = awaitReady { it.archived.isNotEmpty() }
        assertEquals(listOf("Fun"), april.categories.map { it.name })
        assertEquals(ArchivedRow(food.id, "Food", ym("2026-04")), april.archived.single())

        vm.previousMonth()
        val march = awaitReady { it.month == ym("2026-03") }
        assertEquals(listOf("Food", "Fun"), march.categories.map { it.name })
        assertEquals(ym("2026-04"), march.categories.first().archivedFrom)
        assertTrue(march.archived.isEmpty())

        vm.unarchive(food.id)
        assertNull(awaitReady { it.categories.first().archivedFrom == null }.categories.first().archivedFrom)
    }

    @Test
    fun `deleting removes a category without history, and hides one with history`() = runTest {
        val used = categories.create("Used")
        val unused = categories.create("Unused")
        budgets.setTarget(BudgetScope.Category(unused.id), BudgetTarget(dailyMinor = 100)) // budgets alone don't count
        expenses.add(eur(1), used.id, LocalDate.of(2026, 4, 2))

        vm.delete(unused.id)
        assertEquals(BudgetsMessage.DELETED, vm.message.awaitFirst { it != null })
        vm.messageShown(BudgetsMessage.DELETED)
        assertEquals(listOf("Used"), categories.categories.first().map { it.name })
        assertTrue(budgets.configs.first().isEmpty())

        vm.delete(used.id)
        assertEquals(BudgetsMessage.DELETED, vm.message.awaitFirst { it != null })
        val april = awaitReady { it.categories.isEmpty() }
        assertTrue(april.archived.isEmpty())
        assertFalse(april.hasCategories)
        assertTrue(categories.categories.first().single().isDeleted)

        // March is before it was deleted and isn't closed: still in its plan, but marked.
        vm.previousMonth()
        assertTrue(awaitReady { it.month == ym("2026-03") }.categories.single().isDeleted)
    }

    @Test
    fun `a closed month is read-only and shows what it was closed with`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(weeklyMinor = 7_000), ym("2026-01"))
        surplus.closeMonth(ym("2026-03")).ok()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-02"))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(monthlyMinor = 5_000), ym("2026-02"))
        categories.create("New")

        vm.previousMonth()
        val march = awaitReady { it.month == ym("2026-03") }
        assertTrue(march.isClosed)
        assertEquals(eur(620), march.overall.plan!!.amount) // 31 × 20, not the later 1000
        assertEquals(BudgetTarget(monthlyMinor = 62_000), march.overall.target)
        assertNull(march.overall.since)
        assertNull(march.overall.nextChange)
        assertEquals(listOf("Food"), march.categories.map { it.name }) // not Fun or New
        assertEquals(eur(310), march.categories.single().budget.plan!!.amount)
        assertEquals(eur(310), march.unallocated)
        assertTrue(march.archived.isEmpty())
        assertFalse(march.categories.single().canMoveDown)

        // The same month, reopened, reads the budgets as they now stand.
        surplus.reopenMonth(ym("2026-03")).ok()
        val reopened = awaitReady { !it.isClosed }
        assertEquals(eur(1_000), reopened.overall.plan!!.amount)
        assertEquals(listOf("Food", "Fun", "New"), reopened.categories.map { it.name })
    }

    @Test
    fun `archiving starts at the first open month`() = runTest {
        val food = categories.create("Food")
        surplus.closeMonth(ym("2026-04")).ok() // the current month, closed early
        vm.archive(food.id)
        assertEquals(ym("2026-05"), categories.categories.awaitFirst { it.single().isArchived }.single().archivedFrom)
    }

    @Test
    fun `closed months are flagged`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 100), ym("2026-03"))
        surplus.closeMonth(ym("2026-03")).ok()
        vm.previousMonth()
        assertTrue(awaitReady { it.month == ym("2026-03") }.isClosed)
    }

    @Test
    fun `selected month survives process death`() = runTest {
        val handle = SavedStateHandle()
        val first = BudgetsViewModel(handle, budgets, categories, surplus, settings, clock)
        first.nextMonth()
        val restored = BudgetsViewModel(SavedStateHandle(mapOf("month" to handle.get<String>("month"))), budgets, categories, surplus, settings, clock)
        val state = restored.uiState.awaitFirst { it is BudgetsUiState.Ready } as BudgetsUiState.Ready
        assertEquals(ym("2026-05"), state.month)
    }
}
