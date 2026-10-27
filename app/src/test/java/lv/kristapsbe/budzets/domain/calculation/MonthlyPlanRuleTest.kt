package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthlyPlanRuleTest {

    @Test
    fun `daily target multiplies by actual month length`() {
        val daily = 1_000L // 10.00
        assertEquals(eur(280), MonthlyPlanRule.dailyEquivalent(daily, ym("2025-02"))) // 28 days
        assertEquals(eur(290), MonthlyPlanRule.dailyEquivalent(daily, ym("2024-02"))) // leap year, 29
        assertEquals(eur(300), MonthlyPlanRule.dailyEquivalent(daily, ym("2026-04"))) // 30
        assertEquals(eur(310), MonthlyPlanRule.dailyEquivalent(daily, ym("2026-01"))) // 31
    }

    @Test
    fun `century leap year rules`() {
        assertEquals(eur(290), MonthlyPlanRule.dailyEquivalent(1_000, ym("2000-02")))
        assertEquals(eur(280), MonthlyPlanRule.dailyEquivalent(1_000, ym("2100-02")))
    }

    @Test
    fun `weekly target extrapolates by days over 7 with half-up rounding`() {
        val weekly = 10_000L // 100.00
        // 28 days: exactly 4 weeks
        assertEquals(eur(400), MonthlyPlanRule.weeklyEquivalent(weekly, ym("2025-02")))
        // 29 days: 2 900 000 / 7 = 41 428.57 -> 41 429 (rounds up)
        assertEquals(Money(41_429), MonthlyPlanRule.weeklyEquivalent(weekly, ym("2024-02")))
        // 30 days: 300 000 / 7 = 42 857.14 -> 42 857 (rounds down)
        assertEquals(Money(42_857), MonthlyPlanRule.weeklyEquivalent(weekly, ym("2026-04")))
        // 31 days: 310 000 / 7 = 44 285.71 -> 44 286
        assertEquals(Money(44_286), MonthlyPlanRule.weeklyEquivalent(weekly, ym("2026-01")))
    }

    @Test
    fun `weekly rounding boundary - remainder 3 rounds down, remainder 4 rounds up`() {
        // 1 minor/week * 31 = 31 = 4*7 + 3 -> 4.43 -> 4
        assertEquals(Money(4), MonthlyPlanRule.weeklyEquivalent(1, ym("2026-01")))
        // 2 minor/week * 30 = 60 = 8*7 + 4 -> 8.57 -> 9
        assertEquals(Money(9), MonthlyPlanRule.weeklyEquivalent(2, ym("2026-04")))
    }

    @Test
    fun `both targets - higher monthly equivalent wins and is not added`() {
        val month = ym("2026-01") // 31 days
        // daily 10.00 -> 310.00 ; weekly 100.00 -> 442.86
        val plan = MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 1_000, weeklyMinor = 10_000), month)!!
        assertEquals(Money(44_286), plan.amount)
        assertEquals(setOf(TargetKind.WEEKLY), plan.determinedBy)
        assertEquals(eur(310), plan.dailyEquivalent)

        val dailyWins = MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 2_000, weeklyMinor = 10_000), month)!!
        assertEquals(eur(620), dailyWins.amount)
        assertEquals(setOf(TargetKind.DAILY), dailyWins.determinedBy)

        val equal = MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 1_000, weeklyMinor = 7_000), ym("2025-02"))!!
        assertEquals(eur(280), equal.amount)
        assertEquals(setOf(TargetKind.DAILY, TargetKind.WEEKLY), equal.determinedBy)
    }

    @Test
    fun `monthly target is used as is and competes on equal terms`() {
        val month = ym("2026-02") // 28 days
        val monthlyOnly = MonthlyPlanRule.plan(BudgetTarget(monthlyMinor = 30_000), month)!!
        assertEquals(eur(300), monthlyOnly.amount)
        assertEquals(eur(300), monthlyOnly.monthlyEquivalent)
        assertEquals(setOf(TargetKind.MONTHLY), monthlyOnly.determinedBy)
        // Same amount in every month length, unlike daily/weekly.
        assertEquals(eur(300), MonthlyPlanRule.plan(BudgetTarget(monthlyMinor = 30_000), ym("2026-01"))!!.amount)

        // daily 10.00 -> 280.00, weekly 100.00 -> 400.00, monthly 300.00: weekly is highest.
        val all = MonthlyPlanRule.plan(BudgetTarget(1_000, 10_000, 30_000), month)!!
        assertEquals(eur(400), all.amount)
        assertEquals(setOf(TargetKind.WEEKLY), all.determinedBy)
        assertEquals(eur(280), all.dailyEquivalent)

        // In a 31-day month the daily equivalent (310.00) beats the monthly 300.00.
        assertEquals(setOf(TargetKind.DAILY), MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 1_000, monthlyMinor = 30_000), ym("2026-01"))!!.determinedBy)

        val tie = MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 1_000, weeklyMinor = 7_000, monthlyMinor = 28_000), month)!!
        assertEquals(TargetKind.entries.toSet(), tie.determinedBy)
    }

    @Test
    fun `missing targets give no plan, zero targets give zero plan`() {
        assertNull(MonthlyPlanRule.plan(BudgetTarget(), ym("2026-01")))
        assertEquals(Money.ZERO, MonthlyPlanRule.plan(BudgetTarget(dailyMinor = 0), ym("2026-01"))!!.amount)
        assertEquals(Money.ZERO, MonthlyPlanRule.plan(BudgetTarget(weeklyMinor = 0), ym("2026-01"))!!.amount)
        assertEquals(Money.ZERO, MonthlyPlanRule.plan(BudgetTarget(monthlyMinor = 0), ym("2026-01"))!!.amount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative target rejected`() {
        BudgetTarget(dailyMinor = -1)
    }

    @Test(expected = ArithmeticException::class)
    fun `overflow throws instead of wrapping`() {
        MonthlyPlanRule.dailyEquivalent(Long.MAX_VALUE / 2, ym("2026-01"))
    }
}
