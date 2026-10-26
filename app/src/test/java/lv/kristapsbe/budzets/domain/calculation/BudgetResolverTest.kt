package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetResolverTest {

    private val categories = listOf(CategoryRef(FOOD), CategoryRef(FUN))

    private fun daily(scope: BudgetScope, minor: Long, from: String) =
        BudgetConfig(scope, BudgetTarget(dailyMinor = minor), ym(from))

    @Test
    fun `effective-dated versions - past months keep the old budget`() {
        val configs = listOf(
            daily(BudgetScope.Overall, 2_000, "2026-01"),
            daily(BudgetScope.Overall, 3_000, "2026-03"),
        )
        assertNull(BudgetResolver.resolve(ym("2025-12"), configs, categories).overallBase)
        assertEquals(eur(560), BudgetResolver.resolve(ym("2026-02"), configs, categories).overallBase) // 28 * 20
        assertEquals(eur(930), BudgetResolver.resolve(ym("2026-03"), configs, categories).overallBase) // 31 * 30
        assertEquals(eur(900), BudgetResolver.resolve(ym("2026-06"), configs, categories).overallBase) // 30 * 30
    }

    @Test
    fun `explicit overall budget is not derived`() {
        val configs = listOf(
            daily(BudgetScope.Overall, 3_000, "2026-01"),
            daily(BudgetScope.Category(FOOD), 1_000, "2026-01"),
        )
        val r = BudgetResolver.resolve(ym("2026-04"), configs, categories)
        assertFalse(r.overallDerived)
        assertEquals(eur(900), r.overallBase)
        assertEquals(eur(300), r.categoryPlans[FOOD]!!.amount)
        assertNull(r.categoryPlans[FUN])
    }

    @Test
    fun `no overall budget - overall is derived from category plans`() {
        val configs = listOf(
            daily(BudgetScope.Category(FOOD), 1_000, "2026-01"),
            daily(BudgetScope.Category(FUN), 500, "2026-01"),
        )
        val r = BudgetResolver.resolve(ym("2026-04"), configs, categories)
        assertTrue(r.overallDerived)
        assertEquals(eur(450), r.overallBase)
    }

    @Test
    fun `cleared overall config falls back to derived`() {
        val configs = listOf(
            daily(BudgetScope.Overall, 3_000, "2026-01"),
            BudgetConfig(BudgetScope.Overall, BudgetTarget(), ym("2026-03")),
            daily(BudgetScope.Category(FOOD), 1_000, "2026-01"),
        )
        assertFalse(BudgetResolver.resolve(ym("2026-02"), configs, categories).overallDerived)
        val march = BudgetResolver.resolve(ym("2026-03"), configs, categories)
        assertTrue(march.overallDerived)
        assertEquals(eur(310), march.overallBase)
    }

    @Test
    fun `nothing configured gives null overall`() {
        val r = BudgetResolver.resolve(ym("2026-04"), emptyList(), categories)
        assertNull(r.overallBase)
        assertFalse(r.overallDerived)
    }

    @Test
    fun `archived category drops out of plans from its archive month`() {
        val cats = listOf(CategoryRef(FOOD), CategoryRef(FUN, archivedFrom = ym("2026-05")))
        val configs = listOf(
            daily(BudgetScope.Category(FOOD), 1_000, "2026-01"),
            daily(BudgetScope.Category(FUN), 500, "2026-01"),
        )
        assertEquals(setOf(FOOD, FUN), BudgetResolver.resolve(ym("2026-04"), configs, cats).categoryPlans.keys)
        assertEquals(setOf(FOOD), BudgetResolver.resolve(ym("2026-05"), configs, cats).categoryPlans.keys)
    }
}
