package lv.kristapsbe.budzets.ui.expenses

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.expense.DraftErrors
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExpenseEditorViewModelTest : ViewModelTest() {

    private val today = LocalDate.of(2026, 4, 15)

    private fun editor(vararg args: Pair<String, Any?>, handle: SavedStateHandle = SavedStateHandle(mapOf(*args))) =
        ExpenseEditorViewModel(handle, expenses, categories, settings, surplus, clock, decimalSeparator = '.')

    private suspend fun ExpenseEditorViewModel.awaitEditing(
        predicate: (ExpenseEditorState.Editing) -> Boolean = { true },
    ): ExpenseEditorState.Editing =
        awaitValue({ state }) { it is ExpenseEditorState.Editing && predicate(it) } as ExpenseEditorState.Editing

    private suspend fun ExpenseEditorViewModel.awaitDone() = awaitValue({ state }) { it == ExpenseEditorState.Done }

    @Test
    fun `new expense defaults to today and preselects the last used category`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        expenses.add(eur(1), fun_.id, LocalDate.of(2026, 4, 1))

        val state = editor().awaitEditing()
        assertTrue(state.isNew)
        assertEquals(today, state.form.date)
        assertEquals(fun_.id, state.form.categoryId)
        assertEquals(listOf(food.id, fun_.id), state.categories.map { it.id })
        assertEquals(DraftErrors(), state.errors)
    }

    @Test
    fun `category passed in wins, archived categories are neither offered nor preselected`() = runTest {
        val food = categories.create("Food")
        val old = categories.create("Old")
        expenses.add(eur(1), old.id, LocalDate.of(2026, 3, 1))
        categories.archive(old.id)

        assertEquals(food.id, editor(ExpenseEditorViewModel.ARG_CATEGORY_ID to food.id.value).awaitEditing().form.categoryId)

        val noPreselect = editor().awaitEditing() // last used is archived
        assertNull(noPreselect.form.categoryId)
        assertEquals(listOf(food.id), noPreselect.categories.map { it.id })
    }

    @Test
    fun `errors stay quiet while typing, then all show after a save attempt`() = runTest {
        categories.create("Food")
        val vm = editor()
        vm.awaitEditing()

        vm.onAmountChange("1.234")
        assertEquals(AmountError.TOO_MANY_DECIMALS, vm.awaitEditing().errors.amount) // typing more can't fix it
        vm.onAmountChange("0,")
        assertNull(vm.awaitEditing().errors.amount) // half-typed: no flashing error

        vm.onAmountChange("")
        vm.save()
        val errors = vm.awaitEditing { it.errors.amount != null }.errors
        assertEquals(AmountError.EMPTY, errors.amount)
        assertTrue(errors.missingCategory)
        assertTrue(expenses.observeMonth(ym("2026-04")).first().isEmpty())
    }

    @Test
    fun `amount input keeps only digits and separators`() = runTest {
        val vm = editor()
        vm.awaitEditing()
        vm.onAmountChange("€ 1,234.5 -x")
        assertEquals("1,234.5", vm.form.amountText)
    }

    @Test
    fun `future dates are refused even if set programmatically`() = runTest {
        val food = categories.create("Food")
        val vm = editor(ExpenseEditorViewModel.ARG_CATEGORY_ID to food.id.value)
        vm.awaitEditing()
        vm.onAmountChange("5")
        vm.onDateSelect(today.plusDays(1))
        vm.save()
        assertTrue(vm.awaitEditing { it.errors.futureDate }.errors.futureDate)
        assertTrue(expenses.observeMonth(ym("2026-04")).first().isEmpty())
    }

    @Test
    fun `saving adds the expense and updates the month totals`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000))
        val vm = editor(ExpenseEditorViewModel.ARG_CATEGORY_ID to food.id.value)
        vm.awaitEditing()
        vm.onAmountChange("12,5")
        vm.onNoteChange(" lunch ")
        vm.save()
        vm.awaitDone()

        val saved = expenses.observeMonth(ym("2026-04")).first().single()
        assertEquals(eur(12, 50), saved.amount)
        assertEquals(today, saved.date)
        assertEquals("lunch", saved.note)
        assertEquals(eur(12, 50), budgets.loadMonthSummary(ym("2026-04")).categories.getValue(food.id).spent)
    }

    @Test
    fun `editing prefills the form and moving the expense recalculates both months and categories`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        val expense = expenses.add(eur(1_234, 50), food.id, LocalDate.of(2026, 4, 10), note = "shop")

        val vm = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to expense.id.value)
        val state = vm.awaitEditing()
        assertFalse(state.isNew)
        assertEquals(ExpenseForm("1234.50", food.id, LocalDate.of(2026, 4, 10), "shop"), state.form)

        vm.onCategorySelect(fun_.id)
        vm.onDateSelect(LocalDate.of(2026, 3, 31))
        vm.save()
        vm.awaitDone()

        val april = budgets.loadMonthSummary(ym("2026-04"))
        val march = budgets.loadMonthSummary(ym("2026-03"))
        assertEquals(eur(0), april.totalSpent)
        assertEquals(eur(1_234, 50), march.categories.getValue(fun_.id).spent)
        assertNull(march.categories[food.id])
        assertEquals(expense.createdAt, expenses.get(expense.id)!!.createdAt)
    }

    @Test
    fun `an archived category stays selectable for an old expense that uses it`() = runTest {
        categories.create("Food")
        val old = categories.create("Old")
        val expense = expenses.add(eur(5), old.id, LocalDate.of(2026, 3, 3))
        categories.archive(old.id)

        val option = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to expense.id.value).awaitEditing().categories.last()
        assertEquals(CategoryOption(old.id, "Old", isArchived = true), option)
    }

    @Test
    fun `closed months touched by the change are flagged`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03"))
        val expense = expenses.add(eur(5), food.id, LocalDate.of(2026, 3, 3))
        surplus.closeMonth(ym("2026-03")).ok()

        val vm = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to expense.id.value)
        assertEquals(listOf(ym("2026-03")), vm.awaitEditing { it.closedMonthsAffected.isNotEmpty() }.closedMonthsAffected)
        vm.onDateSelect(today) // moving out of March still changes March
        assertEquals(listOf(ym("2026-03")), vm.awaitEditing { it.form.date == today }.closedMonthsAffected)

        val fresh = editor(ExpenseEditorViewModel.ARG_CATEGORY_ID to food.id.value)
        assertTrue(fresh.awaitEditing().closedMonthsAffected.isEmpty())
        fresh.onDateSelect(LocalDate.of(2026, 3, 20))
        assertEquals(listOf(ym("2026-03")), fresh.awaitEditing { it.form.date.monthValue == 3 }.closedMonthsAffected)
    }

    @Test
    fun `delete removes the expense and closes`() = runTest {
        val food = categories.create("Food")
        val expense = expenses.add(eur(5), food.id, today)
        val vm = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to expense.id.value)
        vm.awaitEditing()
        vm.delete()
        vm.awaitDone()
        assertNull(expenses.get(expense.id))
    }

    @Test
    fun `missing expense shows not found`() = runTest {
        val vm = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to ExpenseId("gone").value)
        val state = awaitValue({ vm.state }) { it == ExpenseEditorState.NotFound }
        assertEquals(ExpenseEditorState.NotFound, state)
    }

    @Test
    fun `form survives process death, and is not overwritten by preselection`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        expenses.add(eur(1), food.id, today)

        val handle = SavedStateHandle()
        val first = editor(handle = handle)
        first.awaitEditing()
        first.onAmountChange("7.25")
        first.onCategorySelect(fun_.id)
        first.onDateSelect(today.minusDays(1))
        first.onNoteChange("bus")

        // A new process gets a handle with the same saved values.
        val restored = editor(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        val form = restored.awaitEditing().form
        assertEquals(ExpenseForm("7.25", CategoryId(fun_.id.value), today.minusDays(1), "bus"), form)
    }

    @Test
    fun `a deleted category is offered only for an old expense that uses it`() = runTest {
        categories.create("Food")
        val old = categories.create("Old")
        val expense = expenses.add(eur(5), old.id, LocalDate.of(2026, 3, 3))
        surplus.deleteCategory(old.id)

        assertEquals(listOf("Food"), editor().awaitEditing().categories.map { it.name })
        val option = editor(ExpenseEditorViewModel.ARG_EXPENSE_ID to expense.id.value).awaitEditing().categories.last()
        assertEquals(CategoryOption(old.id, "Old", isArchived = true, isDeleted = true), option)
    }
}
