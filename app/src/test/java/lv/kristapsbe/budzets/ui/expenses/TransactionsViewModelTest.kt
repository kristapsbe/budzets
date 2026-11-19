package lv.kristapsbe.budzets.ui.expenses

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
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
import org.junit.Test
import java.time.LocalDate

class TransactionsViewModelTest : ViewModelTest() {

    private fun transactions(vararg args: Pair<String, Any?>) =
        TransactionsViewModel(SavedStateHandle(mapOf(*args)), expenses, categories, settings, surplus, clock)

    private suspend fun TransactionsViewModel.awaitReady(
        predicate: (TransactionsUiState.Ready) -> Boolean = { true },
    ): TransactionsUiState.Ready =
        uiState.awaitFirst { it is TransactionsUiState.Ready && predicate(it) } as TransactionsUiState.Ready

    @Test
    fun `lists the month by day, newest first, and totals reconcile with the overview`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000))
        expenses.add(eur(3), food.id, LocalDate.of(2026, 4, 1))
        expenses.add(eur(4), fun_.id, LocalDate.of(2026, 4, 14), note = "cinema")
        expenses.add(eur(5), food.id, LocalDate.of(2026, 4, 14))
        expenses.add(eur(99), food.id, LocalDate.of(2026, 3, 31)) // other month
        expenses.add(eur(98), food.id, LocalDate.of(2026, 5, 1))

        val state = transactions().awaitReady { it.count == 3 }
        assertEquals(ym("2026-04"), state.month)
        assertEquals(listOf(LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 1)), state.days.map { it.date })
        assertEquals(listOf(eur(9), eur(3)), state.days.map { it.total })
        // Same day: newest entry first.
        assertEquals(listOf(eur(5), eur(4)), state.days[0].rows.map { it.amount })
        assertEquals("cinema", state.days[0].rows[1].note)

        val summary = budgets.loadMonthSummary(ym("2026-04"))
        assertEquals(summary.totalSpent, state.total)
        assertEquals(eur(12), state.total)

        val vm = transactions()
        vm.selectCategory(food.id)
        val foodOnly = vm.awaitReady { it.selectedCategory == food.id }
        assertEquals(summary.categories.getValue(food.id).spent, foodOnly.total)
        assertEquals(2, foodOnly.count)
        assertEquals("Food", foodOnly.selectedCategoryName)
    }

    @Test
    fun `a page shows its own month with the same filters, and selecting one moves the rest`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        expenses.add(eur(3), food.id, LocalDate.of(2026, 3, 2))
        expenses.add(eur(4), fun_.id, LocalDate.of(2026, 3, 3))
        val march = ym("2026-03")
        val handle = SavedStateHandle()
        val vm = TransactionsViewModel(handle, expenses, categories, settings, surplus, clock)
        vm.selectCategory(food.id)

        val page = vm.stateFor(march).awaitFirst { it is TransactionsUiState.Ready && it.selectedCategory == food.id } as TransactionsUiState.Ready
        assertEquals(march, page.month)
        assertEquals(eur(3), page.total)
        assertEquals(ym("2026-04"), vm.selectedMonth.value)

        vm.selectMonth(march)
        assertEquals("2026-03", handle.get<String>("month"))
        assertEquals(eur(3), vm.awaitReady { it.month == march }.total)

        vm.selectMonth(ym("2026-04"))
        assertNull(handle.get<String>("month")) // the current month goes on following the date
    }

    @Test
    fun `note search is case-insensitive and combines with the category filter`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        expenses.add(eur(1), food.id, LocalDate.of(2026, 4, 2), note = "Bread and milk")
        expenses.add(eur(2), fun_.id, LocalDate.of(2026, 4, 3), note = "milkshake")
        expenses.add(eur(3), food.id, LocalDate.of(2026, 4, 4))

        val vm = transactions()
        vm.setQuery(" MILK ")
        assertEquals(eur(3), vm.awaitReady { it.query.isNotEmpty() }.total)
        vm.selectCategory(fun_.id)
        assertEquals(eur(2), vm.awaitReady { it.selectedCategory == fun_.id }.total)
        vm.setQuery("nothing")
        val none = vm.awaitReady { it.query == "nothing" }
        assertEquals(0, none.count)
        assertTrue(none.days.isEmpty())
    }

    @Test
    fun `a drill-down starts on the route's category and month`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        expenses.add(eur(7), fun_.id, LocalDate.of(2026, 3, 9))
        expenses.add(eur(8), food.id, LocalDate.of(2026, 3, 9))

        val state = transactions("categoryId" to fun_.id.value, "month" to "2026-03").awaitReady()
        assertEquals(ym("2026-03"), state.month)
        assertFalse(state.isCurrentMonth)
        assertEquals(fun_.id, state.selectedCategory)
        assertEquals(eur(7), state.total)
    }

    @Test
    fun `the month under way shows the selected category's surplus pocket`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        expenses.add(eur(30), fun_.id, LocalDate.of(2026, 3, 9))
        surplus.closeMonth(ym("2026-03")).ok() // Food saves €100, Fun owes €30
        surplus.addOneOff(ym("2026-04"), eur(20)).ok()

        val funNow = transactions("categoryId" to fun_.id.value).awaitReady { it.pocket != null }.pocket!!
        assertEquals(CategoryPocket(eur(-30), eur(120), isActive = true), funNow)
        assertTrue(funNow.canReceive)
        val foodNow = transactions("categoryId" to food.id.value).awaitReady { it.pocket != null }.pocket!!
        assertEquals(CategoryPocket(eur(100), eur(20), isActive = true), foodNow)

        // Not for an earlier month, and not without a category.
        assertNull(transactions("categoryId" to fun_.id.value, "month" to "2026-03").awaitReady().pocket)
        assertNull(transactions().awaitReady().pocket)
    }

    @Test
    fun `an archived category can be paid off but not added to`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 10_000), ym("2026-03"))
        categories.archive(food.id, ym("2026-04"))
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.addOneOff(ym("2026-04"), eur(20)).ok()
        val pocket = transactions("categoryId" to food.id.value).awaitReady { it.pocket != null }.pocket!!
        assertFalse(pocket.isActive)
        assertFalse(pocket.canReceive)
        assertTrue(CategoryPocket(eur(-5), eur(20), isActive = false).canReceive)
        assertFalse(CategoryPocket(eur(-5), eur(0)).canReceive)
    }

    @Test
    fun `month navigation keeps the filter`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(1), food.id, LocalDate.of(2026, 3, 9))
        val vm = transactions()
        vm.selectCategory(food.id)
        vm.previousMonth()
        val march = vm.awaitReady { it.month == ym("2026-03") }
        assertEquals(food.id, march.selectedCategory)
        assertEquals(eur(1), march.total)
        vm.goToCurrentMonth()
        assertTrue(vm.awaitReady { it.month == ym("2026-04") }.isCurrentMonth)
    }

    @Test
    fun `chips show active categories, plus archived ones with spending, plus the selection`() = runTest {
        val food = categories.create("Food")
        val oldWithSpending = categories.create("Old")
        val oldEmpty = categories.create("Older")
        expenses.add(eur(1), oldWithSpending.id, LocalDate.of(2026, 4, 2))
        categories.archive(oldWithSpending.id, ym("2026-04"))
        categories.archive(oldEmpty.id, ym("2026-04"))

        assertEquals(listOf(food.id, oldWithSpending.id), transactions().awaitReady().chips.map { it.id })
        val selected = transactions("categoryId" to oldEmpty.id.value).awaitReady()
        assertEquals(listOf(food.id, oldWithSpending.id, oldEmpty.id), selected.chips.map { it.id })
        assertTrue(transactions().awaitReady { it.count == 1 }.days.single().rows.single().isArchivedCategory)
    }

    @Test
    fun `delete is immediate and undo restores the same expense`() = runTest {
        val food = categories.create("Food")
        val expense = expenses.add(eur(6), food.id, LocalDate.of(2026, 4, 2), note = "keep")
        val vm = transactions()
        vm.awaitReady { it.count == 1 }

        vm.delete(expense.id)
        val event = vm.deleteEvent.awaitFirst { it != null } as DeleteEvent.Deleted
        assertEquals(expense, event.expense)
        assertEquals(0, vm.awaitReady { it.count == 0 }.count)

        vm.undoDelete(event)
        vm.awaitReady { it.count == 1 }
        assertEquals(expense, expenses.get(expense.id))
        assertNull(vm.deleteEvent.value)
    }

    @Test
    fun `a repeated delete and a stale handled call don't disturb the newest undo`() = runTest {
        val food = categories.create("Food")
        val a = expenses.add(eur(1), food.id, LocalDate.of(2026, 4, 2))
        val b = expenses.add(eur(2), food.id, LocalDate.of(2026, 4, 3))
        val vm = transactions()

        vm.delete(a.id)
        val first = vm.deleteEvent.awaitFirst { it != null } as DeleteEvent.Deleted
        vm.delete(a.id) // already gone: no new event
        vm.delete(b.id)
        val second = vm.deleteEvent.awaitFirst { (it as? DeleteEvent.Deleted)?.expense == b } as DeleteEvent.Deleted

        vm.eventHandled(first) // the old snackbar finishing must not clear the new one
        assertEquals(second, vm.deleteEvent.value)
        vm.eventHandled(second)
        assertNull(vm.deleteEvent.value)
    }

    @Test
    fun `a closed month offers only what it has spending in, with no marks`() = runTest {
        val food = categories.create("Food")
        val old = categories.create("Old")
        expenses.add(eur(1), old.id, LocalDate.of(2026, 3, 2))
        surplus.closeMonth(ym("2026-03")).ok()
        categories.create("New")
        surplus.deleteCategory(old.id)

        val march = transactions("month" to "2026-03").awaitReady { it.count == 1 }
        assertEquals(listOf(old.id), march.chips.map { it.id })
        val row = march.days.single().rows.single()
        assertEquals(false, row.isArchivedCategory)
        assertEquals(false, row.isDeletedCategory)
        assertTrue(food.id !in march.chips.map { it.id })
    }

    @Test
    fun `a deleted category's expense in an open month is marked`() = runTest {
        val old = categories.create("Old")
        expenses.add(eur(1), old.id, LocalDate.of(2026, 4, 2))
        surplus.deleteCategory(old.id)

        val april = transactions().awaitReady { it.days.singleOrNull()?.rows?.single()?.isDeletedCategory == true }
        assertEquals(false, april.hasCategories)
    }
}
