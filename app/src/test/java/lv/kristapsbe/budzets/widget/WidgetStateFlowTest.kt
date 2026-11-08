package lv.kristapsbe.budzets.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The flow a live widget session collects. One collection is kept open throughout, as in a
 * session, so every change has to arrive through it rather than through a fresh read.
 */
class WidgetStateFlowTest : ViewModelTest() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val latest = MutableStateFlow<WidgetState?>(null)

    @After
    fun stop() = scope.cancel()

    private suspend fun awaitState(predicate: (WidgetState) -> Boolean): WidgetState =
        latest.filterNotNull().awaitFirst(predicate)

    private suspend fun awaitMonth(predicate: (WidgetState.Month) -> Boolean = { true }): WidgetState.Month =
        awaitState { it is WidgetState.Month && predicate(it) } as WidgetState.Month

    @Test
    fun `one collection follows setup, expenses, budgets and currency`() = runTest {
        scope.launch {
            WidgetStates.observe(budgets, categories, surplus, settings, expenses, LocalDate.of(2026, 4, 15)).collect { latest.value = it }
        }
        assertEquals(WidgetState.NotSetUp, awaitState { true })

        val food = categories.create("Food")
        val empty = awaitMonth()
        assertEquals(ym("2026-04"), empty.month)
        assertEquals(eur(0), empty.spent)

        expenses.add(eur(5), food.id, LocalDate.of(2026, 4, 2))
        awaitMonth { it.spent == eur(5) }

        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = eur(300).minor), ym("2026-04"))
        val budgeted = awaitMonth { it.hasBudget }
        assertEquals(eur(295), budgeted.remaining)
        // €295 left at the start of the 15th, over 16 days.
        assertEquals(eur(18, 43), budgeted.daily!!.perDay)

        // Spending today moves what's left of today, not the daily amount.
        expenses.add(eur(8), food.id, LocalDate.of(2026, 4, 15))
        val spentToday = awaitMonth { it.spent == eur(13) }.daily!!
        assertEquals(eur(18, 43), spentToday.perDay)
        assertEquals(eur(10, 43), spentToday.leftToday)

        settings.setCurrency(CurrencySpec.of("USD"))
        awaitMonth { it.currency.code == "USD" }
    }
}
