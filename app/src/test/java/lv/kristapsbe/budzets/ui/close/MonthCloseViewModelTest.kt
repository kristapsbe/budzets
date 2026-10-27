package lv.kristapsbe.budzets.ui.close

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MonthCloseViewModelTest : ViewModelTest() {

    private fun review(month: String) = MonthCloseViewModel(SavedStateHandle(mapOf("month" to month)), surplus, categories, settings, clock)

    private suspend fun MonthCloseViewModel.awaitReady(predicate: (MonthCloseUiState.Ready) -> Boolean = { true }) =
        uiState.awaitFirst { it is MonthCloseUiState.Ready && predicate(it) } as MonthCloseUiState.Ready

    /** March: overall 1000, Food 500 with 400 spent, Fun without a budget but 30 spent. */
    private suspend fun setUpMarch(): Pair<SurplusPocket, SurplusPocket> {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-03"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 50_000), ym("2026-03"))
        expenses.add(eur(400), food.id, LocalDate.of(2026, 3, 3))
        expenses.add(eur(30), fun_.id, LocalDate.of(2026, 3, 4))
        return SurplusPocket.Category(food.id) to SurplusPocket.Category(fun_.id)
    }

    @Test
    fun `an open month previews each pocket, calling out spending without a budget`() = runTest {
        val (food, fun_) = setUpMarch()
        val state = review("2026-03").awaitReady()

        assertEquals(MonthStatus.OPEN, state.status)
        assertTrue(state.canClose)
        assertFalse(state.isCurrentMonth)
        assertTrue(state.hasBudget)
        assertEquals(listOf(food, fun_, SurplusPocket.Unallocated), state.lines.map { it.label.pocket })
        assertEquals(listOf(eur(100), eur(-30), eur(500)), state.lines.map { it.effect })
        assertEquals(listOf(false, true, false), state.lines.map { it.isUnbudgetedSpending })
        assertNull(state.lines.last().summary)
        assertEquals(eur(570), state.netEffect)
    }

    @Test
    fun `close, then changes show only the difference, then close again`() = runTest {
        val (food, fun_) = setUpMarch()
        val vm = review("2026-03")
        vm.awaitReady()
        vm.close()
        assertEquals(CloseEvent.Closed, vm.event.awaitFirst { it != null })

        val closed = vm.awaitReady { it.status == MonthStatus.CLOSED }
        assertEquals(1, closed.revision)
        assertTrue(closed.lines.all { it.pending.isZero })

        expenses.add(eur(20), (food as SurplusPocket.Category).id, LocalDate.of(2026, 3, 9))
        val changed = vm.awaitReady { it.status == MonthStatus.CHANGED }
        assertEquals(eur(-20), changed.lines.single { it.label.pocket == food }.pending)
        assertEquals(Money.ZERO, changed.lines.single { it.label.pocket == fun_ }.pending)
        assertEquals(eur(-20), changed.pendingNet)
        assertEquals(eur(80), changed.lines.single { it.label.pocket == food }.effect)

        vm.eventHandled(CloseEvent.Closed)
        vm.close()
        vm.event.awaitFirst { it == CloseEvent.Closed }
        assertEquals(2, vm.awaitReady { it.status == MonthStatus.CLOSED && it.revision == 2 }.revision)
        assertEquals(eur(80), surplus.balances.awaitFirst { true }[food])
    }

    @Test
    fun `reopening reverses the month, unless its surplus has been used`() = runTest {
        val (food, fun_) = setUpMarch()
        surplus.closeMonth(ym("2026-03")).ok()
        // Food's whole surplus is set aside for a boost.
        val boost = surplus.allocateBoost(food, (fun_ as SurplusPocket.Category).id, ym("2026-04"), eur(100)).ok()

        val vm = review("2026-03")
        vm.awaitReady { it.status == MonthStatus.CLOSED }
        vm.reopen()
        val refused = vm.event.awaitFirst { it != null } as CloseEvent.ReopenRefused
        assertEquals(listOf(food to eur(-100)), refused.pockets.map { it.first.pocket to it.second })

        surplus.releaseBoost(boost.id).ok()
        vm.eventHandled(refused)
        vm.reopen()
        assertEquals(CloseEvent.Reopened, vm.event.awaitFirst { it == CloseEvent.Reopened })
        val reopened = vm.awaitReady { it.status == MonthStatus.OPEN }
        assertTrue(reopened.wasReopened)
        assertEquals(Money.ZERO, surplus.balances.awaitFirst { true }.values.fold(Money.ZERO) { a, b -> a + b })
    }

    @Test
    fun `the current month can be closed early, a future one not at all`() = runTest {
        assertTrue(review("2026-04").awaitReady().let { it.isCurrentMonth && it.canClose })
        val future = review("2026-05").awaitReady()
        assertFalse(future.canClose)
        assertFalse(future.hasBudget)
    }
}
