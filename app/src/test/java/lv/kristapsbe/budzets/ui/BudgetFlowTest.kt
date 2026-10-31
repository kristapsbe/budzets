package lv.kristapsbe.budzets.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import lv.kristapsbe.budzets.MainActivity
import lv.kristapsbe.budzets.ui.budgets.CATEGORY_NAME_FIELD_TAG
import lv.kristapsbe.budzets.ui.budgets.DAILY_FIELD_TAG
import lv.kristapsbe.budzets.ui.budgets.MONTHLY_FIELD_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

/** The whole app on the JVM, like [ExpenseFlowTest], for budgets and category management. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The list inside a month page: the pager around it can scroll to an index too. */
    private val verticalList = hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, substring: Boolean = false) = waitFor(hasText(text, substring))

    @Test
    fun `set budgets, see the overall warning, manage categories, and the overview agrees`() {
        // The app uses the real clock, so expect this month's length.
        val base = "€%d.00".format(20 * YearMonth.now().lengthOfMonth())

        waitForText("Add starter categories")
        compose.onNodeWithText("Add starter categories").performClick()
        waitForText("Add expense")

        compose.onNodeWithText("Budgets").performClick()
        waitForText("Overall budget")
        compose.onNodeWithText("Overall budget").performClick()
        waitFor(hasTestTag(DAILY_FIELD_TAG))
        compose.onNodeWithTag(DAILY_FIELD_TAG).performTextInput("20")
        waitForText("Plan for ", substring = true)
        compose.onNodeWithText("Save").performClick()
        waitForText("$base/month")

        // A category budget above the overall: warned while typing, and after saving.
        compose.onNode(verticalList).performScrollToNode(hasText("Groceries"))
        compose.onNodeWithText("Groceries").performClick()
        waitFor(hasTestTag(MONTHLY_FIELD_TAG))
        compose.onNodeWithTag(MONTHLY_FIELD_TAG).performTextInput("1000")
        waitForText("Category budgets would add up to €1,000.00", substring = true)
        compose.onNodeWithText("Save").performClick()
        waitForText("€1,000.00") // the Groceries row, still scrolled into view
        val exceeds = "Category budgets add up to €1,000.00, more than the overall budget of $base."
        compose.onNode(verticalList).performScrollToNode(hasText(exceeds))

        // Category management lives here too.
        compose.onNodeWithText("Add category").performClick()
        compose.onNodeWithTag(CATEGORY_NAME_FIELD_TAG).performTextInput("Pets")
        compose.onNodeWithText("Save").performClick()
        compose.onNode(verticalList).performScrollToNode(hasText("Pets"))

        // The overview uses the same budgets.
        compose.onNodeWithText("Overview").performClick()
        waitForText("$base left")
        waitForText(exceeds)
    }
}
