package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.SequentialIds
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.TRAVEL_POCKET
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MonthFinalizerTest {

    private val ids = SequentialIds()
    private val finalizer = MonthFinalizer(ids::next) { FIXED_NOW }
    private val ops = SurplusOperations(ids::next) { FIXED_NOW }
    private val march = ym("2026-03")
    private val current = ym("2026-04")

    private fun summary(
        month: YearMonth,
        overall: Money?,
        vararg cats: Triple<CategoryId, Money, Money>, // id, base, spent
    ) = MonthSummary(
        month, overall, false,
        cats.associate { (id, base, spent) -> id to CategorySummary(id, base, Money.ZERO, spent) },
    )

    private fun cat(id: CategoryId, base: Long, spent: Long) = Triple(id, eur(base), eur(spent))

    private fun balance(ledger: List<LedgerEntry>, pocket: SurplusPocket) = SurplusLedger.balance(ledger, pocket)

    @Test
    fun `closing credits unused, debits overspend, credits unallocated`() {
        val s = summary(march, eur(1000), cat(FOOD, 500, 400), cat(FUN, 300, 350))
        assertEquals(
            mapOf(FOOD_POCKET to eur(100), FUN_POCKET to eur(-50), SurplusPocket.Unallocated to eur(200)),
            closingEffect(s),
        )
        val result = finalizer.close(emptyList(), null, s, current).ok()
        assertEquals(1, result.closure.revision)
        assertTrue(result.closure.isClosed)
        assertEquals(s, result.closure.snapshot)

        val byPocket = result.entries.associateBy { it.pocket }
        assertEquals(LedgerEntryType.FINALIZE_CREDIT, byPocket.getValue(FOOD_POCKET).type)
        assertEquals(LedgerEntryType.FINALIZE_DEBIT, byPocket.getValue(FUN_POCKET).type)
        assertEquals(eur(50), byPocket.getValue(FUN_POCKET).amount)
        assertTrue(result.entries.all { it.sourceMonth == march })

        assertEquals(eur(-50), balance(result.entries, FUN_POCKET))
        assertEquals(eur(200), balance(result.entries, SurplusPocket.Unallocated))
    }

    @Test
    fun `category exactly on budget generates nothing`() {
        val s = summary(march, eur(500), cat(FOOD, 500, 500))
        assertTrue(finalizer.close(emptyList(), null, s, current).ok().entries.isEmpty())
    }

    @Test
    fun `overspend debt is repaid by a later month's surplus`() {
        val m1 = finalizer.close(emptyList(), null, summary(march, eur(300), cat(FUN, 300, 380)), current).ok()
        val ledger1 = m1.entries
        assertEquals(eur(-80), balance(ledger1, FUN_POCKET))

        val m2 = finalizer.close(ledger1, null, summary(current, eur(300), cat(FUN, 300, 200)), current).ok()
        assertEquals(eur(20), balance(ledger1 + m2.entries, FUN_POCKET))
    }

    @Test
    fun `month cannot be closed twice without changes`() {
        val s = summary(march, eur(1000), cat(FOOD, 500, 400))
        val first = finalizer.close(emptyList(), null, s, current).ok()
        assertEquals(ClosureError.NothingToChange, finalizer.close(first.entries, first.closure, s, current).err())
        assertFalse(finalizer.hasChangesSinceClosing(first.entries, s))
    }

    @Test
    fun `re-closing after an expense change posts only the delta`() {
        val before = summary(march, eur(1000), cat(FOOD, 500, 400), cat(FUN, 300, 300))
        val first = finalizer.close(emptyList(), null, before, current).ok()

        // A 30.00 expense moved from FOOD to FUN in the closed month.
        val after = summary(march, eur(1000), cat(FOOD, 500, 370), cat(FUN, 300, 330))
        assertTrue(finalizer.hasChangesSinceClosing(first.entries, after))

        val second = finalizer.close(first.entries, first.closure, after, current).ok()
        assertEquals(2, second.closure.revision)
        assertEquals(2, second.entries.size)
        val byPocket = second.entries.associateBy { it.pocket }
        assertEquals(LedgerEntryType.ADJUSTMENT_CREDIT, byPocket.getValue(FOOD_POCKET).type)
        assertEquals(eur(30), byPocket.getValue(FOOD_POCKET).amount)
        assertEquals(LedgerEntryType.ADJUSTMENT_DEBIT, byPocket.getValue(FUN_POCKET).type)
        assertEquals(eur(30), byPocket.getValue(FUN_POCKET).amount)

        val ledger = first.entries + second.entries
        assertEquals(closingEffect(after), SurplusLedger.closureNet(ledger, march))
        assertEquals(eur(130), balance(ledger, FOOD_POCKET))
        assertEquals(eur(-30), balance(ledger, FUN_POCKET))
    }

    @Test
    fun `unspent boost returns as surplus of the destination category`() {
        // Earlier surplus in FOOD funds a TRAVEL boost for April.
        val m1 = finalizer.close(emptyList(), null, summary(march, eur(500), cat(FOOD, 500, 300)), current).ok()
        val alloc = ops.allocateBoost(m1.entries, FOOD_POCKET, TRAVEL, current, eur(150), current, emptySet()).ok()
        var ledger = m1.entries + alloc.entry
        assertEquals(eur(50), balance(ledger, FOOD_POCKET))

        // April: TRAVEL has no base budget, only the boost; spent 120 of it.
        val april = MonthSummary(
            current, eur(500), false,
            mapOf(
                FOOD to CategorySummary(FOOD, eur(500), Money.ZERO, eur(500)),
                TRAVEL to CategorySummary(TRAVEL, null, eur(150), eur(120)),
            ),
        )
        ledger = ledger + finalizer.close(ledger, null, april, current).ok().entries
        assertEquals(eur(30), balance(ledger, TRAVEL_POCKET))
        assertEquals(eur(50), balance(ledger, FOOD_POCKET))
        // No unallocated surplus: the boost doesn't count as unassigned overall base.
        assertEquals(Money.ZERO, balance(ledger, SurplusPocket.Unallocated))
    }

    @Test
    fun `cannot close a future month, can close current month`() {
        val future = summary(ym("2026-05"), eur(10), cat(FOOD, 10, 0))
        assertEquals(ClosureError.MonthInFuture, finalizer.close(emptyList(), null, future, current).err())
        finalizer.close(emptyList(), null, summary(current, eur(10), cat(FOOD, 10, 0)), current).ok()
    }

    @Test
    fun `year boundary - December closes and January boost can use it`() {
        val dec = ym("2025-12")
        val jan = ym("2026-01")
        val closed = finalizer.close(emptyList(), null, summary(dec, eur(400), cat(FOOD, 400, 250)), jan).ok()
        assertEquals(dec, closed.closure.month)
        val alloc = ops.allocateBoost(closed.entries, FOOD_POCKET, FUN, jan, eur(150), jan, setOf(dec)).ok()
        assertEquals(Money.ZERO, balance(closed.entries + alloc.entry, FOOD_POCKET))
    }

    @Test
    fun `reopen reverses the month's net effect and allows closing again`() {
        val s = summary(march, eur(1000), cat(FOOD, 500, 400), cat(FUN, 300, 350))
        val first = finalizer.close(emptyList(), null, s, current).ok()
        val reopened = finalizer.reopen(first.entries, first.closure).ok()
        assertFalse(reopened.closure.isClosed)

        val ledger = first.entries + reopened.entries
        assertTrue(SurplusLedger.closureNet(ledger, march).isEmpty())
        assertEquals(Money.ZERO, balance(ledger, FOOD_POCKET))
        assertEquals(Money.ZERO, balance(ledger, FUN_POCKET))
        assertEquals(ClosureError.NotClosed, finalizer.reopen(ledger, reopened.closure).err())

        val again = finalizer.close(ledger, reopened.closure, s, current).ok()
        assertEquals(2, again.closure.revision)
        assertTrue(again.entries.all { it.type == LedgerEntryType.ADJUSTMENT_CREDIT || it.type == LedgerEntryType.ADJUSTMENT_DEBIT })
        assertEquals(eur(100), balance(ledger + again.entries, FOOD_POCKET))
    }

    @Test
    fun `reopen refused when the month's surplus was already moved away`() {
        val first = finalizer.close(emptyList(), null, summary(march, eur(500), cat(FOOD, 500, 400)), current).ok()
        val moved = first.entries + ops.transfer(first.entries, FOOD_POCKET, TRAVEL_POCKET, eur(60)).ok()

        val error = finalizer.reopen(moved, first.closure).err()
        assertEquals(ClosureError.WouldGoNegative(mapOf(FOOD_POCKET to eur(-60))), error)
    }
}
