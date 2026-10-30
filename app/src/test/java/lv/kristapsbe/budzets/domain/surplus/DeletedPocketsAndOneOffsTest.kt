package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.SequentialIds
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummaryCalculator
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DeletedPocketsAndOneOffsTest {

    private val march = ym("2026-03")
    private val april = ym("2026-04")
    private val operations = SequentialIds().let { ids -> SurplusOperations(ids::next) { FIXED_NOW } }

    private fun entry(id: String, type: LedgerEntryType, pocket: SurplusPocket, amount: Money, month: java.time.YearMonth? = null) =
        LedgerEntry(LedgerEntryId(id), type, pocket, amount, FIXED_NOW, sourceMonth = month)

    @Test
    fun `a deleted category's pocket counts as Unallocated, debts included`() {
        val ledger = listOf(
            entry("1", LedgerEntryType.FINALIZE_CREDIT, FOOD_POCKET, eur(100), march),
            entry("2", LedgerEntryType.FINALIZE_DEBIT, FUN_POCKET, eur(30), march),
            entry("3", LedgerEntryType.FINALIZE_CREDIT, SurplusPocket.Unallocated, eur(5), march),
        )
        val merged = SurplusLedger.balances(SurplusLedger.merged(ledger, setOf(FUN)))
        assertEquals(mapOf(FOOD_POCKET to eur(100), SurplusPocket.Unallocated to eur(-25)), merged)
    }

    @Test
    fun `effectivePocket leaves everything else alone, and merged keeps the list when nothing is deleted`() {
        assertEquals(FOOD_POCKET, SurplusLedger.effectivePocket(FOOD_POCKET, setOf(FUN)))
        assertEquals(SurplusPocket.Unallocated, SurplusLedger.effectivePocket(SurplusPocket.Unallocated, setOf(FUN)))
        assertEquals(SurplusPocket.Unallocated, SurplusLedger.effectivePocket(FUN_POCKET, setOf(FUN)))
        val ledger = listOf(entry("1", LedgerEntryType.FINALIZE_CREDIT, FOOD_POCKET, eur(1), march))
        assertSame(ledger, SurplusLedger.merged(ledger, emptySet()))
    }

    @Test
    fun `reopen checks merged balances but reverses the pockets that were posted to`() {
        val finalizer = SequentialIds().let { ids -> MonthFinalizer(ids::next) { FIXED_NOW } }
        val ledger = listOf(entry("1", LedgerEntryType.FINALIZE_CREDIT, FUN_POCKET, eur(60), march))
        val closure = MonthClosure(march, 1, FIXED_NOW, MonthSummary(march, null, false, emptyMap()))

        val result = finalizer.reopen(ledger, closure, deleted = setOf(FUN)).ok()
        assertEquals(FUN_POCKET, result.entries.single().pocket)
        assertEquals(LedgerEntryType.ADJUSTMENT_DEBIT, result.entries.single().type)
    }

    @Test
    fun `one-offs count in a pocket's total, and removed ones aren't active`() {
        val kept = operations.addOneOff(april, eur(200), emptySet()).ok()
        val gone = operations.addOneOff(april, eur(50), emptySet()).ok()
        val removal = operations.removeOneOff(listOf(kept, gone), gone, emptySet()).ok()
        val ledger = listOf(kept, gone, removal)

        assertEquals(listOf(kept), SurplusLedger.activeOneOffs(ledger))
        assertEquals(gone.id, removal.relatedEntryId)
        assertEquals(april, removal.targetMonth)
        val breakdown = SurplusLedger.breakdown(ledger).getValue(SurplusPocket.Unallocated)
        assertEquals(PocketBreakdown(oneOff = eur(200)), breakdown)
        assertEquals(eur(200), breakdown.available)
    }

    @Test
    fun `the first open month skips closed ones`() {
        assertEquals(april, ClosingSchedule.firstOpenMonth(april, emptySet()))
        assertEquals(april, ClosingSchedule.firstOpenMonth(april, setOf(march)))
        assertEquals(ym("2026-06"), ClosingSchedule.firstOpenMonth(april, setOf(april, ym("2026-05"))))
    }

    @Test
    fun `a closed summary takes budgets from the snapshot, and boosts and spending from now`() {
        val snapshot = MonthSummary(
            march, eur(1_000), false,
            mapOf(FOOD to CategorySummary(FOOD, eur(400), eur(50), eur(150))),
        )
        val boost = Boost(BoostId("b"), march, FOOD, SurplusPocket.Unallocated, eur(70), BoostStatus.ACTIVE, LedgerEntryId("r"), FIXED_NOW)
        val released = boost.copy(id = BoostId("c"), status = BoostStatus.RELEASED)

        val summary = MonthSummaryCalculator.summarizeClosed(snapshot, listOf(boost, released), mapOf(FUN to eur(9)))

        assertEquals(eur(1_000), summary.overallBase)
        assertEquals(CategorySummary(FOOD, eur(400), eur(70), Money.ZERO), summary.categories.getValue(FOOD))
        val unbudgeted = summary.categories.getValue(FUN)
        assertNull(unbudgeted.base)
        assertEquals(eur(9), unbudgeted.spent)
    }
}
