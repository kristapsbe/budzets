package lv.kristapsbe.budzets.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.calculation.StackSegment
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
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
import java.time.YearMonth

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
    ) = SpendingHistoryUiState.Ready(CurrencySpec.DEFAULT, months, surplus, series, april, selected, highlighted)

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
    fun `each month is described in words, for TalkBack`() {
        show(ready())
        compose.onNodeWithContentDescription("March 2026: €340.00 of €500.00 spent").assertIsNotSelected()
        compose.onNodeWithContentDescription("April 2026 (so far): €110.00 spent · No budget set").assertIsSelected()
    }

    @Test
    fun `the selected month is listed largest first`() {
        show(ready())
        compose.onNodeWithText("April 2026 (so far)").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("Budget").assertIsDisplayed()
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
