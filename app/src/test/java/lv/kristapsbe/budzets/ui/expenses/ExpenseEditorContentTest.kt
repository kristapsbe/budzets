package lv.kristapsbe.budzets.ui.expenses

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.expense.DraftErrors
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpenseEditorContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 4, 15)
    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")

    private fun editing(
        isNew: Boolean = true,
        form: ExpenseForm = ExpenseForm(categoryId = food, date = today),
        errors: DraftErrors = DraftErrors(),
        closed: List<java.time.YearMonth> = emptyList(),
    ) = ExpenseEditorState.Editing(
        isNew = isNew,
        form = form,
        currency = CurrencySpec.DEFAULT,
        categories = listOf(CategoryOption(food, "Food", false), CategoryOption(fun_, "Old fun", true)),
        today = today,
        closedMonthsAffected = closed,
        errors = errors,
    )

    private fun show(state: ExpenseEditorState, actions: EditorActions = EditorActions()) {
        compose.setContent { BudzetsTheme { ExpenseEditorContent(state, actions) } }
    }

    @Test
    fun `new expense - preselected category and today are visible, typing and Done save`() {
        var typed = ""
        var saves = 0
        show(editing(), EditorActions(onAmountChange = { typed = it }, onSave = { saves++ }))

        compose.onNodeWithText("Add expense").assertIsDisplayed()
        compose.onNodeWithText("Food").assertIsSelected()
        compose.onNodeWithText("Old fun (archived)").assertIsNotSelected()
        compose.onNodeWithText("Today").assertIsSelected()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()

        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("4.2")
        assertEquals("4.2", typed)
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performImeAction()
        compose.onNodeWithText("Save").performClick()
        assertEquals(2, saves)
    }

    @Test
    fun `choosing a category and a quick date`() {
        var category: CategoryId? = null
        var date: LocalDate? = null
        show(editing(), EditorActions(onCategorySelect = { category = it }, onDateSelect = { date = it }))
        compose.onNodeWithText("Old fun (archived)").performClick()
        compose.onNodeWithText("Yesterday").performClick()
        assertEquals(fun_, category)
        assertEquals(today.minusDays(1), date)
    }

    @Test
    fun `opening the date picker drops the keyboard so the whole calendar is visible`() {
        show(editing())
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performClick()
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).assertIsFocused()
        compose.onNodeWithText("Other date").performClick()
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).assertIsNotFocused()
        compose.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun `an older date shows on its chip, the amount its currency symbol`() {
        show(editing(form = ExpenseForm("1", food, LocalDate.of(2026, 3, 2))))
        // M3 shows the prefix once the field has text or focus; en-US writes the symbol first.
        compose.onNodeWithText("€").assertIsDisplayed()
        compose.onNodeWithText("Mon, Mar 2").assertIsSelected()
        compose.onNodeWithText("Today").assertIsNotSelected()
    }

    @Test
    fun `validation errors are spelled out`() {
        show(editing(form = ExpenseForm(date = today), errors = DraftErrors(AmountError.EMPTY, missingCategory = true)))
        compose.onNodeWithText("Enter an amount").assertIsDisplayed()
        compose.onNodeWithText("Choose a category").assertIsDisplayed()
    }

    @Test
    fun `too many decimals names the currency's precision`() {
        show(editing(errors = DraftErrors(AmountError.TOO_MANY_DECIMALS)))
        compose.onNodeWithText("Use at most 2 decimal places").assertIsDisplayed()
    }

    @Test
    fun `editing a closed month explains the consequence`() {
        show(editing(isNew = false, closed = listOf(ym("2026-03"))))
        compose.onNodeWithText("Edit expense").assertIsDisplayed()
        compose.onNodeWithText("Already closed: March 2026. After saving, close it again to update its surplus.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `delete asks for confirmation first`() {
        var deleted = 0
        show(editing(isNew = false), EditorActions(onDelete = { deleted++ }))

        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete this expense?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)

        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick() // the dialog's button; the icon has no text
        assertEquals(1, deleted)
    }

    @Test
    fun `missing expense offers a way out`() {
        var closed = false
        show(ExpenseEditorState.NotFound, EditorActions(onClose = { closed = true }))
        compose.onNodeWithText("This expense no longer exists.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(true, closed)
    }
}
