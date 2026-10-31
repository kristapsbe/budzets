package lv.kristapsbe.budzets.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import lv.kristapsbe.budzets.MainActivity
import lv.kristapsbe.budzets.ui.expenses.AMOUNT_FIELD_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole app on the JVM: real activity, navigation, Room database and DataStore. Data is
 * written on Room's threads, so each step waits for the screen to catch up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpenseFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The list inside a month page: the pager around it can scroll to an index too. */
    private val verticalList = hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitFor(hasText(text))

    /** An expense row: clickable, showing [amount]. Day headers and totals aren't clickable. */
    private fun row(amount: String) = hasText(amount) and hasClickAction()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `log, see the overview update, edit, delete with undo, and drill down`() {
        waitForText("Add starter categories")
        compose.onNodeWithText("Add starter categories").performClick()

        // Add from the overview. Nothing is preselected for the very first expense.
        waitForText("Add expense")
        compose.onNodeWithText("Add expense").performClick()
        waitFor(hasTestTag(AMOUNT_FIELD_TAG)) // the form appears once its data has loaded
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("12.5")
        compose.onNodeWithText("Save").performClick()
        waitForText("Choose a category")
        compose.onNodeWithText("Groceries").performClick()
        compose.onNodeWithText("Save").performClick()

        // No budget yet, so the overview reports spending in its setup card.
        waitForText("€12.50 spent")

        // History reconciles and an edit recalculates.
        compose.onNodeWithText("Spending").performClick()
        waitForText("€12.50 spent · 1 expense")
        compose.onNode(row("€12.50")).performClick()
        waitForText("Edit expense")
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performTextReplacement("20")
        compose.onNodeWithText("Save").performClick()
        waitForText("€20.00 spent · 1 expense")

        // Delete (TalkBack's action; same callback as the swipe) and undo.
        compose.onNode(row("€20.00")).performCustomAccessibilityActionWithLabel("Delete")
        waitForText("Undo")
        compose.onNodeWithText("Undo").performClick()
        waitFor(row("€20.00"))

        // The next expense starts on the last used category; the keyboard's Done saves it.
        compose.onNodeWithText("Add expense").performClick()
        waitFor(hasText("Groceries") and isSelected())
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("5")
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performImeAction()
        waitForText("€25.00 spent · 2 expenses")

        // The overview agrees, and a category opens its own spending list.
        compose.onNodeWithText("Overview").performClick()
        waitForText("€25.00 spent")
        compose.onNode(verticalList).performScrollToNode(hasText("No budget · €25.00 spent"))
        compose.onNodeWithText("No budget · €25.00 spent").performClick()
        waitForText("€25.00 spent · 2 expenses")
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Categories")
    }
}
