package lv.kristapsbe.budzets.ui.surplus

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.PocketBreakdown
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SurplusViewModelTest : ViewModelTest() {

    private fun surplusVm() = SurplusViewModel(SavedStateHandle(), surplus, categories, settings, clock)

    private suspend fun SurplusViewModel.awaitReady(predicate: (SurplusUiState.Ready) -> Boolean = { true }) =
        uiState.awaitFirst { it is SurplusUiState.Ready && predicate(it) } as SurplusUiState.Ready

    @Test
    fun `nothing closed yet`() = runTest {
        val state = surplusVm().awaitReady()
        assertTrue(state.pockets.isEmpty())
        assertFalse(state.hasAvailable)
    }

    @Test
    fun `pockets, boosts and history after closing, moving and boosting`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        val foodPocket = SurplusPocket.Category(food.id)
        val funPocket = SurplusPocket.Category(fun_.id)
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-03"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 50_000), ym("2026-03"))
        expenses.add(eur(400), food.id, LocalDate.of(2026, 3, 3))
        expenses.add(eur(30), fun_.id, LocalDate.of(2026, 3, 4)) // no budget: a debit
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.transfer(SurplusPocket.Unallocated, foodPocket, eur(50), note = "top up").ok()
        surplus.allocateBoost(foodPocket, fun_.id, ym("2026-05"), eur(25)).ok()

        val vm = surplusVm()
        val state = vm.awaitReady { it.boosts.isNotEmpty() }
        // Closing: Food 100, Fun −30, Unallocated 500. The move nets to zero; 25 is reserved for
        // the boost. What Fun owes is kept apart, not taken off what the others have saved.
        assertEquals(eur(575), state.saved)
        assertEquals(eur(30), state.debt)
        assertEquals(listOf(foodPocket, funPocket, SurplusPocket.Unallocated), state.pockets.map { it.label.pocket })
        assertEquals(PocketBreakdown(eur(100), eur(50), eur(-25)), state.pockets[0].breakdown)
        assertEquals(eur(-30), state.pockets[1].breakdown.available)
        assertEquals(PocketBreakdown(eur(500), eur(-50)), state.pockets[2].breakdown)
        assertTrue(state.hasAvailable)

        val boost = state.boosts.single()
        assertEquals("Fun", boost.destination.name)
        assertEquals(foodPocket, boost.source.pocket)
        assertEquals(ym("2026-05"), boost.month)
        assertTrue(boost.canRelease)

        // Newest first: the boost, the two transfer legs, then the closing entries.
        assertEquals(HistoryKind.BOOST_RESERVED, state.history.first().kind)
        assertEquals("Fun", state.history.first().counterpart?.name)
        assertEquals(ym("2026-05"), state.history.first().month)
        val movedIn = state.history.single { it.kind == HistoryKind.MOVED_IN }
        assertEquals(SurplusPocket.Unallocated, movedIn.counterpart?.pocket)
        assertEquals("top up", movedIn.note)
        val debit = state.history.single { it.kind == HistoryKind.CLOSE_DEBIT }
        assertEquals(eur(-30), debit.amount)
        assertEquals(ym("2026-03"), debit.month)
        assertEquals(LocalDate.of(2026, 4, 15), debit.date)

        vm.setFilter(funPocket)
        val filtered = vm.awaitReady { it.filter == funPocket }
        assertEquals(listOf(HistoryKind.CLOSE_DEBIT), filtered.history.map { it.kind })
    }

    @Test
    fun `releasing a boost makes its surplus available again`() = runTest {
        val food = categories.create("Food")
        val pocket = SurplusPocket.Category(food.id)
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.allocateBoost(pocket, food.id, ym("2026-04"), eur(40)).ok()

        val vm = surplusVm()
        val boost = vm.awaitReady { it.boosts.isNotEmpty() }.boosts.single()
        vm.releaseBoost(boost.id)
        assertEquals(SurplusMessage.BOOST_RELEASED, vm.message.awaitFirst { it != null })
        // Boosts and ledger update separately, so wait for both; either alone can be seen first.
        val after = vm.awaitReady { it.boosts.isEmpty() && it.history.first().kind == HistoryKind.BOOST_RELEASED }
        assertEquals(eur(100), after.pockets.single().breakdown.available)
    }

    @Test
    fun `a reopen shows as such in history`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.reopenMonth(ym("2026-03")).ok()
        val state = surplusVm().awaitReady { it.history.size == 2 }
        assertEquals(listOf(HistoryKind.REOPEN, HistoryKind.CLOSE_CREDIT), state.history.map { it.kind })
        assertEquals(null, state.history.first().note)
    }

    @Test
    fun `a deleted category's money shows under Unallocated, and its history keeps its name`() = runTest {
        val food = categories.create("Food")
        val clothes = categories.create("Clothes")
        val clothesPocket = SurplusPocket.Category(clothes.id)
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        budgets.setTarget(BudgetScope.Category(clothes.id), BudgetTarget(monthlyMinor = 5_000), ym("2026-03"))
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.deleteCategory(clothes.id)

        val vm = surplusVm()
        val state = vm.awaitReady { it.pockets.isNotEmpty() }
        assertEquals(listOf(SurplusPocket.Category(food.id), SurplusPocket.Unallocated), state.pockets.map { it.label.pocket })
        assertEquals(eur(50), state.pockets.last().breakdown.available)
        assertEquals(eur(150), state.saved)
        assertEquals(Money.ZERO, state.debt)
        val entry = state.history.single { it.pocket.pocket == clothesPocket }
        assertTrue(entry.pocket.isDeleted)

        vm.setFilter(SurplusPocket.Unallocated)
        assertEquals(listOf(clothesPocket), vm.awaitReady { it.filter != null }.history.map { it.pocket.pocket })

        // A filter saved before the delete lands on the pocket that took its money.
        vm.setFilter(clothesPocket)
        assertEquals(SurplusPocket.Unallocated, vm.awaitReady { it.filter != null }.filter)
    }

    @Test
    fun `one-off boosts are listed until removed or their month closes, and stay in history`() = runTest {
        val gift = surplus.addOneOff(ym("2026-04"), eur(200), "Gift").ok()
        surplus.addOneOff(ym("2026-03"), eur(30)).ok()

        val vm = surplusVm()
        val state = vm.awaitReady { it.oneOffs.size == 2 }
        assertEquals(listOf(ym("2026-03"), ym("2026-04")), state.oneOffs.map { it.month })
        assertEquals(OneOffRow(gift.id, ym("2026-04"), eur(200), "Gift"), state.oneOffs.last())
        assertEquals(eur(230), state.pockets.single().breakdown.oneOff)
        assertEquals(eur(230), state.saved)
        assertTrue(state.hasAvailable)

        vm.removeOneOff(gift.id)
        assertEquals(SurplusMessage.ONE_OFF_REMOVED, vm.message.awaitFirst { it != null })
        vm.messageShown(SurplusMessage.ONE_OFF_REMOVED)
        assertEquals(1, vm.awaitReady { it.oneOffs.size == 1 }.oneOffs.size)

        surplus.closeMonth(ym("2026-03")).ok()
        val after = vm.awaitReady { it.oneOffs.isEmpty() }
        assertEquals(listOf(HistoryKind.ONE_OFF_REMOVED, HistoryKind.ONE_OFF_ADDED, HistoryKind.ONE_OFF_ADDED), after.history.map { it.kind })
        assertEquals(ym("2026-04"), after.history.first().month)
    }

    @Test
    fun `removing a one-off boost whose money was used says so`() = runTest {
        val food = categories.create("Food")
        val gift = surplus.addOneOff(ym("2026-04"), eur(200)).ok()
        surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(food.id), eur(150)).ok()

        val vm = surplusVm()
        vm.removeOneOff(gift.id)
        assertEquals(SurplusMessage.ONE_OFF_USED, vm.message.awaitFirst { it != null })
    }
}
