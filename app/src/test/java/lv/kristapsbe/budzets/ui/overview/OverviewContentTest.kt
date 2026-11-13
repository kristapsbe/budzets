package lv.kristapsbe.budzets.ui.overview

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.components.awaitEquals
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverviewContentTest {

    @get:Rule
    val compose = createComposeRule()

    /** The list inside a month page: the pager around it can scroll to an index too. */
    private val verticalList = hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")
    private val april = ym("2026-04")

    private fun ready(
        summary: MonthSummary,
        rows: List<CategoryRow>,
        hasCategories: Boolean = true,
        closeStatus: CloseStatus = CloseStatus.OPEN,
        backupReminder: BackupReminder? = null,
        daily: DailyAllowance? = null,
    ) = OverviewUiState.Ready(
        month = april,
        isCurrentMonth = true,
        currency = CurrencySpec.DEFAULT,
        summary = summary,
        pace = PaceCalculator.pace(summary, LocalDate.of(2026, 4, 15)),
        rows = rows,
        closeStatus = closeStatus,
        totalSurplus = eur(42),
        hasCategories = hasCategories,
        backupReminder = backupReminder,
        daily = daily,
    )

    private fun show(state: OverviewUiState, actions: OverviewActions = OverviewActions()) {
        compose.setContent { BudzetsTheme { OverviewContent(state, actions) } }
    }

    @Test
    fun `first launch explains the next step and offers starter categories`() {
        var clicked = false
        val empty = MonthSummary(april, null, false, emptyMap())
        show(ready(empty, emptyList(), hasCategories = false), OverviewActions(onAddStarterCategories = { clicked = true }))

        compose.onNodeWithText("No categories yet").assertIsDisplayed()
        compose.onNodeWithText("Add expense").assertDoesNotExist()
        compose.onNodeWithText("Add starter categories").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun `no budget shows setup prompt, add expense is available`() {
        val summary = MonthSummary(april, null, false, emptyMap())
        val rows = listOf(CategoryRow(food, "Food", false, CategorySummary(food, null, Money.ZERO, Money.ZERO)))
        show(ready(summary, rows))

        compose.onNodeWithText("No budget for this month").assertIsDisplayed()
        compose.onNodeWithText("Set up budgets").assertIsDisplayed()
        compose.onNodeWithText("Add expense").assertIsDisplayed()
    }

    @Test
    fun `budget card separates base and boosts, over-budget is stated in text`() {
        val foodSummary = CategorySummary(food, eur(300), eur(50), eur(355)) // over by 5
        val funSummary = CategorySummary(fun_, eur(100), Money.ZERO, eur(40))
        val summary = MonthSummary(april, eur(600), false, mapOf(food to foodSummary, fun_ to funSummary))
        show(ready(summary, listOf(CategoryRow(food, "Food", false, foodSummary), CategoryRow(fun_, "Fun", false, funSummary))))

        compose.onNodeWithText("€255.00 left").assertIsDisplayed() // 650 - 395
        compose.onNodeWithText("€395.00 of €650.00 spent").assertIsDisplayed()
        compose.onNodeWithText("Base budget").assertIsDisplayed()
        compose.onNodeWithText("+€50.00").assertIsDisplayed()
        compose.onNodeWithText("Saved surplus").assertIsDisplayed()

        // Category rows are below the fold on a small screen: scroll like a user would.
        val list = compose.onNode(verticalList)
        list.performScrollToNode(hasText("Over by €5.00"))
        compose.onNodeWithText("Over by €5.00").assertIsDisplayed()
        compose.onNodeWithText("+€50.00 boost").assertIsDisplayed()
        list.performScrollToNode(hasText("€60.00 left"))
        compose.onNodeWithText("€60.00 left").assertIsDisplayed()
    }

    private val budgeted = CategorySummary(food, eur(600), Money.ZERO, eur(130))
    private fun budgetedMonth(daily: DailyAllowance?) =
        ready(MonthSummary(april, eur(600), false, mapOf(food to budgeted)), listOf(CategoryRow(food, "Food", false, budgeted)), daily = daily)

    @Test
    fun `daily amount sits under the status with what's left today`() {
        show(budgetedMonth(DailyAllowance(eur(31, 25), eur(30))))
        compose.onNodeWithText("€31.25 a day · €1.25 left today").assertIsDisplayed()
        compose.onNodeWithText("Pace guide", substring = true).assertIsDisplayed()
    }

    @Test
    fun `today over its daily amount is said in words`() {
        show(budgetedMonth(DailyAllowance(eur(10), eur(13))))
        compose.onNodeWithText("€10.00 a day · €3.00 over today").assertIsDisplayed()
    }

    @Test
    fun `no daily amount line without one`() {
        show(budgetedMonth(daily = null))
        compose.onNodeWithText("Pace guide", substring = true).assertIsDisplayed()
        compose.onNodeWithText("a day", substring = true).assertDoesNotExist()
    }

    @Test
    fun `swiping changes month, category rows included`() {
        val food = CategoryId("food")
        val row = CategorySummary(food, eur(100), Money.ZERO, eur(40))
        val summary = MonthSummary(april, eur(600), false, mapOf(food to row))
        val months = mutableListOf<YearMonth>()
        show(ready(summary, listOf(CategoryRow(food, "Food", false, row))), OverviewActions(onSelectMonth = { months += it }))

        compose.onNodeWithText("April 2026").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.awaitEquals(listOf(ym("2026-05"))) { months.toList() }

        compose.onNode(verticalList).performScrollToNode(hasText("€60.00 left"))
        compose.onNodeWithText("€60.00 left").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.awaitEquals(listOf(ym("2026-05"), ym("2026-04"))) { months.toList() }
    }

    @Test
    fun `month controls and changed-since-closing banner`() {
        var prev = 0
        val summary = MonthSummary(april, eur(100), false, emptyMap())
        show(
            ready(summary, emptyList(), closeStatus = CloseStatus.CHANGED_SINCE_CLOSING),
            OverviewActions(onPreviousMonth = { prev++ }),
        )
        compose.onNodeWithText("April 2026").assertIsDisplayed()
        compose.onNodeWithText("Changed since this month was closed. Close it again to update surplus.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous month").performClick()
        assertEquals(1, prev)
    }

    @Test
    fun `error state offers retry`() {
        var retried = false
        show(OverviewUiState.Error("boom"), OverviewActions(onRetry = { retried = true }))
        compose.onNodeWithText("Couldn’t load this month.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun `backup reminder opens Settings`() {
        var opened = false
        val summary = MonthSummary(april, null, false, emptyMap())
        show(ready(summary, emptyList(), backupReminder = BackupReminder(34)), OverviewActions(onOpenSettings = { opened = true }))
        compose.onNodeWithText("Your last backup was 34 days ago.").assertIsDisplayed()
        compose.onNodeWithText("Back up").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `the chart icon opens spending by month`() {
        var opened = false
        show(ready(MonthSummary(april, null, false, emptyMap()), emptyList()), OverviewActions(onOpenHistory = { opened = true }))
        compose.onNodeWithContentDescription("Spending by month").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `backup reminder when there is no backup at all`() {
        show(ready(MonthSummary(april, null, false, emptyMap()), emptyList(), backupReminder = BackupReminder(null)))
        compose.onNodeWithText("No backup yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a one-off boost is mentioned on its month, and a deleted category is marked`() {
        val summary = MonthSummary(april, eur(100), false, emptyMap())
        val deleted = CategoryRow(CategoryId("old"), "Old", true, CategorySummary(CategoryId("old"), null, Money.ZERO, eur(3)), isDeleted = true)
        show(ready(summary, listOf(deleted)).copy(oneOffTotal = eur(200)))

        compose.onNodeWithText("One-off boost this month: +€200.00, added to Unallocated surplus.").assertIsDisplayed()
        compose.onNode(verticalList).performScrollToNode(hasText("Old (deleted)"))
        compose.onNodeWithText("Old (deleted)").assertIsDisplayed()
    }
}
