package lv.kristapsbe.budzets.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import lv.kristapsbe.budzets.domain.calculation.BurnDown
import lv.kristapsbe.budzets.domain.calculation.BurnDownCalculator
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.forecast.Estimate
import lv.kristapsbe.budzets.domain.forecast.ForecastBasis
import lv.kristapsbe.budzets.domain.forecast.ForecastSegment
import lv.kristapsbe.budzets.domain.forecast.ForecastStack
import lv.kristapsbe.budzets.domain.forecast.MonthEndSurplus
import lv.kristapsbe.budzets.domain.forecast.SurplusProjection
import lv.kristapsbe.budzets.domain.calculation.StackSegment
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpendingHistoryContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")
    private val march = ym("2026-03")
    private val april = ym("2026-04")

    /** Bottom to top, each segment on the one before. */
    private fun stack(month: YearMonth, budget: Money?, vararg parts: Pair<CategoryId?, Money>): MonthStack {
        var below = Money.ZERO
        return MonthStack(month, parts.map { (id, amount) -> StackSegment(id, amount, below).also { below += amount } }, budget)
    }

    private val series = listOf(
        ChartSeries(food, "Food", isArchived = false, isDeleted = false, slot = 0),
        ChartSeries(fun_, "Fun", isArchived = false, isDeleted = true, slot = 1),
    )

    private fun ready(
        months: List<MonthStack> = listOf(
            stack(march, eur(500), fun_ to eur(40), food to eur(300)),
            stack(april, null, food to eur(20), fun_ to eur(90)),
        ),
        selected: YearMonth? = months.last().month,
        highlighted: String? = null,
        series: List<ChartSeries> = this.series,
        // No surplus results unless a test gives some.
        surplus: List<MonthSurplus> = months.map { MonthSurplus(it.month, null, isClosed = false, runningTotal = Money.ZERO) },
        burnDown: BurnDown? = null,
    ) = SpendingHistoryUiState.Ready(CurrencySpec.DEFAULT, months, surplus, series, april, selected, highlighted, burnDown)

    /** A month of [budget] with [spent] on the 3rd and a [boost] added on the 10th, seen on 15 April. */
    private fun burnDown(
        month: YearMonth = april,
        budget: Money = eur(500),
        spent: Money = eur(110),
        boost: Money = Money.ZERO,
        debt: Money = Money.ZERO,
    ): BurnDown {
        val zone = ZoneId.of("Europe/Riga")
        val summary = MonthSummary(month, budget, false, mapOf(food to CategorySummary(food, budget, boost, spent)))
            .let { if (debt.isPositive) it.carrying(mapOf(SurplusPocket.Category(fun_) to debt)) else it }
        val boosts = listOfNotNull(
            if (boost.isPositive) Boost(BoostId("b"), month, food, SurplusPocket.Unallocated, boost, BoostStatus.ACTIVE, LedgerEntryId("e"), month.atDay(10).atStartOfDay(zone).plusHours(12).toInstant()) else null,
        )
        return BurnDownCalculator.of(summary, boosts, mapOf(month.atDay(3) to spent), LocalDate.of(2026, 4, 15), zone)!!
    }

    /** March closed with +€160, February open at -€30, April under way with €400 still unspent. */
    private val withSurplus = listOf(
        MonthSurplus(ym("2026-02"), eur(-30), isClosed = false, runningTotal = eur(-30)),
        MonthSurplus(march, eur(160), isClosed = true, runningTotal = eur(130)),
        MonthSurplus(april, eur(400), isClosed = false, runningTotal = eur(530)),
    )
    private val threeMonths = listOf(
        stack(ym("2026-02"), eur(500), food to eur(530)),
        stack(march, eur(500), fun_ to eur(40), food to eur(300)),
        stack(april, null, food to eur(20), fun_ to eur(90)),
    )

    private fun show(state: SpendingHistoryUiState, actions: SpendingHistoryActions = SpendingHistoryActions()) {
        compose.setContent { BudzetsTheme { SpendingHistoryContent(state, actions) } }
    }

    @Test
    fun `without spending it says so, and the surplus chart is still there`() {
        val surplus = listOf(MonthSurplus(april, eur(500), isClosed = false, runningTotal = eur(500)))
        show(ready(months = listOf(stack(april, eur(500))), series = emptyList(), surplus = surplus))
        compose.onNodeWithText("No spending logged yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Surplus total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "April 2026 (so far): Not closed yet: closing it now would add €500.00. Surplus after this month: €500.00",
        ).assertIsSelected()
    }

    @Test
    fun `the burn-down describes the month under way, and the key explains its marks`() {
        show(ready(burnDown = burnDown(boost = eur(50))))
        compose.onNodeWithText("Budget left").assertIsDisplayed()
        // €550 over 30 days: €275 spent evenly by the 15th leaves €275.
        compose.onNodeWithContentDescription(
            "April 2026 (so far): Started with €500.00 · Boosts +€50.00. €440.00 left. At an even pace, €275.00 would be left by today",
        ).assertIsDisplayed()
        compose.onNodeWithText("Started with €500.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Boosts +€50.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€440.00 left").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("At an even pace, €275.00 would be left by today").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Left to spend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pace guide").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Boost added").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Over budget").assertDoesNotExist()
    }

    @Test
    fun `without a boost there is no boost key, and debt is named in the start`() {
        show(ready(burnDown = burnDown(debt = eur(40))))
        compose.onNodeWithText("Started with €460.00, after €40.00 of debt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€350.00 left").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Boost added").assertDoesNotExist()
    }

    @Test
    fun `a past month over budget says so in words, with no guide line text`() {
        show(ready(selected = march, burnDown = burnDown(month = march, budget = eur(300), spent = eur(340))))
        compose.onNodeWithContentDescription("March 2026: Started with €300.00. Over by €40.00").assertIsDisplayed()
        compose.onNodeWithText("Over by €40.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Over budget").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("At an even pace", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a month without a budget has no burn-down, and says why`() {
        show(ready())
        compose.onNodeWithText("Budget left").assertIsDisplayed()
        compose.onNodeWithText("No budget set").assertIsDisplayed()
        compose.onNodeWithText("Left to spend").assertDoesNotExist()
    }

    @Test
    fun `selecting a month without a budget doesn't pull the charts below up`() {
        var state by mutableStateOf(ready(burnDown = burnDown(boost = eur(50))))
        compose.setContent { BudzetsTheme { SpendingHistoryContent(state, SpendingHistoryActions()) } }
        val before = compose.onNodeWithText("Spending").fetchSemanticsNode().positionInRoot.y

        state = ready(selected = march)
        compose.onNodeWithText("No budget set").assertIsDisplayed()
        val after = compose.onNodeWithText("Spending").fetchSemanticsNode().positionInRoot.y
        assertEquals(before, after)
    }

    /** The whole forecast for [months], Food at €100 (€80 to €120) and Fun at €40 every month. */
    private fun chartForecast(months: List<YearMonth> = (5..16).map { april.plusMonths(it - 4L) }): ChartForecast {
        val foodEstimate = Estimate(eur(100), eur(80), eur(120))
        val funEstimate = Estimate(eur(40), eur(30), eur(50))
        val stacks = months.map { month ->
            ForecastStack(
                month,
                listOf(ForecastSegment(fun_, funEstimate, Money.ZERO), ForecastSegment(food, foodEstimate, eur(40))),
                Estimate(eur(140), eur(115), eur(165)),
                budget = eur(500),
            )
        }
        var total = eur(530)
        val surplus = months.map { SurplusProjection(it, Estimate(eur(360), eur(335), eur(385)), (total + eur(360)).also { t -> total = t }) }
        return ChartForecast(ForecastBasis.TREND, stacks, currentEnd = Estimate(eur(150), eur(120), eur(180)), surplus = surplus, monthEnd = MonthEndSurplus(Estimate(eur(350), eur(320), eur(380)), eur(480)))
    }

    @Test
    fun `a forecast month is described in words and listed with its range`() {
        val state = ready(months = threeMonths, surplus = withSurplus, selected = ym("2026-06")).copy(forecast = chartForecast(), finishedMonths = 8)
        show(state)
        compose.onNodeWithContentDescription(
            "June 2026 (forecast): Likely €140.00 of €500.00 spent. Likely range €115.00 to €165.00",
        ).assertIsSelected()
        compose.onNodeWithContentDescription(
            "June 2026 (forecast): Would likely add €360.00 to the surplus. Likely surplus after this month: €1,250.00",
        ).assertIsSelected()
        compose.onNode(hasText("June 2026 (forecast)") and isHeading()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Likely €140.00 of €500.00 spent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Would likely add €360.00 to the surplus").performScrollTo().assertIsDisplayed()
        // Largest first, each with its range. Unclipped: both may be below the test window's fold.
        val food = compose.onNodeWithText("€80.00 to €120.00").fetchSemanticsNode().positionInRoot.y
        val fun_ = compose.onNodeWithText("€30.00 to €50.00").fetchSemanticsNode().positionInRoot.y
        assertTrue(food < fun_)
        // A month that hasn't started has no burn-down yet.
        compose.onNodeWithText("Hasn’t started yet").assertExists()
    }

    @Test
    fun `the month under way says where it will likely end`() {
        val state = ready(months = threeMonths, surplus = withSurplus).copy(forecast = chartForecast(), finishedMonths = 8)
        show(state)
        compose.onNodeWithContentDescription(
            "April 2026 (so far): €110.00 spent · No budget set. Likely €150.00 spent by the month’s end",
        ).assertIsSelected()
        compose.onNodeWithText("Likely €150.00 spent by the month’s end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("At the month’s end it would likely add €350.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Forecast from the trend so far").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Forecast").assertCountAtLeast(2)
        compose.onAllNodesWithText("Likely range").assertCountAtLeast(2)
        compose.onNodeWithText("Likely month end").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without a forecast it says when forecasts start, and has no forecast keys`() {
        show(ready().copy(finishedMonths = 1))
        compose.onNodeWithText("Forecasts start once 3 months are finished (1 so far)").assertIsDisplayed()
        compose.onNodeWithText("Forecast").assertDoesNotExist()
        compose.onNodeWithText("Likely range").assertDoesNotExist()
    }

    @Test
    fun `the burn-down says where the month will likely end`() {
        val projected = burnDown().let { b -> b.copy(projection = listOf(Estimate(b.left, b.left, b.left), Estimate(eur(150), eur(-40), eur(280)))) }
        show(ready(burnDown = projected))
        compose.onNodeWithText("Likely €150.00 left at the month’s end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Likely range: -€40.00 to €280.00 left").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Likely €150.00 left at the month’s end", substring = true).assertExists()
    }

    @Test
    fun `a month likely to end over budget says by how much, with no minus signs`() {
        val projected = burnDown().let { b -> b.copy(projection = listOf(Estimate(b.left, b.left, b.left), Estimate(eur(-50), eur(-90), eur(-20)))) }
        show(ready(burnDown = projected))
        compose.onNodeWithText("Likely over by €50.00 at the month’s end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Likely range: over by €20.00 to €90.00").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each month is described in words, for TalkBack`() {
        show(ready())
        compose.onNodeWithContentDescription("March 2026: €340.00 of €500.00 spent").assertIsNotSelected()
        compose.onNodeWithContentDescription("April 2026 (so far): €110.00 spent · No budget set").assertIsSelected()
    }

    @Test
    fun `the selected month is listed largest first`() {
        show(ready())
        // The breakdown's heading; the burn-down at the top names the month too.
        compose.onNode(hasText("April 2026 (so far)") and isHeading()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€110.00 spent · No budget set").performScrollTo().assertIsDisplayed()
        // Unclipped: both rows are below the test window's fold.
        val fun_ = compose.onNodeWithText("€90.00").fetchSemanticsNode().positionInRoot.y
        val food = compose.onNodeWithText("€20.00").fetchSemanticsNode().positionInRoot.y
        assertTrue("Fun (€90) should be listed above Food (€20)", fun_ < food)
        // A deleted category says so.
        compose.onAllNodesWithText("Fun (deleted)").assertCountAtLeast(1)
    }

    @Test
    fun `tapping a month selects it`() {
        var selected: YearMonth? = null
        show(ready(), SpendingHistoryActions(onSelectMonth = { selected = it }))
        compose.onNodeWithContentDescription("March 2026: €340.00 of €500.00 spent").performClick()
        assertEquals(march, selected)
    }

    @Test
    fun `an overspent month says by how much, in words`() {
        show(ready(months = listOf(stack(march, eur(300), food to eur(340))), selected = march))
        compose.onNodeWithText("€340.00 of €300.00 spent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Over by €40.00").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a category in the list or the legend toggles its highlight`() {
        var state by mutableStateOf(ready())
        val toggled = mutableListOf<String>()
        compose.setContent {
            BudzetsTheme {
                SpendingHistoryContent(
                    state,
                    SpendingHistoryActions(onToggleHighlight = { key ->
                        toggled += key
                        state = state.copy(highlighted = key.takeUnless { it == state.highlighted })
                    }),
                )
            }
        }
        // The breakdown row (it has the amount next to it) and the legend chip.
        val row = compose.onNode(hasClickAction() and hasText("Food") and hasText("€20.00"), useUnmergedTree = false)
        row.performScrollTo().performClick()
        row.assertIsSelected()
        assertEquals(food.value, state.highlighted)

        val chip = compose.onNode(hasClickAction() and hasText("Food") and !hasText("€20.00"))
        chip.performScrollTo().assertIsSelected().performClick()
        assertEquals(null, state.highlighted)
        assertEquals(listOf(food.value, food.value), toggled)
    }

    @Test
    fun `the legend names the folded categories and the budget line`() {
        show(
            ready(
                months = listOf(stack(april, eur(100), null to eur(5), food to eur(20))),
                series = series + ChartSeries(null, "", isArchived = false, isDeleted = false, slot = -1),
            ),
        )
        compose.onAllNodesWithText("All other categories").assertCountAtLeast(2)
        compose.onNodeWithText("Budget").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no budget key without a budget line`() {
        show(ready(months = listOf(stack(april, null, food to eur(20)))))
        compose.onAllNodesWithText("Food").assertCountAtLeast(1)
        compose.onNodeWithText("Budget").assertDoesNotExist()
    }

    @Test
    fun `a closed month's surplus result and the total after it, in words`() {
        show(ready(months = threeMonths, surplus = withSurplus, selected = march))
        compose.onNodeWithText("Added €160.00 to the surplus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Surplus after this month: €130.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("March 2026: Added €160.00 to the surplus. Surplus after this month: €130.00").assertIsSelected()
    }

    @Test
    fun `a month not closed yet says what closing it would do, and the legend explains the lighter bar`() {
        show(ready(months = threeMonths, surplus = withSurplus, selected = ym("2026-02")))
        compose.onNodeWithText("Not closed yet: closing it now would take €30.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Surplus after this month: -€30.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not closed yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Surplus total").assertIsDisplayed()
    }

    @Test
    fun `a closed month that took from the surplus says so`() {
        val surplus = listOf(MonthSurplus(march, eur(-45), isClosed = true, runningTotal = eur(-45)), MonthSurplus(april, null, false, eur(-45)))
        show(ready(surplus = surplus, selected = march))
        compose.onNodeWithText("Took €45.00 from the surplus").performScrollTo().assertIsDisplayed()
        // April gives nothing and March is closed, so there is no lighter bar to explain.
        compose.onNodeWithText("Not closed yet").assertDoesNotExist()
    }

    @Test
    fun `a month with a boost shows what closing gave and what the boost took`() {
        val surplus = listOf(
            MonthSurplus(march, eur(190), isClosed = true, runningTotal = eur(190), boosts = eur(-50)),
            MonthSurplus(april, null, false, eur(190)),
        )
        show(ready(surplus = surplus, selected = march))
        compose.onNodeWithText("Added €190.00 to the surplus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Closing the month: +€240.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Boosts from the surplus: −€50.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-off boosts", substring = true).assertDoesNotExist()
    }

    @Test
    fun `without boosts the result isn't split`() {
        show(ready(months = threeMonths, surplus = withSurplus, selected = march))
        compose.onNodeWithText("Closing the month", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the month under way says what closing it now would do`() {
        show(ready(months = threeMonths, surplus = withSurplus))
        compose.onNodeWithText("Not closed yet: closing it now would add €400.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Surplus after this month: €530.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "April 2026 (so far): Not closed yet: closing it now would add €400.00. Surplus after this month: €530.00",
        ).assertIsSelected()
    }

    @Test
    fun `tapping a month in the surplus chart selects it`() {
        var selected: YearMonth? = null
        show(ready(months = threeMonths, surplus = withSurplus), SpendingHistoryActions(onSelectMonth = { selected = it }))
        // The tab's own scroll parent is the chart, which scrolls sideways; bring the page down to the
        // legend under it first.
        compose.onNodeWithText("Surplus total").performScrollTo()
        compose.onNodeWithContentDescription("February 2026: Not closed yet: closing it now would take €30.00. Surplus after this month: -€30.00")
            .performClick()
        assertEquals(ym("2026-02"), selected)
    }

    @Test
    fun `the surplus chart is shown even when no month gives anything`() {
        show(ready())
        compose.onNodeWithText("Surplus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Surplus total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nothing here yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a month without spending has an empty bar and says so`() {
        show(ready(months = listOf(stack(march, eur(500), food to eur(1)), stack(april, eur(500))), selected = april))
        compose.onNodeWithText("Nothing spent this month.").performScrollTo().assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountAtLeast(min: Int) {
    val count = fetchSemanticsNodes().size
    assertTrue("expected at least $min nodes, found $count", count >= min)
}
