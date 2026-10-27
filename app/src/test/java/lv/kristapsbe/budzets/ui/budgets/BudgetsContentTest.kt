package lv.kristapsbe.budzets.ui.budgets

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlanRule
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
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
class BudgetsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val april = ym("2026-04")
    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")
    private val old = CategoryId("old")

    private fun line(target: BudgetTarget?, since: YearMonth? = null, next: YearMonth? = null) =
        BudgetLine(target?.let { MonthlyPlanRule.plan(it, april) }, target, since, next)

    private fun ready(
        overall: BudgetLine = line(BudgetTarget(dailyMinor = 2_000), since = ym("2026-01"), next = ym("2026-06")),
        derived: Boolean = false,
        exceeds: Boolean = false,
        unallocated: Money = eur(300),
        categories: List<CategoryBudgetRow> = listOf(
            CategoryBudgetRow(food, "Food", line(BudgetTarget(dailyMinor = 1_000)), null, canMoveUp = false, canMoveDown = true),
            CategoryBudgetRow(fun_, "Fun", line(null), null, canMoveUp = true, canMoveDown = false),
        ),
        archived: List<ArchivedRow> = listOf(ArchivedRow(old, "Old", ym("2026-02"))),
    ) = BudgetsUiState.Ready(
        month = april,
        isCurrentMonth = true,
        isClosed = false,
        currency = CurrencySpec.DEFAULT,
        overall = overall,
        overallBase = overall.plan?.amount ?: eur(300),
        overallDerived = derived,
        categoryTotal = eur(300),
        exceedsOverall = exceeds,
        unallocated = unallocated,
        categories = categories,
        archived = archived,
    )

    private fun show(state: BudgetsUiState, actions: BudgetsActions = BudgetsActions()) {
        compose.setContent { BudzetsTheme { BudgetsContent(state, actions) } }
    }

    @Test
    fun `overall card shows plan, targets and version notes, and opens the editor`() {
        var edited: Pair<CategoryId?, YearMonth>? = null
        show(ready(), BudgetsActions(onEditBudget = { id, month -> edited = id to month }))

        compose.onNodeWithText("€600.00/month").assertIsDisplayed()
        compose.onNodeWithText("€20.00/day").assertIsDisplayed()
        compose.onNodeWithText("Since January 2026 · Changes in June 2026").assertIsDisplayed()
        compose.onNodeWithText("Category budgets: €300.00 of €600.00").assertIsDisplayed()
        compose.onNodeWithText("Overall budget").performClick()
        assertEquals(null to april, edited)
    }

    @Test
    fun `derived overall is explained`() {
        show(ready(overall = line(null), derived = true, unallocated = Money.ZERO))
        compose.onNodeWithText("Not set: the overall plan is the sum of category budgets, €300.00.").assertIsDisplayed()
    }

    @Test
    fun `categories above the overall are stated in text`() {
        show(ready(exceeds = true))
        compose.onNodeWithText("Category budgets add up to €300.00, more than the overall budget of €600.00.").assertIsDisplayed()
    }

    @Test
    fun `category rows open the editor and their menu offers what's possible`() {
        var edited: CategoryId? = null
        var moved: Pair<CategoryId, Boolean>? = null
        var archived: CategoryId? = null
        show(ready(), BudgetsActions(onEditBudget = { id, _ -> edited = id }, onMove = { id, up -> moved = id to up }, onArchive = { archived = it }))
        val list = compose.onNode(hasScrollToIndexAction())

        list.performScrollToNode(hasText("€10.00/day"))
        compose.onNodeWithText("€10.00/day").performClick()
        assertEquals(food, edited)

        compose.onNodeWithContentDescription("Actions for Food").performClick()
        compose.onNodeWithText("Move up").assertDoesNotExist()
        compose.onNodeWithText("Move down").performClick()
        assertEquals(food to false, moved)

        list.performScrollToNode(hasText("No budget"))
        compose.onNodeWithContentDescription("Actions for Fun").performClick()
        compose.onNodeWithText("Archive").performClick()
        assertEquals(fun_, archived)
    }

    @Test
    fun `add category dialog trims the name and refuses an empty one`() {
        var added: String? = null
        show(ready(), BudgetsActions(onAddCategory = { added = it }))
        compose.onNodeWithText("Add category").performClick()
        compose.onNode(hasText("Save")).assertIsNotEnabled()
        compose.onNodeWithTag(CATEGORY_NAME_FIELD_TAG).performTextInput("  Pets ")
        compose.onNode(hasText("Save")).assertIsEnabled().performClick()
        assertEquals("Pets", added)
    }

    @Test
    fun `rename dialog starts from the current name`() {
        var renamed: Pair<CategoryId, String>? = null
        show(ready(), BudgetsActions(onRename = { id, name -> renamed = id to name }))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("€10.00/day"))
        compose.onNodeWithContentDescription("Actions for Food").performClick()
        compose.onNodeWithText("Rename").performClick()
        // Opens with the name selected: typing replaces it rather than prepending to it.
        compose.onNodeWithTag(CATEGORY_NAME_FIELD_TAG).assertTextSelection(TextRange(0, 4))
        compose.onNodeWithTag(CATEGORY_NAME_FIELD_TAG).performTextReplacement("Groceries")
        compose.onNodeWithText("Save").performClick()
        assertEquals(food to "Groceries", renamed)
    }

    @Test
    fun `archived categories can be restored or deleted after confirming`() {
        var unarchived: CategoryId? = null
        var deleted: CategoryId? = null
        show(ready(), BudgetsActions(onUnarchive = { unarchived = it }, onDelete = { deleted = it }))
        val list = compose.onNode(hasScrollToIndexAction())

        list.performScrollToNode(hasText("Archived from February 2026"))
        compose.onNodeWithContentDescription("Actions for Old").performClick()
        compose.onNodeWithText("Unarchive").performClick()
        assertEquals(old, unarchived)

        compose.onNodeWithContentDescription("Actions for Old").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete Old?").assertIsDisplayed()
        assertEquals(null, deleted)
        compose.onNodeWithText("Delete").performClick() // the dialog's button
        assertEquals(old, deleted)
    }

    @Test
    fun `no categories offers starters`() {
        var starters = false
        show(ready(categories = emptyList(), archived = emptyList()), BudgetsActions(onAddStarterCategories = { starters = true }))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Add starter categories"))
        compose.onNodeWithText("Add starter categories").performClick()
        assertEquals(true, starters)
    }
}

private fun SemanticsNodeInteraction.assertTextSelection(expected: TextRange) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
}
