package lv.kristapsbe.budzets.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import lv.kristapsbe.budzets.MainActivity
import lv.kristapsbe.budzets.ui.expenses.AMOUNT_FIELD_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The widget's + button: [MainActivity] opens Add expense, from a cold start or while running. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAddTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `cold start opens Add expense with the overview underneath`() {
        ActivityScenario.launch<MainActivity>(addExpenseIntent(context)).use {
            waitFor(hasTestTag(AMOUNT_FIELD_TAG))
            compose.onNodeWithContentDescription("Cancel").performClick()
            waitFor(hasText("Add starter categories"))
        }
    }

    @Test
    fun `a running app gets Add expense on top of where it was`() {
        // singleTop: Android delivers the widget's intent to the running activity as a new intent.
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).setup()
        try {
            waitFor(hasText("Add starter categories"))
            controller.newIntent(addExpenseIntent(context))
            waitFor(hasTestTag(AMOUNT_FIELD_TAG))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
