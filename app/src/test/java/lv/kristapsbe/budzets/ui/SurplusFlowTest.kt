package lv.kristapsbe.budzets.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import lv.kristapsbe.budzets.MainActivity
import lv.kristapsbe.budzets.ui.budgets.DAILY_FIELD_TAG
import lv.kristapsbe.budzets.ui.budgets.MONTHLY_FIELD_TAG
import lv.kristapsbe.budzets.ui.expenses.AMOUNT_FIELD_TAG
import lv.kristapsbe.budzets.ui.surplus.SURPLUS_AMOUNT_TAG
import lv.kristapsbe.budzets.util.displayName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth
import java.util.Locale

/** The whole app on the JVM, like [ExpenseFlowTest]: closing a month and using its surplus. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SurplusFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, substring: Boolean = false) = waitFor(hasText(text, substring))

    private val verticalList = hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollTo(text: String) = compose.onNode(verticalList).performScrollToNode(hasText(text))

    /** For text in a list that may still be loading or be scrolled out of view. */
    private fun waitAndScrollTo(text: String) = compose.waitUntil(5_000) { runCatching { scrollTo(text) }.isSuccess }

    @Test
    fun `close this month, move surplus, boost next month, then re-close after a change`() {
        val month = YearMonth.now()
        val base = 20 * month.lengthOfMonth()
        val nextMonth = month.plusMonths(1).displayName(Locale.US)

        waitForText("Add starter categories")
        compose.onNodeWithText("Add starter categories").performClick()
        waitForText("Add expense")

        // Budgets: overall €20/day, Groceries €100/month.
        compose.onNodeWithText("Budgets").performClick()
        waitForText("Overall budget")
        compose.onNodeWithText("Overall budget").performClick()
        waitFor(hasTestTag(DAILY_FIELD_TAG))
        compose.onNodeWithTag(DAILY_FIELD_TAG).performTextInput("20")
        compose.onNodeWithText("Save").performClick()
        waitForText("€$base.00/month")
        scrollTo("Groceries")
        compose.onNodeWithText("Groceries").performClick()
        waitFor(hasTestTag(MONTHLY_FIELD_TAG))
        compose.onNodeWithTag(MONTHLY_FIELD_TAG).performTextInput("100")
        compose.onNodeWithText("Save").performClick()
        waitForText("€100.00/month")

        // Close the current month early, from the overview.
        compose.onNodeWithText("Overview").performClick()
        waitForText("€$base.00 left")
        scrollTo("Review & close this month")
        compose.onNodeWithText("Review & close this month").performClick()
        waitForText("+€100.00 saved")
        scrollTo("Close month")
        compose.onNodeWithText("Close month").performClick()
        waitAndScrollTo("Month closed. Surplus has been recorded.")

        // Surplus: Groceries 100, unallocated the rest of the overall budget.
        compose.onNodeWithText("Surplus").performClick()
        waitForText("€$base.00")
        compose.onNodeWithText("Move surplus").performClick()
        waitForText("Groceries · €100.00")
        // Forms are taller than Robolectric's small screen: scroll each control into view first.
        compose.onNodeWithText("Groceries · €100.00").performScrollTo().performClick()
        compose.onNodeWithText("Eating out").performScrollTo().performClick()
        compose.onNodeWithText("All (€100.00)").performScrollTo().performClick()
        compose.onNodeWithText("Move").performClick()
        waitAndScrollTo("Moved to Eating out")

        // Boost next month's Eating out budget with that surplus.
        compose.onNode(verticalList).performScrollToNode(hasText("Boost a budget"))
        compose.onNodeWithText("Boost a budget").performClick()
        waitForText("Use surplus from")
        compose.onNodeWithContentDescription("Next month").performClick()
        waitForText(nextMonth)
        compose.onNodeWithText("Eating out").performScrollTo().performClick()
        compose.onNodeWithText("Eating out · €100.00").performScrollTo().performClick()
        compose.onNodeWithTag(SURPLUS_AMOUNT_TAG).performScrollTo().performTextInput("30")
        compose.onNodeWithText("Boost").performClick()
        waitAndScrollTo("+€30.00 for Eating out, $nextMonth")

        // The overview's next month shows the boost.
        compose.onNodeWithText("Overview").performClick()
        waitAndScrollTo("Month closed. Surplus has been recorded.")
        scrollTo(YearMonth.now().displayName(Locale.US))
        compose.onNodeWithContentDescription("Next month").performClick()
        waitForText(nextMonth)
        waitForText("+€30.00")

        // A late expense in the closed month: re-close posts the difference.
        compose.onNodeWithText("Go to current month").performClick()
        waitAndScrollTo("Month closed. Surplus has been recorded.")
        compose.onNodeWithText("Add expense").performClick()
        waitFor(hasTestTag(AMOUNT_FIELD_TAG))
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("5")
        compose.onNodeWithText("Groceries").performClick()
        compose.onNodeWithText("Save").performClick()
        waitAndScrollTo("Changed since this month was closed. Close it again to update surplus.")
        // Through the accessibility action, not a tap: scrolled into view, the button can sit
        // under the floating Add expense button, which would take the tap.
        compose.onNodeWithText("Review & close").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("−€5.00 debit")
        scrollTo("Close again")
        compose.onNodeWithText("Close again").performClick()
        waitAndScrollTo("Month closed. Surplus has been recorded.")
    }
}
