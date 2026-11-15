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
    fun `an open month previews each pocket, and says which one closing leaves in debt`() = runTest {
        val (food, fun_) = setUpMarch()
        val state = review("2026-03").awaitReady()

        assertEquals(MonthStatus.OPEN, state.status)
        assertTrue(state.canClose)
        assertFalse(state.isCurrentMonth)
        assertTrue(state.hasBudget)
        assertEquals(listOf(food, fun_, SurplusPocket.Unallocated), state.lines.map { it.label.pocket })
        assertEquals(listOf(eur(100), eur(-30), eur(500)), state.lines.map { it.effect })
        // Fun has no amount set, so its €30 is overspending, and nothing saved covers it.
        assertEquals(listOf(null, DebtChange.Leaves(eur(30)), null), state.lines.map { it.debtChange })
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
    fun `an open month says what closing does to debt, a closed or future one doesn't`() = runTest {
        val (food, fun_) = setUpMarch()
        surplus.closeMonth(ym("2026-03")).ok() // Food +100, Fun −30, Unallocated +500
        val foodId = (food as SurplusPocket.Category).id
        val funId = (fun_ as SurplusPocket.Category).id
        // April: Fun gets €20 and spends nothing; Food spends €650 of its €500.
        budgets.setTarget(BudgetScope.Category(funId), BudgetTarget(monthlyMinor = 2_000), ym("2026-04"))
        expenses.add(eur(650), foodId, LocalDate.of(2026, 4, 3))

        fun MonthCloseUiState.Ready.line(pocket: SurplusPocket) = lines.single { it.label.pocket == pocket }
        val april = review("2026-04").awaitReady { state -> state.lines.any { it.label.pocket == fun_ && it.effect == eur(20) } }
        assertEquals(DebtChange.PaysOffPart(paid = eur(20), stillOwed = eur(10)), april.line(fun_).debtChange)
        assertEquals(DebtChange.Leaves(eur(50)), april.line(food).debtChange) // its €100 saved doesn't cover €150 over
        assertNull(april.line(SurplusPocket.Unallocated).debtChange)

        budgets.setTarget(BudgetScope.Category(funId), BudgetTarget(monthlyMinor = 4_000), ym("2026-04"))
        val enough = review("2026-04").awaitReady { state -> state.lines.any { it.label.pocket == fun_ && it.effect == eur(40) } }
        assertEquals(DebtChange.PaysOff(eur(30)), enough.line(fun_).debtChange)

        // March is closed: its effect is in the balances already. May can't be closed yet.
        assertTrue(review("2026-03").awaitReady().lines.all { it.debtChange == null })
        assertTrue(review("2026-05").awaitReady { it.lines.isNotEmpty() }.lines.all { it.debtChange == null })
    }

    @Test
    fun `the current month can be closed early, a future one not at all`() = runTest {
        assertTrue(review("2026-04").awaitReady().let { it.isCurrentMonth && it.canClose })
        val future = review("2026-05").awaitReady()
        assertFalse(future.canClose)
        assertFalse(future.hasBudget)
    }

    @Test
    fun `budget changes after closing leave the month as closed`() = runTest {
        val (food, _) = setUpMarch()
        surplus.closeMonth(ym("2026-03")).ok()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), ym("2026-02"))
        budgets.setTarget(BudgetScope.Category((food as SurplusPocket.Category).id), BudgetTarget(monthlyMinor = 90_000), ym("2026-02"))

        val state = review("2026-03").awaitReady()
        assertEquals(MonthStatus.CLOSED, state.status)
        assertEquals(eur(570), state.netEffect)
        assertTrue(state.lines.all { it.pending.isZero })
    }

    @Test
    fun `a deleted category keeps its line, marked only while the month is open`() = runTest {
        val (_, fun_) = setUpMarch()
        surplus.deleteCategory((fun_ as SurplusPocket.Category).id)

        val open = review("2026-03").awaitReady()
        val line = open.lines.single { it.label.pocket == fun_ }
        assertTrue(line.label.isDeleted)
        assertEquals(eur(-30), line.effect)

        surplus.closeMonth(ym("2026-03")).ok()
        val closed = review("2026-03").awaitReady { it.status == MonthStatus.CLOSED }
        val closedLine = closed.lines.single { it.label.pocket == fun_ }
        assertFalse(closedLine.label.isDeleted)
        assertEquals(eur(-30), closedLine.effect)
    }
}
