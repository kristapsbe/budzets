package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.SequentialIds
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.TRAVEL_POCKET
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Test

class SurplusOperationsTest {

    private val ids = SequentialIds()
    private val ops = SurplusOperations(ids::next) { FIXED_NOW }
    private val current = ym("2026-04")

    private fun credit(pocket: SurplusPocket, amount: Money, month: String = "2026-03") = LedgerEntry(
        LedgerEntryId(ids.next()), LedgerEntryType.FINALIZE_CREDIT, pocket, amount, FIXED_NOW, sourceMonth = ym(month),
    )

    private val seeded = listOf(credit(FOOD_POCKET, eur(100)), credit(SurplusPocket.Unallocated, eur(30)))

    @Test
    fun `partial transfer moves exact amount atomically as two linked legs`() {
        val legs = ops.transfer(seeded, FOOD_POCKET, TRAVEL_POCKET, eur(40), note = "trip").ok()
        assertEquals(2, legs.size)
        val (out, `in`) = legs
        assertEquals(LedgerEntryType.TRANSFER_OUT, out.type)
        assertEquals(LedgerEntryType.TRANSFER_IN, `in`.type)
        assertEquals(`in`.id, out.relatedEntryId)
        assertEquals(out.id, `in`.relatedEntryId)

        val after = seeded + legs
        assertEquals(eur(60), SurplusLedger.balance(after, FOOD_POCKET))
        assertEquals(eur(40), SurplusLedger.balance(after, TRAVEL_POCKET))
        // total surplus unchanged
        assertEquals(
            SurplusLedger.balances(seeded).values.sumOf { it.minor },
            SurplusLedger.balances(after).values.sumOf { it.minor },
        )
    }

    @Test
    fun `full transfer empties the source`() {
        val after = seeded + ops.transfer(seeded, FOOD_POCKET, FUN_POCKET, eur(100)).ok()
        assertEquals(Money.ZERO, SurplusLedger.balance(after, FOOD_POCKET))
        assertEquals(eur(100), SurplusLedger.balance(after, FUN_POCKET))
    }

    @Test
    fun `unallocated surplus can be moved into a category`() {
        val after = seeded + ops.transfer(seeded, SurplusPocket.Unallocated, FUN_POCKET, eur(30)).ok()
        assertEquals(Money.ZERO, SurplusLedger.balance(after, SurplusPocket.Unallocated))
    }

    @Test
    fun `transfer validation`() {
        assertEquals(SurplusError.NotPositive, ops.transfer(seeded, FOOD_POCKET, FUN_POCKET, Money.ZERO).err())
        assertEquals(SurplusError.SamePocket, ops.transfer(seeded, FOOD_POCKET, FOOD_POCKET, eur(1)).err())
        assertEquals(
            SurplusError.InsufficientSurplus(eur(100)),
            ops.transfer(seeded, FOOD_POCKET, FUN_POCKET, Money(10_001)).err(),
        )
    }

    @Test
    fun `negative pocket cannot send but can receive to pay back`() {
        val debt = listOf(
            LedgerEntry(LedgerEntryId("d"), LedgerEntryType.FINALIZE_DEBIT, FUN_POCKET, eur(50), FIXED_NOW, sourceMonth = ym("2026-03")),
        )
        val ledger = seeded + debt
        assertEquals(SurplusError.InsufficientSurplus(eur(-50)), ops.transfer(ledger, FUN_POCKET, FOOD_POCKET, eur(1)).err())

        val after = ledger + ops.transfer(ledger, FOOD_POCKET, FUN_POCKET, eur(50)).ok()
        assertEquals(Money.ZERO, SurplusLedger.balance(after, FUN_POCKET))
    }

    @Test
    fun `from all surplus takes Unallocated first, then the fullest pockets`() {
        // Unallocated 30, Food 100, Travel 60; Fun owes 150.
        val ledger = seeded + credit(TRAVEL_POCKET, eur(60)) + LedgerEntry(
            LedgerEntryId("d"), LedgerEntryType.FINALIZE_DEBIT, FUN_POCKET, eur(150), FIXED_NOW, sourceMonth = ym("2026-03"),
        )
        val legs = ops.transferFromAll(ledger, FUN_POCKET, eur(150), note = "debt").ok()

        assertEquals(6, legs.size)
        val outs = legs.filter { it.type == LedgerEntryType.TRANSFER_OUT }
        assertEquals(listOf(SurplusPocket.Unallocated, FOOD_POCKET, TRAVEL_POCKET), outs.map { it.pocket })
        assertEquals(listOf(eur(30), eur(100), eur(20)), outs.map { it.amount })
        // Each pair is linked, lands in Fun, and carries the note.
        legs.chunked(2).forEach { (out, `in`) ->
            assertEquals(`in`.id, out.relatedEntryId)
            assertEquals(out.id, `in`.relatedEntryId)
            assertEquals(FUN_POCKET, `in`.pocket)
            assertEquals(out.amount, `in`.amount)
        }
        assertEquals(setOf("debt"), legs.map { it.note }.toSet())

        val after = SurplusLedger.balances(ledger + legs)
        assertEquals(Money.ZERO, after[FUN_POCKET])
        assertEquals(eur(40), after[TRAVEL_POCKET])
        assertEquals(SurplusLedger.balances(ledger).values.sumOf { it.minor }, after.values.sumOf { it.minor })
    }

