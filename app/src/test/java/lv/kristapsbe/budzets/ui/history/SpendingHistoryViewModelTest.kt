package lv.kristapsbe.budzets.ui.history

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** "Now" is 2026-04-15. */
class SpendingHistoryViewModelTest : ViewModelTest() {

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        SpendingHistoryViewModel(handle, budgets, categories, settings, surplus, expenses, clock).clearedAfterTest()

    private suspend fun SpendingHistoryViewModel.awaitReady(predicate: (SpendingHistoryUiState.Ready) -> Boolean = { true }) =
        uiState.awaitFirst { it is SpendingHistoryUiState.Ready && predicate(it) } as SpendingHistoryUiState.Ready

    @Test
    fun `without expenses there is the month under way, selected`() = runTest {
        categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        val state = viewModel().awaitReady()
        assertEquals(listOf(ym("2026-04")), state.months.map { it.month })
        assertTrue(state.months.single().segments.isEmpty())
        assertEquals(ym("2026-04"), state.selectedMonth)
        assertEquals(ym("2026-04"), state.currentMonth)
        // The surplus chart has it too: nothing spent of €500.
        assertEquals(eur(500), state.surplus.single().result)
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
    fun `closed months give what was posted, open ones a projection, the current month included`() = runTest {
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
        // Q45: as it stands now, €10 of €500 spent.
        assertEquals(false, april.isClosed)
        assertEquals(eur(490), april.result)
        assertEquals(eur(540), april.runningTotal)
        assertEquals(april, state.selectedSurplus)
    }

    @Test
    fun `the burn-down follows the selected month`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        expenses.add(eur(30), food.id, LocalDate.of(2026, 3, 9))
        expenses.add(eur(20), food.id, LocalDate.of(2026, 4, 3))
        val vm = viewModel()

        // It opens on the month under way, up to today.
        val april = vm.awaitReady { it.burnDown != null }.burnDown!!
        assertEquals(ym("2026-04"), april.month)
        assertEquals(15, april.days.size)
        assertEquals(eur(500), april.start)
        assertEquals(eur(480), april.left)

        vm.selectMonth(ym("2026-03"))
        val march = vm.awaitReady { it.burnDown?.month == ym("2026-03") }.burnDown!!
        assertTrue(march.isComplete)
        assertEquals(eur(470), march.left)
    }

    @Test
    fun `a new expense and a new boost reach the burn-down`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        expenses.add(eur(400), food.id, LocalDate.of(2026, 3, 3))
        surplus.closeMonth(ym("2026-03")).ok() // €100 saved in Food
        val vm = viewModel()
        vm.awaitReady { it.burnDown != null }

        expenses.add(eur(15), food.id, LocalDate.of(2026, 4, 5))
        assertEquals(eur(485), vm.awaitReady { it.burnDown?.left == eur(485) }.burnDown!!.left)

        // The clock's today is the 15th, so that's the day it lifts the line.
        surplus.allocateBoost(SurplusPocket.Category(food.id), food.id, ym("2026-04"), eur(60)).ok()
        val boosted = vm.awaitReady { it.burnDown?.boostsAdded == eur(60) }.burnDown!!
        assertEquals(eur(500), boosted.start)
        assertEquals(eur(60), boosted.days[14].boostAdded)
        assertEquals(eur(545), boosted.left)
    }

    @Test
    fun `debt carried into the month under way comes off its burn-down, as on the Overview`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        expenses.add(eur(540), food.id, LocalDate.of(2026, 3, 3))
        surplus.closeMonth(ym("2026-03")).ok() // Food owes €40
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 3))
        val vm = viewModel()

        val april = vm.awaitReady { it.burnDown?.debt?.isPositive == true }.burnDown!!
        assertEquals(eur(40), april.debt)
        assertEquals(eur(460), april.start)
        assertEquals(eur(450), april.left)

        // A closed month carries none: its figures are as they were.
        vm.selectMonth(ym("2026-03"))
        val march = vm.awaitReady { it.burnDown?.month == ym("2026-03") }.burnDown!!
        assertEquals(Money.ZERO, march.debt)
        assertEquals(eur(-40), march.left)
    }

    @Test
    fun `a month without a budget has no burn-down`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 3))
        assertNull(viewModel().awaitReady().burnDown)
    }

    @Test
    fun `no forecast before 3 months are finished, then twelve months of one`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 60_000), ym("2026-06"))
        expenses.add(eur(100), food.id, LocalDate.of(2026, 2, 3))
        expenses.add(eur(100), food.id, LocalDate.of(2026, 3, 3))
        val vm = viewModel()

        val before = vm.awaitReady()
        assertNull(before.forecast)
        assertEquals(2, before.finishedMonths)

        expenses.add(eur(100), food.id, LocalDate.of(2026, 1, 3))
        val state = vm.awaitReady { it.forecast != null }
        val forecast = state.forecast!!
        assertEquals(3, state.finishedMonths)
        assertEquals((5..16).map { YearMonth.of(2026, 4).plusMonths(it - 4L) }, forecast.months)
        val may = forecast.stacks.first()
        assertEquals(eur(100), may.total.expected)
        assertEquals(listOf(food.id), may.segments.map { it.series })
        // The planned budget comes from the configs: €600 from June on.
        assertEquals(eur(500), may.budget)
        assertEquals(eur(600), forecast.stacks[1].budget)
        // €500 planned less €100 likely spent.
        assertEquals(eur(400), forecast.surplus.first().result!!.expected)
        // Every month so far was spent on the 3rd, so nothing more is likely in April after the 15th.
        assertEquals(Money.ZERO, forecast.currentEnd!!.expected)
        assertEquals(eur(500), state.burnDown!!.likelyLeft!!.expected)
        assertEquals(eur(500), forecast.monthEnd!!.result.expected)
        // Forecasts don't add real months.
        assertEquals(ym("2026-04"), state.months.last().month)
    }

    @Test
    fun `a forecast month can be selected, and keeps its selection`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        listOf(1, 2, 3).forEach { expenses.add(eur(100), food.id, LocalDate.of(2026, it, 3)) }
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.awaitReady { it.forecast != null }

        vm.selectMonth(ym("2026-07"))
        val state = vm.awaitReady { it.selectedMonth == ym("2026-07") }
        assertEquals(ym("2026-07"), state.selectedForecast!!.month)
        assertEquals(ym("2026-07"), state.selectedProjection!!.month)
        assertNull(state.selected)
        assertNull(state.burnDown)

        assertEquals(ym("2026-07"), viewModel(handle).awaitReady { it.forecast != null }.selectedMonth)
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
