package lv.kristapsbe.budzets.ui.budgets

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.TargetErrors
import lv.kristapsbe.budzets.domain.calculation.TargetKind
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BudgetEditorViewModelTest : ViewModelTest() {

    private fun editor(categoryId: CategoryId?, month: String, handle: SavedStateHandle? = null) =
        BudgetEditorViewModel(
            handle ?: SavedStateHandle(mapOf("categoryId" to categoryId?.value, "month" to month)),
            budgets, categories, settings, surplus, decimalSeparator = '.',
        )

    private suspend fun BudgetEditorViewModel.awaitEditing(
        predicate: (BudgetEditorState.Editing) -> Boolean = { true },
    ): BudgetEditorState.Editing =
        awaitValue({ state }) { it is BudgetEditorState.Editing && predicate(it) } as BudgetEditorState.Editing

    @Test
    fun `starts from the version in force, unchanged until edited`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_050, monthlyMinor = 60_000), ym("2026-01"))
        val vm = editor(null, "2026-04")
        val state = vm.awaitEditing()
        assertNull(state.categoryName)
        assertEquals(TargetForm(daily = "20.50", monthly = "600.00"), state.form)
        assertFalse(state.isChanged)
        assertEquals(eur(615), state.preview!!.amount) // 20.50 × 30 beats 600
        assertEquals(setOf(TargetKind.DAILY), state.preview.determinedBy)

        vm.save() // nothing to store
        assertEquals(1, budgets.configs.first().size)
    }

    @Test
    fun `preview follows typing and shows which target sets the plan`() = runTest {
        val food = categories.create("Food")
        val vm = editor(food.id, "2026-04")
        assertEquals("Food", vm.awaitEditing().categoryName)
        assertTrue(vm.awaitEditing().clearsBudget)

        vm.onDailyChange("10")
        vm.onWeeklyChange("70")
        vm.onMonthlyChange("350")
        val state = vm.awaitEditing { it.form.monthly == "350" }
        assertEquals(BudgetTarget(1_000, 7_000, 35_000), state.draft)
        assertEquals(eur(350), state.preview!!.amount)
        assertEquals(eur(300), state.preview.dailyEquivalent)
        assertEquals(eur(300), state.preview.weeklyEquivalent)
        assertEquals(setOf(TargetKind.MONTHLY), state.preview.determinedBy)
        assertTrue(state.isChanged)
    }

    @Test
    fun `saving stores a version for that month only, keeping earlier and later ones`() = runTest {
        val food = categories.create("Food")
        val scope = BudgetScope.Category(food.id)
        budgets.setTarget(scope, BudgetTarget(dailyMinor = 1_000), ym("2026-01"))
        budgets.setTarget(scope, BudgetTarget(dailyMinor = 3_000), ym("2026-09"))

        val vm = editor(food.id, "2026-06")
        assertEquals(ym("2026-09"), vm.awaitEditing().nextChange)
        vm.onDailyChange("")
        vm.onMonthlyChange("400")
        vm.save()
        awaitValue({ vm.state }) { it == BudgetEditorState.Done }

        val versions = budgets.configs.first().filter { it.scope == scope }.associate { it.effectiveFrom to it.target }
        assertEquals(
            mapOf(
                ym("2026-01") to BudgetTarget(dailyMinor = 1_000),
                ym("2026-06") to BudgetTarget(monthlyMinor = 40_000),
                ym("2026-09") to BudgetTarget(dailyMinor = 3_000),
            ),
            versions,
        )
        assertEquals(eur(310), budgets.loadMonthSummary(ym("2026-05")).categories.getValue(food.id).base)
        assertEquals(eur(400), budgets.loadMonthSummary(ym("2026-08")).categories.getValue(food.id).base)
        assertEquals(eur(900), budgets.loadMonthSummary(ym("2026-09")).categories.getValue(food.id).base)
    }

    @Test
    fun `clearing all fields removes the budget from that month`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-01"))
        val vm = editor(food.id, "2026-04")
        vm.awaitEditing()
        vm.clear()
        assertTrue(vm.awaitEditing { it.form == TargetForm() }.clearsBudget)
        vm.save()
        awaitValue({ vm.state }) { it == BudgetEditorState.Done }

        assertNull(budgets.loadMonthSummary(ym("2026-04")).categories[food.id]?.base)
        assertEquals(eur(310), budgets.loadMonthSummary(ym("2026-03")).categories.getValue(food.id).base)
    }

    @Test
    fun `warns while the draft would put categories above the overall`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(monthlyMinor = 20_000))

        val vm = editor(food.id, "2026-04")
        assertFalse(vm.awaitEditing().exceedsOverall)
        vm.onMonthlyChange("300")
        assertFalse(vm.awaitEditing { it.form.monthly == "300" }.exceedsOverall) // exactly 500
        vm.onMonthlyChange("300.01")
        val over = vm.awaitEditing { it.form.monthly == "300.01" }
        assertTrue(over.exceedsOverall)
        assertEquals(eur(500, 1), over.categoryTotal)
        assertEquals(eur(500), over.overallBase)
    }

    @Test
    fun `closed months within the range the change covers are listed`() = runTest {
        val food = categories.create("Food")
        val scope = BudgetScope.Category(food.id)
        budgets.setTarget(scope, BudgetTarget(dailyMinor = 100), ym("2026-01"))
        budgets.setTarget(scope, BudgetTarget(dailyMinor = 200), ym("2026-03"))
        surplus.closeMonth(ym("2026-01")).ok()
        surplus.closeMonth(ym("2026-02")).ok()
        surplus.closeMonth(ym("2026-03")).ok()

        // From February, until the March version takes over: only February.
        assertEquals(listOf(ym("2026-02")), editor(food.id, "2026-02").awaitEditing().closedMonthsInRange)
        assertEquals(listOf(ym("2026-03")), editor(food.id, "2026-03").awaitEditing().closedMonthsInRange)
        assertTrue(editor(food.id, "2026-04").awaitEditing().closedMonthsInRange.isEmpty())
    }

    @Test
    fun `errors that typing can't fix show at once, others after a save attempt`() = runTest {
        val vm = editor(null, "2026-04")
        vm.awaitEditing()
        vm.onDailyChange("1.234")
        assertEquals(TargetErrors(daily = AmountError.TOO_MANY_DECIMALS), vm.awaitEditing { it.form.daily == "1.234" }.errors)

        vm.onDailyChange(",")
        assertEquals(TargetErrors(), vm.awaitEditing { it.form.daily == "," }.errors)
        assertNull(vm.awaitEditing().preview)
        vm.save()
        assertEquals(AmountError.NOT_A_NUMBER, vm.awaitEditing { it.errors.daily != null }.errors.daily)
        assertTrue(budgets.configs.first().isEmpty())
    }

    @Test
    fun `missing category shows not found`() = runTest {
        val vm = editor(CategoryId("gone"), "2026-04")
        val state = awaitValue({ vm.state }) { it == BudgetEditorState.NotFound }
        assertEquals(BudgetEditorState.NotFound, state)
    }

    @Test
    fun `form survives process death and isn't overwritten by the stored version`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 1_000))
        val handle = SavedStateHandle(mapOf("month" to YearMonth.of(2026, 4).toString()))
        val first = editor(null, "2026-04", handle)
        first.awaitEditing()
        first.onWeeklyChange("99")

        val restored = editor(null, "2026-04", SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(TargetForm(daily = "10.00", weekly = "99"), restored.awaitEditing().form)
    }
}
