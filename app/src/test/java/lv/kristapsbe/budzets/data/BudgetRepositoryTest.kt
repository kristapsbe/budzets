package lv.kristapsbe.budzets.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetRepositoryTest : DbTest() {

    @Test
    fun `saving the same month updates in place, a new month adds a version`() = runTest {
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 2_000), ym("2026-03"))
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 3_000)) // defaults to current month (April)
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(weeklyMinor = 20_000)) // same month -> update

        val configs = budgets.configs.first()
        assertEquals(listOf(ym("2026-03"), ym("2026-04")), configs.map { it.effectiveFrom })
        assertEquals(BudgetTarget(weeklyMinor = 20_000), configs.last().target)

        // March keeps its own version; April uses the edited one.
        assertEquals(eur(620), budgets.loadMonthSummary(ym("2026-03")).overallBase) // 31 * 20
        assertEquals(eur(857, 14), budgets.loadMonthSummary(ym("2026-04")).overallBase) // 200 * 30 / 7
    }

    @Test
    fun `summary combines budgets, expenses and is derived without overall`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(dailyMinor = 500))
        expenses.add(eur(40), food.id, LocalDate.of(2026, 4, 3))
        expenses.add(eur(2), food.id, LocalDate.of(2026, 4, 4))
        expenses.add(eur(99), food.id, LocalDate.of(2026, 3, 31))

        val s = budgets.loadMonthSummary(ym("2026-04"))
        assertTrue(s.overallDerived)
        assertEquals(eur(450), s.overallBase)
        assertEquals(eur(42), s.totalSpent)
        assertEquals(eur(258), s.categories.getValue(food.id).remaining)
        assertEquals(eur(150), s.categories.getValue(fun_.id).remaining)
    }

    @Test
    fun `month summary flow re-emits after an expense is added`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 1_000))
        val flow = budgets.observeMonthSummary(ym("2026-04"))
        val subscribed = CompletableDeferred<Unit>()
        // Real dispatcher + timeout: runTest's virtual clock would expire while Room works on its own threads.
        val next = async(Dispatchers.Default) {
            withTimeout(5_000) {
                flow.onEach { if (subscribed.complete(Unit)) assertEquals(eur(0), it.totalSpent) }.drop(1).first()
            }
        }
        subscribed.await()

        expenses.add(eur(12), food.id, LocalDate.of(2026, 4, 15))
        val updated = next.await()
        assertEquals(eur(12), updated.totalSpent)
        assertEquals(eur(288), updated.overallLeft)
    }

    @Test
    fun `archived category leaves plans from its archive month`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-01"))
        categories.archive(food.id, ym("2026-04"))
        assertEquals(eur(310), budgets.loadMonthSummary(ym("2026-03")).categories.getValue(food.id).base)
        assertNull(budgets.loadMonthSummary(ym("2026-04")).categories[food.id])
    }

    @Test
    fun `monthly target round-trips, and a mid-range edit leaves earlier and later versions alone`() = runTest {
        val food = BudgetScope.Category(categories.create("Food").id)
        budgets.setTarget(food, BudgetTarget(dailyMinor = 1_000), ym("2026-01"))
        budgets.setTarget(food, BudgetTarget(dailyMinor = 2_000), ym("2026-09"))

        budgets.setTarget(food, BudgetTarget(weeklyMinor = 7_000, monthlyMinor = 25_000), ym("2026-04"))

        val target = budgets.configs.first().single { it.effectiveFrom == ym("2026-04") }.target
        assertEquals(BudgetTarget(weeklyMinor = 7_000, monthlyMinor = 25_000), target)
        val foodId = food.id
        assertEquals(eur(310), budgets.loadMonthSummary(ym("2026-03")).categories.getValue(foodId).base) // Jan version
        assertEquals(eur(300), budgets.loadMonthSummary(ym("2026-04")).categories.getValue(foodId).base) // max(300, 250)
        assertEquals(eur(310), budgets.loadMonthSummary(ym("2026-08")).categories.getValue(foodId).base) // max(310, 250)
        assertEquals(eur(600), budgets.loadMonthSummary(ym("2026-09")).categories.getValue(foodId).base) // Sep version
    }
}