    @Test
    fun `from all surplus leaves the destination's own surplus alone`() {
        val legs = ops.transferFromAll(seeded, FOOD_POCKET, eur(30)).ok()
        assertEquals(listOf(SurplusPocket.Unallocated, FOOD_POCKET), legs.map { it.pocket })
        assertEquals(
            SurplusError.InsufficientSurplus(eur(30)),
            ops.transferFromAll(seeded, FOOD_POCKET, eur(31)).err(),
        )
    }

    @Test
    fun `from all surplus validation`() {
        assertEquals(SurplusError.NotPositive, ops.transferFromAll(seeded, FUN_POCKET, Money.ZERO).err())
        assertEquals(SurplusError.InsufficientSurplus(eur(130)), ops.transferFromAll(seeded, FUN_POCKET, Money(13_001)).err())
        // Exactly everything is fine.
        assertEquals(4, ops.transferFromAll(seeded, FUN_POCKET, eur(130)).ok().size)
    }

    @Test
    fun `the draw order breaks ties by category, and a pocket in debt never gives`() {
        val balances = mapOf(
            FUN_POCKET to eur(20),
            FOOD_POCKET to eur(20),
            TRAVEL_POCKET to eur(-5),
            SurplusPocket.Unallocated to eur(1),
        )
        val order = SurplusDraw.sources(balances, to = null).map { it.first }
        assertEquals(SurplusPocket.Unallocated, order.first())
        assertEquals(listOf(FOOD_POCKET, FUN_POCKET).sortedBy { it.id.value }, order.drop(1))
        assertEquals(eur(41), SurplusDraw.available(balances, to = null))
        assertEquals(null, SurplusDraw.plan(balances, to = null, amount = eur(42)))
        assertEquals(listOf(SurplusPocket.Unallocated to eur(1)), SurplusDraw.plan(balances, TRAVEL_POCKET, eur(1)))
    }

    @Test
    fun `boost reserves surplus so it cannot be allocated twice`() {
        val first = ops.allocateBoost(seeded, FOOD_POCKET, TRAVEL, current, eur(70), current, emptySet()).ok()
        assertEquals(BoostStatus.ACTIVE, first.boost.status)
        assertEquals(FOOD_POCKET, first.boost.source)
        assertEquals(TRAVEL, first.boost.destination)
        assertEquals(first.entry.id, first.boost.reserveEntryId)

        val ledger = seeded + first.entry
        assertEquals(eur(30), SurplusLedger.balance(ledger, FOOD_POCKET))
        assertEquals(
            SurplusError.InsufficientSurplus(eur(30)),
            ops.allocateBoost(ledger, FOOD_POCKET, TRAVEL, current, eur(70), current, emptySet()).err(),
        )
        // also blocks a transfer of the reserved money
        assertEquals(
            SurplusError.InsufficientSurplus(eur(30)),
            ops.transfer(ledger, FOOD_POCKET, FUN_POCKET, eur(31)).err(),
        )
    }

    @Test
    fun `boost into a past or closed month is refused, future open month allowed`() {
        assertEquals(
            SurplusError.MonthInPast,
            ops.allocateBoost(seeded, FOOD_POCKET, FOOD, ym("2026-03"), eur(1), current, emptySet()).err(),
        )
        assertEquals(
            SurplusError.MonthClosed,
            ops.allocateBoost(seeded, FOOD_POCKET, FOOD, current, eur(1), current, setOf(current)).err(),
        )
        ops.allocateBoost(seeded, FOOD_POCKET, FOOD, ym("2027-01"), eur(1), current, emptySet()).ok()
        assertEquals(
            SurplusError.NotPositive,
            ops.allocateBoost(seeded, FOOD_POCKET, FOOD, current, Money.ZERO, current, emptySet()).err(),
        )
    }

    @Test
    fun `releasing a boost restores availability once`() {
        val alloc = ops.allocateBoost(seeded, FOOD_POCKET, TRAVEL, current, eur(70), current, emptySet()).ok()
        val release = ops.releaseBoost(alloc.boost, emptySet()).ok()
        assertEquals(BoostStatus.RELEASED, release.boost.status)
        assertEquals(FIXED_NOW, release.boost.releasedAt)
        assertEquals(alloc.entry.id, release.entry.relatedEntryId)
        assertEquals(eur(100), SurplusLedger.balance(seeded + alloc.entry + release.entry, FOOD_POCKET))

        assertEquals(SurplusError.AlreadyReleased, ops.releaseBoost(release.boost, emptySet()).err())
        assertEquals(SurplusError.MonthClosed, ops.releaseBoost(alloc.boost, setOf(current)).err())
    }
}
