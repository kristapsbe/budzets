package lv.kristapsbe.budzets.ui.budgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlanRule
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetTarget
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
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val april = ym("2026-04")

    /** The list inside a month page: the pager around it can scroll to an index too. */
    private val verticalList = hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

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

    private val foodRow get() = compose.onNode(hasText("Food") and hasClickAction())
    private val dragging get() = compose.onNodeWithTag(CATEGORY_DRAGGING_TAG, useUnmergedTree = true)

    /** Holds a finger on Food past the long-press time. */
    private fun holdFood() {
        foodRow.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
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
        val list = compose.onNode(verticalList)

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
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))
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
        val list = compose.onNode(verticalList)

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
        compose.onNode(verticalList).performScrollToNode(hasText("Add starter categories"))
        compose.onNodeWithText("Add starter categories").performClick()
        assertEquals(true, starters)
    }

    @Test
    fun `a category can be deleted from its menu, and the dialog says what happens`() {
        var deleted: CategoryId? = null
        show(ready(), BudgetsActions(onDelete = { deleted = it }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))
        compose.onNodeWithContentDescription("Actions for Food").performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Delete Food?").assertIsDisplayed()
        compose.onNodeWithText("Closed months keep it exactly as it was.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This can’t be undone.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(food, deleted)
    }

    @Test
    fun `a closed month can't be edited`() {
        var edits = 0
        val state = ready().copy(isClosed = true, archived = emptyList(), unallocated = eur(300))
        show(state, BudgetsActions(onEditBudget = { _, _ -> edits++ }))

        compose.onNodeWithText("April 2026 is closed, so its budgets stay as they were. Changes start from the next open month. To change this month, reopen it.")
            .assertIsDisplayed()
        compose.onNodeWithText("Overall budget").performClick()
        val list = compose.onNode(verticalList)
        list.performScrollToNode(hasText("€10.00/day"))
        compose.onNodeWithText("€10.00/day").performClick()
        assertEquals(0, edits)

        compose.onNodeWithText("Add category").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for Food").assertDoesNotExist()
        compose.onNodeWithText("€300.00 isn’t assigned to a category.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `swiping a category left asks first, and Delete or Cancel decide`() {
        var deleted: CategoryId? = null
        show(ready(), BudgetsActions(onDelete = { deleted = it }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))

        foodRow.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete Food?").assertIsDisplayed()
        assertEquals(null, deleted)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete Food?").assertDoesNotExist()
        assertEquals(null, deleted)

        // Cancelled, the row is back and works again.
        foodRow.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(food, deleted)
    }

    @Test
    fun `an archived category can be swiped away too`() {
        var deleted: CategoryId? = null
        show(ready(), BudgetsActions(onDelete = { deleted = it }))
        compose.onNode(verticalList).performScrollToNode(hasText("Archived from February 2026"))

        compose.onNodeWithText("Archived from February 2026").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete Old?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(old, deleted)
    }

    @Test
    fun `swiping a category right goes to the previous month`() {
        var month: YearMonth? = null
        show(ready(), BudgetsActions(onSelectMonth = { month = it }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))

        foodRow.performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.awaitEquals(ym("2026-03")) { month }
        compose.onNodeWithText("Delete Food?").assertDoesNotExist()
    }

    @Test
    fun `swiping anywhere off the rows changes month both ways`() {
        val months = mutableListOf<YearMonth>()
        show(ready(), BudgetsActions(onSelectMonth = { months += it }))

        compose.onNodeWithText("Overall budget").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.awaitEquals(listOf(ym("2026-05"))) { months.toList() }
    }

    @Test
    fun `holding a category shows it can be dragged, and letting go changes nothing`() {
        var reordered: List<CategoryId>? = null
        var edits = 0
        show(ready(), BudgetsActions(onReorder = { reordered = it }, onEditBudget = { _, _ -> edits++ }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))
        dragging.assertDoesNotExist()

        holdFood()
        dragging.assertExists()

        foodRow.performTouchInput { up() }
        compose.waitForIdle()
        dragging.assertDoesNotExist()
        assertEquals(null, reordered)
        assertEquals("a long press isn't a tap", 0, edits)
    }

    @Test
    fun `dragging a category past another saves the new order`() {
        var reordered: List<CategoryId>? = null
        var edits = 0
        val categories = listOf(
            CategoryBudgetRow(food, "Food", line(null), null, canMoveUp = false, canMoveDown = true),
            CategoryBudgetRow(fun_, "Fun", line(null), null, canMoveUp = true, canMoveDown = false),
        )
        show(ready(categories = categories, archived = emptyList()), BudgetsActions(onReorder = { reordered = it }, onEditBudget = { _, _ -> edits++ }))
        compose.onNode(verticalList).performScrollToNode(hasText("Fun"))

        holdFood()
        repeat(12) {
            foodRow.performTouchInput { moveBy(Offset(0f, 20.dp.toPx())) }
            compose.waitForIdle()
        }
        foodRow.performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(listOf(fun_, food), reordered)
        assertEquals(0, edits)
    }

    @Test
    fun `a finger that moves before the long press time doesn't start a drag`() {
        var reordered: List<CategoryId>? = null
        show(ready(), BudgetsActions(onReorder = { reordered = it }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))

        foodRow.performTouchInput {
            down(center)
            moveBy(Offset(0f, 60.dp.toPx()))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        dragging.assertDoesNotExist()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(null, reordered)
    }

    @Test
    fun `a category that can't move ignores a long press`() {
        val row = CategoryBudgetRow(food, "Food", line(null), null, canMoveUp = false, canMoveDown = false)
        show(ready(categories = listOf(row)))
        compose.onNode(verticalList).performScrollToNode(hasText("Food"))

        holdFood()
        dragging.assertDoesNotExist()
        foodRow.performTouchInput { up() }
    }

    @Test
    fun `in a closed month a row ignores a long press, and swipes change month`() {
        val months = mutableListOf<YearMonth>()
        val categories = listOf(
            CategoryBudgetRow(food, "Food", line(BudgetTarget(dailyMinor = 1_000)), null, canMoveUp = false, canMoveDown = false),
            CategoryBudgetRow(fun_, "Fun", line(null), null, canMoveUp = false, canMoveDown = false),
        )
        show(ready(categories = categories).copy(isClosed = true, archived = emptyList()), BudgetsActions(onSelectMonth = { months += it }))
        compose.onNode(verticalList).performScrollToNode(hasText("€10.00/day"))
        val closedFood = compose.onNodeWithText("€10.00/day")

        closedFood.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        dragging.assertDoesNotExist()
        closedFood.performTouchInput { up() }

        closedFood.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete Food?").assertDoesNotExist()
        compose.awaitEquals(listOf(ym("2026-05"))) { months.toList() }
    }

    @Test
    fun `a deleted category in an earlier open month is marked and can't be changed`() {
        var edits = 0
        val row = CategoryBudgetRow(food, "Food", line(BudgetTarget(dailyMinor = 1_000)), april, canMoveUp = false, canMoveDown = false, isDeleted = true)
        show(ready(categories = listOf(row)), BudgetsActions(onEditBudget = { _, _ -> edits++ }))

        val list = compose.onNode(verticalList)
        list.performScrollToNode(hasText("Food (deleted)"))
        compose.onNodeWithText("Food (deleted)").performClick()
        assertEquals(0, edits)
        compose.onNodeWithContentDescription("Actions for Food").assertDoesNotExist()
        compose.onNodeWithText("Archived from April 2026").assertDoesNotExist()
    }
}

private fun SemanticsNodeInteraction.assertTextSelection(expected: TextRange) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
}
