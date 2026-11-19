package lv.kristapsbe.budzets.data

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.ClosureError
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SurplusRepositoryTest : DbTest() {

    private val march = ym("2026-03")
    private val april = ym("2026-04")

    private lateinit var food: Category
    private lateinit var travel: Category

    /** March (31 days): overall and food both 10.00/day = 310, food spent 210 → food surplus 100, no unallocated. */
    private suspend fun seedMarch() {
        food = categories.create("Food")
        travel = categories.create("Travel")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 1_000), march) // 310
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), march) // 310
        expenses.add(eur(210), food.id, LocalDate.of(2026, 3, 10))
    }

    private suspend fun balance(pocket: SurplusPocket) = surplus.balances.first()[pocket] ?: eur(0)

    @Test
    fun `closing persists credits and a snapshot, and is idempotent`() = runTest {
        seedMarch()
        val closure = surplus.closeMonth(march).ok()
        assertEquals(1, closure.revision)
        assertEquals(eur(100), balance(SurplusPocket.Category(food.id)))

        assertEquals(ClosureError.NothingToChange, surplus.closeMonth(march).err())
        assertEquals(1, surplus.ledger.first().size)
        assertEquals(closure, surplus.latestClosure(march))
        assertEquals(setOf(march), surplus.closedMonths.first())
    }

    @Test
    fun `concurrent closes of the same month credit surplus exactly once`() = runTest {
        seedMarch()
        val results = (1..5).map { async { surplus.closeMonth(march) } }.awaitAll()
        assertEquals(1, results.count { it is Outcome.Ok })
        assertEquals(1, surplus.ledger.first().size)
        assertEquals(eur(100), balance(SurplusPocket.Category(food.id)))
    }

    @Test
    fun `expense change after closing is detected and re-close posts only the delta`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        assertFalse(surplus.hasChangesSinceClosing(march))

        expenses.add(eur(30), food.id, LocalDate.of(2026, 3, 20))
        assertTrue(surplus.hasChangesSinceClosing(march))
        assertTrue(surplus.hasChangesSinceClosing(surplus.ledger.first(), budgets.loadMonthSummary(march)))

        assertEquals(2, surplus.closeMonth(march).ok().revision)
        assertEquals(2, surplus.ledger.first().size)
        assertEquals(eur(70), balance(SurplusPocket.Category(food.id)))
        assertFalse(surplus.hasChangesSinceClosing(march))
    }

    @Test
    fun `transfer persists both legs`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        surplus.transfer(SurplusPocket.Category(food.id), SurplusPocket.Category(travel.id), eur(60), "trip").ok()
        assertEquals(eur(40), balance(SurplusPocket.Category(food.id)))
        assertEquals(eur(60), balance(SurplusPocket.Category(travel.id)))
        assertEquals(
            SurplusError.InsufficientSurplus(eur(40)),
            surplus.transfer(SurplusPocket.Category(food.id), SurplusPocket.Category(travel.id), eur(41)).err(),
        )
    }

    @Test
    fun `a transfer that fails half-way leaves no partial rows`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        // Both legs get the same id, so inserting the second leg violates the primary key.
        val broken = SurplusRepository(db, budgets, clock) { "duplicate-id" }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { broken.transfer(SurplusPocket.Category(food.id), SurplusPocket.Category(travel.id), eur(10)) }
        }
        assertEquals(1, surplus.ledger.first().size)
        assertEquals(eur(100), balance(SurplusPocket.Category(food.id)))
    }

    @Test
    fun `a transfer from all surplus persists every leg, Unallocated first`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        surplus.addOneOff(april, eur(25)).ok()
        val legs = surplus.transferFromAll(SurplusPocket.Category(travel.id), eur(110), "trip").ok()

        assertEquals(4, legs.size)
        assertEquals(eur(0), balance(SurplusPocket.Unallocated))
        assertEquals(eur(15), balance(SurplusPocket.Category(food.id)))
        assertEquals(eur(110), balance(SurplusPocket.Category(travel.id)))
        assertEquals(6, surplus.ledger.first().size)
        assertEquals(
            SurplusError.InsufficientSurplus(eur(15)),
            surplus.transferFromAll(SurplusPocket.Category(travel.id), eur(16)).err(),
        )
    }

    @Test
    fun `a transfer from all surplus that fails half-way leaves no partial rows`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        surplus.addOneOff(april, eur(25)).ok()
        val broken = SurplusRepository(db, budgets, clock) { "duplicate-id" }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { broken.transferFromAll(SurplusPocket.Category(travel.id), eur(110)) }
        }
        assertEquals(2, surplus.ledger.first().size)
        assertEquals(eur(25), balance(SurplusPocket.Unallocated))
    }

    @Test
    fun `boost allocation, double allocation refused, release restores`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        val boost = surplus.allocateBoost(SurplusPocket.Category(food.id), travel.id, april, eur(80)).ok()
        assertEquals(eur(20), balance(SurplusPocket.Category(food.id)))
        assertEquals(eur(80), budgets.loadMonthSummary(april).categories.getValue(travel.id).boost)

        assertEquals(
            SurplusError.InsufficientSurplus(eur(20)),
            surplus.allocateBoost(SurplusPocket.Category(food.id), travel.id, april, eur(80)).err(),
        )
        assertEquals(SurplusError.MonthInPast, surplus.allocateBoost(SurplusPocket.Category(food.id), travel.id, march, eur(1)).err())

        val released = surplus.releaseBoost(boost.id).ok()
        assertEquals(BoostStatus.RELEASED, released.status)
        assertEquals(eur(100), balance(SurplusPocket.Category(food.id)))
        assertEquals(eur(0), budgets.loadMonthSummary(april).totalBoosts)
        assertEquals(listOf(BoostStatus.RELEASED), surplus.boosts.first().map { it.status })
        assertEquals(SurplusError.AlreadyReleased, surplus.releaseBoost(boost.id).err())
    }

    @Test
    fun `reopen reverses, is refused once surplus has been moved`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        val reopened = surplus.reopenMonth(march).ok()
        assertFalse(reopened.isClosed)
        assertEquals(eur(0), balance(SurplusPocket.Category(food.id)))
        assertEquals(emptySet<Any>(), surplus.closedMonths.first())
        assertEquals(ClosureError.NotClosed, surplus.reopenMonth(march).err())

        assertEquals(2, surplus.closeMonth(march).ok().revision)
        surplus.transfer(SurplusPocket.Category(food.id), SurplusPocket.Category(travel.id), eur(100)).ok()
        assertTrue(surplus.reopenMonth(march).err() is ClosureError.WouldGoNegative)
    }

    @Test
    fun `cannot boost a closed month`() = runTest {
        seedMarch()
        surplus.closeMonth(march).ok()
        surplus.closeMonth(april).ok() // current month may be closed early
        assertEquals(SurplusError.MonthClosed, surplus.allocateBoost(SurplusPocket.Category(food.id), travel.id, april, eur(1)).err())
    }

    @Test
    fun `close state follows closing, later changes and reopening`() = runTest {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03")) // 310
        expenses.add(eur(300), food.id, LocalDate.of(2026, 3, 5))
        val pocket = SurplusPocket.Category(food.id)
        val flow = surplus.observeCloseState(ym("2026-03"))

        val open = flow.first()
        assertNull(open.latest)
        assertEquals(mapOf(pocket to eur(10)), open.review.pending)

        surplus.closeMonth(ym("2026-03")).ok()
        val closed = flow.first { it.latest != null }
        assertTrue(closed.latest!!.isClosed)
        assertEquals(1, closed.latest.revision)
        assertTrue(closed.review.pending.isEmpty())
        assertEquals(mapOf(pocket to eur(10)), closed.review.recorded)

        expenses.add(eur(4), food.id, LocalDate.of(2026, 3, 6))
        assertEquals(mapOf(pocket to eur(-4)), flow.first { it.review.pending.isNotEmpty() }.review.pending)

        surplus.reopenMonth(ym("2026-03")).ok()
        val reopened = flow.first { it.latest?.isClosed == false }
        assertTrue(reopened.review.recorded.isEmpty())
        assertEquals(mapOf(pocket to eur(6)), reopened.review.pending)
    }
}
