package lv.kristapsbe.budzets.ui.history

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** "Now" is 2026-04-15. */
class SpendingHistoryViewModelTest : ViewModelTest() {

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        SpendingHistoryViewModel(handle, budgets, categories, settings, surplus, clock).clearedAfterTest()

    private suspend fun SpendingHistoryViewModel.awaitReady(predicate: (SpendingHistoryUiState.Ready) -> Boolean = { true }) =
        uiState.awaitFirst { it is SpendingHistoryUiState.Ready && predicate(it) } as SpendingHistoryUiState.Ready

    @Test
    fun `without expenses there are no months and nothing selected`() = runTest {
        categories.create("Food")
        val state = viewModel().awaitReady()
        assertTrue(state.months.isEmpty())
        assertNull(state.selectedMonth)
        assertEquals(ym("2026-04"), state.currentMonth)
    }

    @Test
    fun `opens on the newest month, with colours in display order`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        val travel = categories.create("Travel")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        expenses.add(eur(10), travel.id, LocalDate.of(2026, 2, 3))
        expenses.add(eur(90), fun_.id, LocalDate.of(2026, 4, 3))
        expenses.add(eur(20), food.id, LocalDate.of(2026, 4, 4))

        val state = viewModel().awaitReady()

        assertEquals(listOf(ym("2026-02"), ym("2026-03"), ym("2026-04")), state.months.map { it.month })
        assertEquals(ym("2026-04"), state.selectedMonth)
        assertEquals(listOf(food.id, fun_.id, travel.id), state.series.map { it.id })
        assertEquals(listOf(0, 1, 2), state.series.map { it.slot })
        // Largest on top: Fun over Food.
        assertEquals(listOf(food.id, fun_.id), state.selected!!.segments.map { it.series })
        assertEquals(eur(500), state.selected!!.budget)
    }

    @Test
    fun `the selected month and the highlight are kept in the saved state`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 2, 3))
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 3))
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.awaitReady()

        vm.selectMonth(ym("2026-02"))
        vm.toggleHighlight(seriesKey(food.id))
        val state = vm.awaitReady { it.selectedMonth == ym("2026-02") && it.highlighted != null }
        assertEquals(food.id.value, state.highlighted)

        val restored = viewModel(handle).awaitReady()
        assertEquals(ym("2026-02"), restored.selectedMonth)
        assertEquals(food.id.value, restored.highlighted)

        vm.toggleHighlight(seriesKey(food.id))
        assertNull(vm.awaitReady { it.highlighted == null }.highlighted)
    }

    @Test
    fun `a saved month or highlight that no longer exists falls back`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 3))
        val state = viewModel(SavedStateHandle(mapOf("month" to "2025-01", "highlight" to "gone"))).awaitReady()
        assertEquals(ym("2026-04"), state.selectedMonth)
        assertNull(state.highlighted)
    }

    @Test
    fun `a new expense reaches the chart`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 3))
        val vm = viewModel()
        vm.awaitReady()

        expenses.add(eur(15), food.id, LocalDate.of(2026, 4, 5))

        assertEquals(eur(25), vm.awaitReady { it.selected?.total == eur(25) }.selected!!.total)
    }

    @Test
    fun `closed months give what was posted, open ones a projection, and the current month waits`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        expenses.add(eur(420), food.id, LocalDate.of(2026, 2, 3))
        expenses.add(eur(530), food.id, LocalDate.of(2026, 3, 3))
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 3))
        surplus.closeMonth(ym("2026-02")).ok()

        val state = viewModel().awaitReady { it.surplus.firstOrNull()?.isClosed == true }

        assertEquals(listOf(ym("2026-02"), ym("2026-03"), ym("2026-04")), state.surplus.map { it.month })
        val (february, march, april) = state.surplus
        assertEquals(eur(80), february.result)
        assertEquals(false, march.isClosed)
        assertEquals(eur(-30), march.result)
        assertEquals(eur(50), march.runningTotal)
        assertNull(april.result)
        assertNull(april.runningTotal)
        assertEquals(april, state.selectedSurplus)
    }

    @Test
    fun `an archived category is marked as such`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 3))
        categories.archive(food.id, ym("2026-04"))
        val series = viewModel().awaitReady { it.series.isNotEmpty() }.series.single()
        assertTrue(series.isArchived)
    }
}
