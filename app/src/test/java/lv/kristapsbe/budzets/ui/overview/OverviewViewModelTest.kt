package lv.kristapsbe.budzets.ui.overview

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class OverviewViewModelTest : ViewModelTest() {

    private lateinit var vm: OverviewViewModel

    @Before
    fun createViewModel() {
        vm = OverviewViewModel(SavedStateHandle(), budgets, surplus, categories, settings, expenses.hasMonetaryData, clock)
    }

    private suspend fun awaitReady(predicate: (OverviewUiState.Ready) -> Boolean = { true }): OverviewUiState.Ready =
        vm.uiState.awaitFirst { it is OverviewUiState.Ready && predicate(it) } as OverviewUiState.Ready

    @Test
    fun `first launch - empty but usable`() = runTest {
        val state = awaitReady()
        assertEquals(ym("2026-04"), state.month)
        assertTrue(state.isCurrentMonth)
        assertFalse(state.hasCategories)
        assertFalse(state.hasBudget)
        assertEquals(PaceStatus.NO_BUDGET, state.pace.status)
        assertEquals("EUR", state.currency.code)
    }

    @Test
    fun `starter categories are created once and listed in order`() = runTest {
        vm.addStarterCategories(listOf("Groceries", "Fun"))
        val state = awaitReady { it.hasCategories }
        assertEquals(listOf("Groceries", "Fun"), state.rows.map { it.name })
        assertTrue(state.rows.all { it.summary.base == null })

        assertFalse(categories.createStarterCategories(listOf("Again")))
        assertEquals(2, categories.categories.first().size)
    }

    @Test
    fun `renders real data and updates reactively`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000)) // 600
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000)) // 300
        expenses.add(eur(100), food.id, LocalDate.of(2026, 4, 2))

        val before = awaitReady { it.summary.totalSpent == eur(100) }
        assertEquals(eur(600), before.summary.overallBase)
        assertEquals(eur(500), before.summary.overallRemaining)
        assertEquals(listOf(food.id, fun_.id), before.rows.map { it.id })
        assertNull(before.rows[1].summary.base) // Fun: active, no budget, still listed
        assertEquals(eur(300), before.pace.expectedSpend) // 15 of 30 days
        assertEquals(PaceStatus.ON_TRACK, before.pace.status)

        expenses.add(eur(250), food.id, LocalDate.of(2026, 4, 15))
        val after = awaitReady { it.summary.totalSpent == eur(350) }
        assertTrue(after.rows[0].summary.isOver)
        assertEquals(PaceStatus.WATCH, after.pace.status)
    }

    @Test
    fun `month navigation`() = runTest {
        awaitReady()
        vm.previousMonth()
        val march = awaitReady { it.month == ym("2026-03") }
        assertFalse(march.isCurrentMonth)
        vm.nextMonth(); vm.nextMonth()
        awaitReady { it.month == ym("2026-05") }
        vm.goToCurrentMonth()
        assertTrue(awaitReady { it.month == ym("2026-04") }.isCurrentMonth)
    }

    @Test
    fun `close status and surplus total`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03"))
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 5))
        vm.previousMonth()
        assertEquals(CloseStatus.OPEN, awaitReady { it.month == ym("2026-03") }.closeStatus)

        surplus.closeMonth(ym("2026-03")).ok()
        val closed = awaitReady { it.closeStatus == CloseStatus.CLOSED }
        assertEquals(eur(300), closed.totalSurplus)

        expenses.add(eur(5), food.id, LocalDate.of(2026, 3, 6))
        awaitReady { it.closeStatus == CloseStatus.CHANGED_SINCE_CLOSING }
    }

    @Test
    fun `archived category with spending is still shown, flagged`() = runTest {
        val old = categories.create("Old")
        expenses.add(eur(3), old.id, LocalDate.of(2026, 4, 1))
        categories.archive(old.id, ym("2026-04"))
        val row = awaitReady { it.rows.isNotEmpty() }.rows.single()
        assertTrue(row.isArchived)
        assertEquals(eur(3), row.summary.spent)
    }

    @Test
    fun `selected month survives process death via SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        val first = OverviewViewModel(handle, budgets, surplus, categories, settings, expenses.hasMonetaryData, clock)
        first.previousMonth()
        first.previousMonth()
        // A recreated process hands the ViewModel the saved handle contents.
        val restored = OverviewViewModel(SavedStateHandle(mapOf("month" to handle.get<String>("month"))), budgets, surplus, categories, settings, expenses.hasMonetaryData, clock)
        val state = restored.uiState.awaitFirst { it is OverviewUiState.Ready } as OverviewUiState.Ready
        assertEquals(ym("2026-02"), state.month)
        restored.goToCurrentMonth()
        assertTrue((restored.uiState.awaitFirst { it is OverviewUiState.Ready && it.isCurrentMonth } as OverviewUiState.Ready).isCurrentMonth)
    }

    @Test
    fun `reminds about budgeted past months that aren't closed, and only past and current months can close`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 100), ym("2026-02"))
        val april = awaitReady { it.unclosedPastMonths.isNotEmpty() }
        assertEquals(listOf(ym("2026-03"), ym("2026-02")), april.unclosedPastMonths)
        assertTrue(april.canClose)

        surplus.closeMonth(ym("2026-03")).ok()
        assertEquals(listOf(ym("2026-02")), awaitReady { it.unclosedPastMonths.size == 1 }.unclosedPastMonths)

        vm.nextMonth()
        assertFalse(awaitReady { it.month == ym("2026-05") }.canClose)
    }

    @Test
    fun `backup reminder - only with data, until a backup, and again after 30 days`() = runTest {
        assertNull(awaitReady().backupReminder) // nothing to back up yet
        val food = categories.create("Food")
        expenses.add(eur(5), food.id, LocalDate.of(2026, 4, 2))
        assertNull(awaitReady { it.backupReminder != null }.backupReminder!!.daysSinceLast) // never backed up

        settings.recordBackupSuccess(clock.instant().minus(Duration.ofDays(29)))
        awaitReady { it.backupReminder == null }
        settings.recordBackupSuccess(clock.instant().minus(Duration.ofDays(40)))
        assertEquals(40L, awaitReady { it.backupReminder != null }.backupReminder!!.daysSinceLast)
        settings.recordBackupSuccess(clock.instant())
        awaitReady { it.backupReminder == null }
    }

    @Test
    fun `a closed month lists what it was closed with, under plain names`() = runTest {
        val food = categories.create("Food")
        val old = categories.create("Old")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03"))
        expenses.add(eur(3), old.id, LocalDate.of(2026, 3, 2))
        surplus.closeMonth(ym("2026-03")).ok()
        categories.create("New")
        surplus.deleteCategory(old.id)

        vm.previousMonth()
        val march = awaitReady { it.month == ym("2026-03") }
        assertEquals(CloseStatus.CLOSED, march.closeStatus)
        assertEquals(listOf("Food", "Old"), march.rows.map { it.name })
        assertTrue(march.rows.none { it.isArchived || it.isDeleted })
    }

    @Test
    fun `a deleted category with spending in an open month is flagged, and doesn't count as a category`() = runTest {
        val old = categories.create("Old")
        expenses.add(eur(3), old.id, LocalDate.of(2026, 4, 1))
        surplus.deleteCategory(old.id)

        val april = awaitReady { it.rows.singleOrNull()?.isDeleted == true }
        assertEquals(eur(3), april.rows.single().summary.spent)
        assertFalse(april.hasCategories)
    }

    @Test
    fun `one-off boosts show on their own month until removed`() = runTest {
        val april = surplus.addOneOff(ym("2026-04"), eur(200)).ok()
        surplus.addOneOff(ym("2026-04"), eur(50)).ok()
        surplus.addOneOff(ym("2026-03"), eur(7)).ok()

        val state = awaitReady { it.oneOffTotal == eur(250) }
        assertEquals(eur(257), state.totalSurplus)

        surplus.removeOneOff(april.id).ok()
        awaitReady { it.oneOffTotal == eur(50) }
    }
}
