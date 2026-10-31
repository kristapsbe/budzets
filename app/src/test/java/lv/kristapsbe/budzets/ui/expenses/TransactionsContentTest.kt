package lv.kristapsbe.budzets.ui.expenses

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.ExpenseId
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
class TransactionsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 4, 15)
    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")
    private val chips = listOf(CategoryChip(food, "Food"), CategoryChip(fun_, "Fun"))

    private fun row(id: String, amount: Long, category: String, note: String? = null) =
        ExpenseRow(ExpenseId(id), eur(amount), category, false, note)

    private fun ready(
        days: List<DayGroup> = emptyList(),
        selected: CategoryId? = null,
        query: String = "",
        hasCategories: Boolean = true,
    ) = TransactionsUiState.Ready(
        month = ym("2026-04"),
        isCurrentMonth = true,
        today = today,
        currency = CurrencySpec.DEFAULT,
        chips = chips,
        selectedCategory = selected,
        query = query,
        days = days,
        total = eur(days.sumOf { d -> d.rows.sumOf { it.amount.minor } } / 100),
        count = days.sumOf { it.rows.size },
        hasCategories = hasCategories,
    )

    private val sample = listOf(
        DayGroup(today, eur(7), listOf(row("a", 5, "Food", "bread"), row("b", 2, "Fun"))),
        DayGroup(today.minusDays(1), eur(3), listOf(row("c", 3, "Food"))),
        DayGroup(LocalDate.of(2026, 4, 2), eur(1), listOf(row("d", 1, "Fun"))),
    )

    private fun show(state: TransactionsUiState, actions: TransactionsActions = TransactionsActions(), onBack: (() -> Unit)? = null) {
        compose.setContent { BudzetsTheme { TransactionsContent(state, actions, onBack = onBack) } }
    }

    @Test
    fun `days are labelled and totals shown`() {
        show(ready(sample))
        compose.onNodeWithText("€11.00 spent · 4 expenses").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("bread").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        // Older days are below the fold on a small screen: scroll like a user would.
        compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Thursday, April 2"))
        compose.onNodeWithText("Thursday, April 2").assertIsDisplayed()
    }

    @Test
    fun `tapping a row opens it, chips filter, FAB passes the filter on`() {
        var opened: ExpenseId? = null
        var filter: CategoryId? = null
        var addedWith: CategoryId? = CategoryId("unset")
        show(
            ready(sample, selected = fun_),
            TransactionsActions(onOpenExpense = { opened = it }, onSelectCategory = { filter = it }, onAddExpense = { addedWith = it }),
        )
        compose.onNode(hasText("bread") and hasClickAction()).performClick()
        assertEquals(ExpenseId("a"), opened)
        compose.onNodeWithText("All").performClick()
        assertEquals(null, filter)
        compose.onNodeWithText("Add expense").performClick()
        assertEquals(fun_, addedWith)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `swipe and the accessibility action both delete`() {
        val deleted = mutableListOf<ExpenseId>()
        show(ready(sample), TransactionsActions(onDelete = { deleted += it }))

        compose.onNode(hasText("bread") and hasClickAction()).performCustomAccessibilityActionWithLabel("Delete")
        assertEquals(listOf(ExpenseId("a")), deleted)

        compose.onNode(hasText("€3.00") and hasClickAction()).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(ExpenseId("a"), ExpenseId("c")), deleted)
    }

    @Test
    fun `an expense takes a swipe left, and a swipe right changes month`() {
        val deleted = mutableListOf<ExpenseId>()
        val months = mutableListOf<YearMonth>()
        show(ready(sample), TransactionsActions(onDelete = { deleted += it }, onSelectMonth = { months += it }))

        compose.onNode(hasText("€3.00") and hasClickAction()).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(emptyList<ExpenseId>(), deleted)
        compose.awaitEquals(listOf(ym("2026-03"))) { months.toList() }
    }

    @Test
    fun `flicking the filter chips never changes month`() {
        val months = mutableListOf<YearMonth>()
        show(ready(sample), TransactionsActions(onSelectMonth = { months += it }))

        repeat(4) {
            compose.onNodeWithText("All").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithText("All").performTouchInput { swipeRight() }
            compose.waitForIdle()
        }
        assertEquals(emptyList<YearMonth>(), months)
    }

    @Test
    fun `search field reports what is typed`() {
        var query = ""
        show(ready(sample), TransactionsActions(onQueryChange = { query = it }))
        compose.onNodeWithContentDescription("Search notes").performClick()
        compose.onNodeWithText("Search notes").assertIsFocused() // ready to type, no extra tap
        compose.onNodeWithText("Search notes").performTextInput("bre")
        assertEquals("bre", query)
        compose.onNodeWithContentDescription("Close search").performClick()
        assertEquals("", query)
    }

    @Test
    fun `empty states say what is empty`() {
        show(ready(selected = food))
        compose.onNodeWithText("No Food spending in April 2026.").assertIsDisplayed()
    }

    @Test
    fun `empty search names the query`() {
        show(ready(query = "zzz"))
        compose.onNodeWithText("No notes match “zzz”.").assertIsDisplayed()
    }

    @Test
    fun `without categories there is no add button`() {
        show(ready(hasCategories = false))
        compose.onNodeWithText("Add categories on the Overview screen first.").assertIsDisplayed()
        compose.onNodeWithText("Add expense").assertDoesNotExist()
    }

    @Test
    fun `drill-down has a back arrow`() {
        var back = false
        show(ready(sample, selected = food), onBack = { back = true })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(true, back)
    }
}
