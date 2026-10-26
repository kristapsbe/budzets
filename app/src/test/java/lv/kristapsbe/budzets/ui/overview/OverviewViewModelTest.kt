package lv.kristapsbe.budzets.ui.overview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lv.kristapsbe.budzets.data.DbTest
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest : DbTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var vm: OverviewViewModel

    @Before
    fun createViewModel() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(tmp.root, "s.preferences_pb") },
            hasMonetaryData = { db.expenseDao().hasMonetaryData() },
        )
        vm = OverviewViewModel(budgets, surplus, categories, settings, clock)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    /** Waits in real time: Room and DataStore emit from their own threads. */
    private suspend fun awaitReady(predicate: (OverviewUiState.Ready) -> Boolean = { true }): OverviewUiState.Ready =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.uiState.first { it is OverviewUiState.Ready && predicate(it) } as OverviewUiState.Ready
            }
        }

    @Test
    fun `first launch - empty but usable`() = runTest {
        val state = awaitReady()
        assertEquals(ym("2026-04"), state.month)
        assertTrue(state.isCurrentMonth)
        assertFalse(state.hasCategories)
        assertFalse(state.hasBudget)
        assertEquals(PaceStatus.NO_BUDGET, state.pace.status)
        assertEquals("EUR", state.currency.code)
    }

    @Test
    fun `starter categories are created once and listed in order`() = runTest {
        vm.addStarterCategories(listOf("Groceries", "Fun"))
        val state = awaitReady { it.hasCategories }
        assertEquals(listOf("Groceries", "Fun"), state.rows.map { it.name })
        assertTrue(state.rows.all { it.summary.base == null })

        assertFalse(categories.createStarterCategories(listOf("Again")))
        assertEquals(2, categories.categories.first().size)
    }

    @Test
    fun `renders real data and updates reactively`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000)) // 600
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000)) // 300
        expenses.add(eur(100), food.id, LocalDate.of(2026, 4, 2))

        val before = awaitReady { it.summary.totalSpent == eur(100) }
        assertEquals(eur(600), before.summary.overallBase)
        assertEquals(eur(500), before.summary.overallRemaining)
        assertEquals(listOf(food.id, fun_.id), before.rows.map { it.id })
        assertNull(before.rows[1].summary.base) // Fun: active, no budget, still listed
        assertEquals(eur(300), before.pace.expectedSpend) // 15 of 30 days
        assertEquals(PaceStatus.ON_TRACK, before.pace.status)

        expenses.add(eur(250), food.id, LocalDate.of(2026, 4, 15))
        val after = awaitReady { it.summary.totalSpent == eur(350) }
        assertTrue(after.rows[0].summary.isOver)
        assertEquals(PaceStatus.WATCH, after.pace.status)
    }

    @Test
    fun `month navigation`() = runTest {
        awaitReady()
        vm.previousMonth()
        val march = awaitReady { it.month == ym("2026-03") }
        assertFalse(march.isCurrentMonth)
        vm.nextMonth(); vm.nextMonth()
        awaitReady { it.month == ym("2026-05") }
        vm.goToCurrentMonth()
        assertTrue(awaitReady { it.month == ym("2026-04") }.isCurrentMonth)
    }

    @Test
    fun `close status and surplus total`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03"))
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 5))
        vm.previousMonth()
        assertEquals(CloseStatus.OPEN, awaitReady { it.month == ym("2026-03") }.closeStatus)

        surplus.closeMonth(ym("2026-03")).ok()
        val closed = awaitReady { it.closeStatus == CloseStatus.CLOSED }
        assertEquals(eur(300), closed.totalSurplus)

        expenses.add(eur(5), food.id, LocalDate.of(2026, 3, 6))
        awaitReady { it.closeStatus == CloseStatus.CHANGED_SINCE_CLOSING }
    }

    @Test
    fun `archived category with spending is still shown, flagged`() = runTest {
        val old = categories.create("Old")
        expenses.add(eur(3), old.id, LocalDate.of(2026, 4, 1))
        categories.archive(old.id, ym("2026-04"))
        val row = awaitReady { it.rows.isNotEmpty() }.rows.single()
        assertTrue(row.isArchived)
        assertEquals(eur(3), row.summary.spent)
    }
}
