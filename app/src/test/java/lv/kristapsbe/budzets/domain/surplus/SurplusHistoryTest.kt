package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.model.sumMoney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SurplusHistoryTest {

    private val march = ym("2026-03")
    private val april = ym("2026-04")
    private val may = ym("2026-05")

    private var n = 0

    private fun entry(
        type: LedgerEntryType,
        amount: Money,
        pocket: SurplusPocket = SurplusPocket.Unallocated,
        source: YearMonth? = null,
        target: YearMonth? = null,
    ) = LedgerEntry(LedgerEntryId("e${++n}"), type, pocket, amount, FIXED_NOW, sourceMonth = source, targetMonth = target)

    private fun summary(month: YearMonth, overall: Money?, vararg categories: Category) =
        MonthSummary(month, overall, false, categories.associate { it.id to CategorySummary(it.id, it.base, it.boost, it.spent) })

    private data class Category(val id: CategoryId, val base: Money?, val spent: Money, val boost: Money = Money.ZERO)

    @Test
    fun `a closed month gives what its closes posted, re-closes included`() {
        // The summary would now give €500; the closes posted €100 - €30 - €10.
        val march = summary(march, eur(1_000), Category(FOOD, eur(400), eur(500)))
        val ledger = listOf(
            entry(LedgerEntryType.FINALIZE_CREDIT, eur(100), source = this.march),
            entry(LedgerEntryType.FINALIZE_DEBIT, eur(30), FOOD_POCKET, source = this.march),
            entry(LedgerEntryType.ADJUSTMENT_DEBIT, eur(10), FOOD_POCKET, source = this.march),
        )
        val month = SurplusHistory.months(listOf(march), ledger, setOf(this.march), may).single()
        assertTrue(month.isClosed)
        assertEquals(eur(60), month.result)
        assertEquals(eur(60), month.runningTotal)
    }

    @Test
    fun `an open month with a budget gives what closing it now would post`() {
        // Food €50 over, Fun has no budget (€20 counts against it), €200 of the overall unassigned.
        val march = summary(march, eur(500), Category(FOOD, eur(300), eur(350)), Category(FUN, null, eur(20)))
        val month = SurplusHistory.months(listOf(march), emptyList(), emptySet(), may).single()
        assertFalse(month.isClosed)
        assertEquals(closingEffect(march).values.sumMoney(), month.result)
        assertEquals(eur(130), month.result)
    }

    @Test
    fun `a reopened month goes back to what closing it would post`() {
        val march = summary(march, eur(500), Category(FOOD, eur(300), eur(350)))
        val ledger = listOf(
            entry(LedgerEntryType.FINALIZE_CREDIT, eur(150), source = this.march),
            entry(LedgerEntryType.ADJUSTMENT_DEBIT, eur(150), source = this.march),
        )
        val month = SurplusHistory.months(listOf(march), ledger, emptySet(), may).single()
        assertFalse(month.isClosed)
        assertEquals(eur(150), month.result) // 500 - 350
    }

    @Test
    fun `a boost nets out, so the month is measured against its base budget`() {
        // €500 overall, Food €300 + €50 boost from its own pocket, €320 spent.
        val april = summary(april, eur(500), Category(FOOD, eur(300), eur(320), boost = eur(50)))
        val ledger = listOf(entry(LedgerEntryType.BOOST_RESERVE, eur(50), FOOD_POCKET, target = this.april))
        val month = SurplusHistory.months(listOf(april), ledger, emptySet(), may).single()
        assertEquals(eur(180), month.result) // 500 - 320
        // Split for the breakdown: closing would post €230, the boost took €50.
        assertEquals(eur(230), month.closing)
        assertEquals(eur(-50), month.boosts)
    }

    @Test
    fun `a one-off boost adds to its month`() {
        val april = summary(april, eur(500), Category(FOOD, eur(500), eur(450)))
        val ledger = listOf(entry(LedgerEntryType.ONE_OFF_CREDIT, eur(40), target = this.april))
        val month = SurplusHistory.months(listOf(april), ledger, emptySet(), may).single()
        assertEquals(eur(90), month.result)
        assertEquals(eur(40), month.oneOffs)
        assertEquals(eur(50), month.closing)
    }

    @Test
    fun `an open month with no budget gives nothing, and the total carries over it`() {
        val months = SurplusHistory.months(
            listOf(
                summary(march, eur(500), Category(FOOD, eur(500), eur(400))),
                summary(april, null, Category(FOOD, null, eur(300))),
            ),
            emptyList(),
            emptySet(),
            may,
        )
        assertNull(months[1].result)
        assertEquals(eur(100), months[1].runningTotal)
    }

    @Test
    fun `the month under way is left out until it's closed`() {
        val open = SurplusHistory.months(listOf(summary(may, eur(500), Category(FOOD, eur(500), eur(10)))), emptyList(), emptySet(), may).single()
        assertNull(open.result)
        assertNull(open.runningTotal)

        val ledger = listOf(entry(LedgerEntryType.FINALIZE_CREDIT, eur(490), FOOD_POCKET, source = may))
        val closed = SurplusHistory.months(listOf(summary(may, eur(500), Category(FOOD, eur(500), eur(10)))), ledger, setOf(may), may).single()
        assertEquals(eur(490), closed.result)
    }

    @Test
    fun `months before the chart start the running total`() {
        val ledger = listOf(
            entry(LedgerEntryType.FINALIZE_CREDIT, eur(70), source = ym("2026-01")),
            entry(LedgerEntryType.ONE_OFF_CREDIT, eur(30), target = ym("2026-02")),
        )
        val month = SurplusHistory.months(listOf(summary(march, null)), ledger, setOf(ym("2026-01")), may).single()
        assertNull(month.result)
        assertEquals(eur(100), month.runningTotal)
    }

    @Test
    fun `with every month closed, the total ends at the saved surplus`() {
        val finalizer = MonthFinalizer({ "c${++n}" }, { FIXED_NOW })
        val summaries = listOf(
            summary(march, eur(500), Category(FOOD, eur(300), eur(280)), Category(FUN, eur(100), eur(160))),
            summary(april, eur(500), Category(FOOD, eur(300), eur(390), boost = eur(40)), Category(FUN, eur(100), eur(20))),
        )
        val ledger = mutableListOf<LedgerEntry>()
        ledger += finalizer.close(ledger, null, summaries[0], may).ok().entries
        // Moving money between pockets doesn't change the total.
        ledger += entry(LedgerEntryType.TRANSFER_OUT, eur(20), FOOD_POCKET)
        ledger += entry(LedgerEntryType.TRANSFER_IN, eur(20), FUN_POCKET)
        ledger += entry(LedgerEntryType.BOOST_RESERVE, eur(40), SurplusPocket.Unallocated, target = april)
        ledger += entry(LedgerEntryType.ONE_OFF_CREDIT, eur(15), target = april)
        ledger += finalizer.close(ledger, null, summaries[1], may).ok().entries

        val months = SurplusHistory.months(summaries, ledger, setOf(march, april), may)

        assertEquals(SurplusLedger.balances(ledger).values.sumMoney(), months.last().runningTotal)
        assertEquals(eur(60), months[0].result) // 500 - 440
        assertEquals(eur(105), months[1].result) // 500 - 410, plus the €15 one-off
        assertEquals(eur(165), months[1].runningTotal)
    }

    @Test
    fun `no months, nothing to show`() {
        assertTrue(SurplusHistory.months(emptyList(), emptyList(), emptySet(), may).isEmpty())
    }
}
