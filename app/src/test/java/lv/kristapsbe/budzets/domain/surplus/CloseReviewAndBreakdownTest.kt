package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.FIXED_NOW
import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FOOD_POCKET
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.FUN_POCKET
import lv.kristapsbe.budzets.domain.SequentialIds
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseReviewAndBreakdownTest {

    private val ids = SequentialIds()
    private val finalizer = MonthFinalizer(ids::next) { FIXED_NOW }
    private val ops = SurplusOperations(ids::next) { FIXED_NOW }
    private val march = ym("2026-03")

    private fun summary(foodSpent: Long, funSpent: Long) = MonthSummary(
        march, eur(1000), false,
        mapOf(
            FOOD to CategorySummary(FOOD, eur(500), Money.ZERO, eur(foodSpent)),
            // No budget: its spending can only become a debit.
            FUN to CategorySummary(FUN, null, Money.ZERO, eur(funSpent)),
        ),
    )

    @Test
    fun `review shows what closing will post, then only the difference after changes`() {
        val first = finalizer.review(emptyList(), summary(400, 30))
        assertEquals(mapOf(FOOD_POCKET to eur(100), FUN_POCKET to eur(-30), SurplusPocket.Unallocated to eur(500)), first.target)
        assertTrue(first.recorded.isEmpty())
        assertEquals(first.target, first.pending)

        val ledger = finalizer.close(emptyList(), null, summary(400, 30), ym("2026-04")).ok().entries
        val unchanged = finalizer.review(ledger, summary(400, 30))
        assertEquals(first.target, unchanged.recorded)
        assertTrue(unchanged.pending.isEmpty())

        // 20 more spent on food and 5 more on fun after closing.
        val changed = finalizer.review(ledger, summary(420, 35))
        assertEquals(mapOf(FOOD_POCKET to eur(-20), FUN_POCKET to eur(-5)), changed.pending)
    }

    @Test
    fun `breakdown separates closing, moves and boosts and sums to the balance`() {
        var ledger = finalizer.close(emptyList(), null, summary(400, 30), ym("2026-04")).ok().entries
        ledger = ledger + ops.transfer(ledger, FOOD_POCKET, FUN_POCKET, eur(40)).ok()
        val boost = ops.allocateBoost(ledger, FOOD_POCKET, FUN, ym("2026-05"), eur(25), ym("2026-04"), emptySet()).ok()
        ledger = ledger + boost.entry
        val release = ops.allocateBoost(ledger, FOOD_POCKET, FUN, ym("2026-06"), eur(10), ym("2026-04"), emptySet()).ok()
        ledger = ledger + release.entry + ops.releaseBoost(release.boost, emptySet()).ok().entry

        val food = SurplusLedger.breakdown(ledger).getValue(FOOD_POCKET)
        assertEquals(PocketBreakdown(fromClosing = eur(100), moved = eur(-40), boosts = eur(-25)), food)
        assertEquals(eur(35), food.available)
        assertEquals(SurplusLedger.balance(ledger, FOOD_POCKET), food.available)

        val fun_ = SurplusLedger.breakdown(ledger).getValue(FUN_POCKET)
        assertEquals(PocketBreakdown(fromClosing = eur(-30), moved = eur(40)), fun_)
        assertEquals(eur(10), fun_.available)
    }

    @Test
    fun `unclosed budgeted past months, newest first, skipping unbudgeted and closed ones`() {
        val categories = listOf(CategoryRef(FOOD))
        val configs = listOf(
            BudgetConfig(BudgetScope.Category(FOOD), BudgetTarget(dailyMinor = 100), ym("2026-01")),
            BudgetConfig(BudgetScope.Category(FOOD), BudgetTarget(), ym("2026-03")), // no budget in March
            BudgetConfig(BudgetScope.Category(FOOD), BudgetTarget(dailyMinor = 100), ym("2026-04")),
        )
        assertEquals(
            listOf(ym("2026-06"), ym("2026-04"), ym("2026-01")),
            ClosingSchedule.unclosedBudgetedPastMonths(configs, categories, setOf(ym("2026-05"), ym("2026-02")), ym("2026-07")),
        )
        // The current month is never "unclosed past".
        assertTrue(ClosingSchedule.unclosedBudgetedPastMonths(configs, categories, emptySet(), ym("2026-01")).isEmpty())
        assertTrue(ClosingSchedule.unclosedBudgetedPastMonths(emptyList(), categories, emptySet(), ym("2026-07")).isEmpty())
    }
}
