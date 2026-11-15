package lv.kristapsbe.budzets.ui.surplus

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoveAndBoostViewModelTest : ViewModelTest() {

    private lateinit var food: Category
    private lateinit var fun_: Category
    private val foodPocket get() = SurplusPocket.Category(food.id)
    private val funPocket get() = SurplusPocket.Category(fun_.id)

    /** March closed with Food 100 unspent; Fun has no surplus. */
    private suspend fun setUp() {
        food = categories.create("Food")
        fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        surplus.closeMonth(ym("2026-03")).ok()
    }

    private fun move(vararg args: Pair<String, Any?>, handle: SavedStateHandle = SavedStateHandle(mapOf(*args))) =
        MoveSurplusViewModel(handle, surplus, categories, settings, clock, decimalSeparator = '.')

    private fun boost(vararg args: Pair<String, Any?>) =
        BoostViewModel(SavedStateHandle(mapOf(*args)), surplus, budgets, categories, settings, clock, decimalSeparator = '.')

    private suspend fun MoveSurplusViewModel.awaitEditing(p: (MoveSurplusState.Editing) -> Boolean = { true }) =
        awaitValue({ state }) { it is MoveSurplusState.Editing && p(it) } as MoveSurplusState.Editing

    private suspend fun BoostViewModel.awaitEditing(p: (BoostState.Editing) -> Boolean = { true }) =
        awaitValue({ state }) { it is BoostState.Editing && p(it) } as BoostState.Editing

    @Test
    fun `move preselects the only pocket with surplus and offers every other destination`() = runTest {
        setUp()
        val state = move().awaitEditing()
        assertEquals(foodPocket, state.form.from)
        assertEquals(listOf(foodPocket), state.sources.map { it.label.pocket })
        assertEquals(listOf(funPocket, SurplusPocket.Unallocated), state.destinations.map { it.label.pocket })
        assertEquals(eur(100), state.available)
    }

    @Test
    fun `move validates, offers all, and records both legs`() = runTest {
        setUp()
        val vm = move()
        vm.awaitEditing()
        vm.save()
        val missing = vm.awaitEditing { it.missingTo }
        assertEquals(AmountError.EMPTY, missing.amountError)

        vm.selectTo(funPocket)
        vm.onAmountChange("100.01")
        assertEquals(eur(100), vm.awaitEditing { it.form.amountText == "100.01" }.exceedsAvailable)
        vm.save()
        assertEquals(eur(100), surplus.balances.first()[foodPocket]) // nothing moved

        vm.useAll()
        assertEquals("100.00", vm.awaitEditing { it.form.amountText == "100.00" }.form.amountText)
        vm.onNoteChange(" for fun ")
        vm.save()
        awaitValue({ vm.state }) { it == MoveSurplusState.Done }
        val balances = surplus.balances.first()
        assertEquals(eur(0), balances[foodPocket])
        assertEquals(eur(100), balances[funPocket])
        assertEquals("for fun", surplus.ledger.first().last().note)
    }

    /** Fun spends [amount] in March with no amount set, and March is closed again: Fun owes it. */
    private suspend fun funOwes(amount: Long) {
        expenses.add(eur(amount), fun_.id, LocalDate.of(2026, 3, 9))
        surplus.closeMonth(ym("2026-03")).ok()
    }

    @Test
    fun `paying off preselects the pocket in debt and fills in what it owes`() = runTest {
        setUp()
        funOwes(30)
        val state = move("to" to funPocket.key()).awaitEditing { it.form.to != null }
        assertEquals(MoveForm(foodPocket, funPocket, "30.00", ""), state.form)
        assertEquals(eur(-30), state.payingOff?.balance)
        assertNull(state.exceedsAvailable)
    }

    @Test
    fun `paying off a debt larger than the source fills in what the source has`() = runTest {
        setUp()
        funOwes(130)
        assertEquals("100.00", move("to" to funPocket.key()).awaitEditing { it.form.to != null }.form.amountText)
    }

    @Test
    fun `a pocket that owes nothing isn't preselected, and an archived one in debt can still be paid off`() = runTest {
        setUp()
        val plain = move("to" to funPocket.key()).awaitEditing()
        assertNull(plain.form.to)
        assertEquals("", plain.form.amountText)
        assertNull(plain.payingOff)

        funOwes(30)
        categories.archive(fun_.id, ym("2026-04"))
        val archived = move("to" to funPocket.key()).awaitEditing { it.form.to != null }
        assertEquals(listOf(funPocket, SurplusPocket.Unallocated), archived.destinations.map { it.label.pocket })
        assertEquals("30.00", archived.form.amountText)
    }

    @Test
    fun `move form survives process death`() = runTest {
        setUp()
        val handle = SavedStateHandle()
        val first = move(handle = handle)
        first.awaitEditing()
        first.selectTo(funPocket)
        first.onAmountChange("12")
        val restored = move(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(MoveForm(foodPocket, funPocket, "12", ""), restored.awaitEditing().form)
    }

    @Test
    fun `boost preselects the category and its own surplus, and previews the new budget`() = runTest {
        setUp()
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 30_000), ym("2026-04"))
        val vm = boost("categoryId" to food.id.value)
        val state = vm.awaitEditing()
        assertEquals(food.id, state.form.destination)
        assertEquals(foodPocket, state.form.source)
        assertEquals(ym("2026-04"), state.month)
        assertEquals(eur(300), state.currentBudget)

        vm.onAmountChange("40")
        assertEquals(eur(40), vm.awaitEditing { it.amount != null }.amount)
        vm.save()
        awaitValue({ vm.state }) { it == BoostState.Done }

        val saved = surplus.boosts.first().single()
        assertEquals(BoostStatus.ACTIVE, saved.status)
        assertEquals(eur(340), budgets.loadMonthSummary(ym("2026-04")).categories.getValue(food.id).effective)
        assertEquals(eur(60), surplus.balances.first()[foodPocket])
    }

    @Test
    fun `boost months start at the current month and skip nothing closed`() = runTest {
        setUp()
        val vm = boost()
        vm.awaitEditing()
        vm.previousMonth()
        assertEquals(ym("2026-04"), vm.awaitEditing().month) // no earlier than now
        vm.nextMonth()
        assertEquals(ym("2026-05"), vm.awaitEditing { it.month == ym("2026-05") }.month)

        // Close the current month early: it can no longer be boosted.
        surplus.closeMonth(ym("2026-04")).ok()
        vm.goToCurrentMonth()
        val closed = vm.awaitEditing { it.month == ym("2026-04") && it.monthClosed }
        assertTrue(closed.monthClosed)
        vm.selectDestination(fun_.id)
        vm.onAmountChange("5")
        vm.save()
        assertTrue(surplus.boosts.first().isEmpty())
    }

    @Test
    fun `boost from a different pocket than the destination`() = runTest {
        setUp()
        val vm = boost("categoryId" to fun_.id.value, "month" to "2026-06")
        val state = vm.awaitEditing()
        assertEquals(ym("2026-06"), state.month)
        assertEquals(fun_.id, state.form.destination)
        assertEquals(foodPocket, state.form.source) // Fun has none; Food is the only pocket with surplus
        assertFalse(state.missingSource)
        assertNull(state.amount)
    }

    @Test
    fun `a deleted category is never a destination, even while the current month is closed`() = runTest {
        setUp()
        expenses.add(eur(1), fun_.id, java.time.LocalDate.of(2026, 4, 2))
        surplus.closeMonth(ym("2026-04")).ok() // from now on Fun stays in April
        surplus.deleteCategory(fun_.id)

        assertFalse(funPocket in move().awaitEditing().destinations.map { it.label.pocket })
        val vm = boost()
        vm.nextMonth()
        assertFalse(funPocket in vm.awaitEditing { it.month == ym("2026-05") }.destinations.map { it.label.pocket })
    }
}
