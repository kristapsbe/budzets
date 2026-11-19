package lv.kristapsbe.budzets.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The months behind the spending chart. "Now" is 2026-04-15. */
class MonthSummariesTest : DbTest() {

    private val april = ym("2026-04")

    @Test
    fun `no expenses, just the month under way`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), ym("2026-01"))
        val months = budgets.observeMonthSummaries(april).first()
        assertEquals(listOf(april), months.map { it.month })
    }

    @Test
    fun `planned months follow without moving the start, with their budgets but no spending`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 60_000), ym("2026-06"))
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 5))
        // Dated after "now" (a clock change): it stays out of the planned months as before.
        expenses.add(eur(99), food.id, LocalDate.of(2026, 5, 2))

        val months = budgets.observeMonthSummaries(april, planThrough = ym("2026-07")).first()

        assertEquals((3..7).map { ym("2026-0$it") }, months.map { it.month })
        assertEquals(eur(500), months.first { it.month == ym("2026-05") }.overallBase)
        assertEquals(eur(600), months.first { it.month == ym("2026-06") }.overallBase)
        assertEquals(eur(0), months.first { it.month == ym("2026-05") }.totalSpent)
    }

    @Test
    fun `with no data the range starts at the month under way, planned months after it`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 50_000), ym("2026-01"))
        val months = budgets.observeMonthSummaries(april, planThrough = ym("2026-06")).first()
        assertEquals(listOf(april, ym("2026-05"), ym("2026-06")), months.map { it.month })
    }

    @Test
    fun `a closed month before the first expense starts the months`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-01"))
        surplus.closeMonth(ym("2026-01")).ok()
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 5))

        val months = budgets.observeMonthSummaries(april).first()

        assertEquals(listOf(ym("2026-01"), ym("2026-02"), ym("2026-03"), april), months.map { it.month })
    }

    @Test
    fun `every month from the first expense to now, gaps included, each as the overview has it`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), ym("2026-01"))
        expenses.add(eur(30), food.id, LocalDate.of(2026, 1, 31))
        expenses.add(eur(12), fun_.id, LocalDate.of(2026, 1, 3))
        expenses.add(eur(50), food.id, LocalDate.of(2026, 3, 1))
        expenses.add(eur(7), food.id, LocalDate.of(2026, 4, 15))

        val months = budgets.observeMonthSummaries(april).first()

        assertEquals(listOf(ym("2026-01"), ym("2026-02"), ym("2026-03"), april), months.map { it.month })
        months.forEach { assertEquals(budgets.loadMonthSummary(it.month), it) }
        assertEquals(eur(42), months[0].totalSpent)
        assertEquals(eur(0), months[1].totalSpent)
    }

    @Test
    fun `a closed month keeps the budget it was closed with`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-01"))
        expenses.add(eur(150), food.id, LocalDate.of(2026, 3, 5))
        surplus.closeMonth(ym("2026-03")).ok()
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 200_000), ym("2026-02"))

        val months = budgets.observeMonthSummaries(april).first().associateBy { it.month }

        assertEquals(eur(1_000), months.getValue(ym("2026-03")).overallEffective)
        assertEquals(eur(2_000), months.getValue(april).overallEffective)
        assertEquals(budgets.loadMonthSummary(ym("2026-03")), months.getValue(ym("2026-03")))
    }

    @Test
    fun `a boost raises its month's budget`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = 100_000), ym("2026-01"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(monthlyMinor = 40_000), ym("2026-01"))
        expenses.add(eur(100), food.id, LocalDate.of(2026, 3, 5))
        surplus.closeMonth(ym("2026-03")).ok()
        surplus.allocateBoost(SurplusPocket.Category(food.id), food.id, april, eur(50)).ok()

        val months = budgets.observeMonthSummaries(april).first().associateBy { it.month }

        assertEquals(eur(1_050), months.getValue(april).overallEffective)
        assertEquals(eur(1_000), months.getValue(ym("2026-03")).overallEffective)
    }

    @Test
    fun `spending dated after now is left out`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 3, 5))
        expenses.add(eur(99), food.id, LocalDate.of(2026, 6, 1))

        val months = budgets.observeMonthSummaries(april).first()

        assertEquals(listOf(ym("2026-03"), april), months.map { it.month })
        assertEquals(eur(0), months.last().totalSpent)
    }

    @Test
    fun `a new expense updates the months`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 1))
        val updates = Channel<List<MonthSummary>>(Channel.UNLIMITED)
        backgroundScope.launch(Dispatchers.Default) { budgets.observeMonthSummaries(april).collect { updates.send(it) } }
        assertEquals(1, receive(updates).size)

        expenses.add(eur(5), food.id, LocalDate.of(2026, 2, 10))

        var months = receive(updates)
        while (months.size != 3) months = receive(updates)
        assertEquals(eur(5), months.first().totalSpent)
    }

    /** Waits in real time: Room emits from its own thread. */
    private suspend fun <T> receive(channel: Channel<T>): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { channel.receive() } }
}
