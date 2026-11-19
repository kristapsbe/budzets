package lv.kristapsbe.budzets.ui.surplus

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneOffBoostViewModelTest : ViewModelTest() {

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = OneOffBoostViewModel(handle, surplus, settings, clock)

    private suspend fun OneOffBoostViewModel.awaitEditing(p: (OneOffBoostState.Editing) -> Boolean = { true }) =
        awaitValue({ state }) { it is OneOffBoostState.Editing && p(it) } as OneOffBoostState.Editing

    @Test
    fun `starts on the current month and saves into Unallocated`() = runTest {
        val vm = vm()
        assertEquals(ym("2026-04"), vm.awaitEditing().month)
        vm.onAmountChange("200")
        vm.onNoteChange("  Gift ")
        vm.save()

        awaitValue({ vm.state }) { it == OneOffBoostState.Done }
        assertEquals(eur(200), surplus.balances.first()[SurplusPocket.Unallocated])
        val credit = SurplusLedger.activeOneOffs(surplus.ledger.first()).single()
        assertEquals(ym("2026-04"), credit.targetMonth)
        assertEquals("Gift", credit.note)
    }

    @Test
    fun `the note from Settings is shown, and follows changes to it`() = runTest {
        val vm = vm()
        assertNull(vm.awaitEditing().infoNote)
        settings.setOneOffNote("Gifts go to Fun")
        assertEquals("Gifts go to Fun", vm.awaitEditing { it.infoNote != null }.infoNote)
        settings.setOneOffNote("")
        assertNull(vm.awaitEditing { it.infoNote == null }.infoNote)
    }

    @Test
    fun `earlier and later months can be chosen`() = runTest {
        val vm = vm()
        vm.previousMonth()
        val march = vm.awaitEditing { it.month == ym("2026-03") }
        assertFalse(march.isCurrentMonth)
        vm.goToCurrentMonth()
        vm.nextMonth()
        assertEquals(ym("2026-05"), vm.awaitEditing { it.month == ym("2026-05") }.month)

        vm.onAmountChange("5")
        vm.save()
        awaitValue({ vm.state }) { it == OneOffBoostState.Done }
        assertEquals(ym("2026-05"), SurplusLedger.activeOneOffs(surplus.ledger.first()).single().targetMonth)
    }

    @Test
    fun `a closed month is refused`() = runTest {
        surplus.closeMonth(ym("2026-03")).ok()
        val vm = vm()
        vm.previousMonth()
        vm.onAmountChange("5")
        assertTrue(vm.awaitEditing { it.month == ym("2026-03") }.monthClosed)

        vm.save()
        assertTrue(surplus.ledger.first().isEmpty())
        assertTrue(vm.awaitEditing().monthClosed) // still editing
    }

    @Test
    fun `an empty amount only complains after a save attempt`() = runTest {
        val vm = vm()
        assertNull(vm.awaitEditing().amountError)
        vm.save()
        assertEquals(AmountError.EMPTY, vm.awaitEditing { it.amountError != null }.amountError)
    }

    @Test
    fun `the form survives process death`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.previousMonth()
        first.onAmountChange("12")
        first.onNoteChange("Bonus")

        val restored = vm(handle).awaitEditing()
        assertEquals(ym("2026-03"), restored.month)
        assertEquals(OneOffForm("12", "Bonus"), restored.form)
    }
}
