package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import kotlinx.coroutines.flow.MutableStateFlow
import lv.kristapsbe.budzets.domain.ym
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
class MonthPagerTest {

    private val NOT_A_MONTH = YearMonth.of(1, 1)

    @get:Rule
    val compose = createComposeRule()

    private var month by mutableStateOf(ym("2026-04"))
    private val reported = mutableListOf<YearMonth>()

    /** Follows reports as a ViewModel does, so a wrong one would really move the selection. */
    private fun show() {
        compose.setContent {
            MonthPager(
                month,
                onMonthSettled = { reported += it; month = it },
                pageState = { MutableStateFlow(it) },
                loading = NOT_A_MONTH,
                modifier = Modifier.fillMaxSize().testTag("pager"),
            ) { shown -> Text(shown.toString()) }
        }
    }

    @Test
    fun `swiping left shows and reports the next month, and right the previous`() {
        show()
        compose.onNodeWithText("2026-04").assertIsDisplayed()

        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("2026-05").assertIsDisplayed()
        compose.awaitEquals(listOf(ym("2026-05"))) { reported.toList() }

        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("2026-04").assertIsDisplayed()
        compose.awaitEquals(listOf(ym("2026-05"), ym("2026-04"))) { reported.toList() }
    }

    @Test
    fun `a short drag springs back and reports nothing`() {
        show()
        compose.onNodeWithTag("pager").performTouchInput {
            down(center)
            moveBy(Offset(-40f, 0f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("2026-04").assertIsDisplayed()
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a month changed from outside moves the pager, even far away`() {
        show()
        month = ym("2026-05")
        compose.waitForIdle()
        compose.onNodeWithText("2026-05").assertIsDisplayed()

        month = ym("2031-11")
        compose.waitForIdle()
        compose.onNodeWithText("2031-11").assertIsDisplayed()
        assertEquals(ym("2031-11"), month)
    }

    @Test
    fun `an arrow waits for the month to load, showing no empty page`() {
        val may = MutableStateFlow(NOT_A_MONTH)
        compose.setContent {
            MonthPager(
                month,
                onMonthSettled = { reported += it; month = it },
                pageState = { if (it == ym("2026-05")) may else MutableStateFlow(it) },
                loading = NOT_A_MONTH,
                modifier = Modifier.fillMaxSize(),
            ) { shown -> Text(shown.toString()) }
        }
        month = ym("2026-05")
        compose.waitForIdle()
        compose.onNodeWithText("2026-04").assertIsDisplayed()

        may.value = ym("2026-05")
        compose.waitForIdle()
        compose.onNodeWithText("2026-05").assertIsDisplayed()
    }

    @Test
    fun `quick changes end on the last month, whatever the pager passed on the way`() {
        show()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (next in listOf("2026-05", "2026-06", "2026-07", "2026-08")) {
            month = ym(next)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("2026-08").assertIsDisplayed()
        assertEquals(ym("2026-08"), month)
    }

    /** A page with something saved, like a list's scroll position. */
    private fun showWithSavedState() {
        compose.setContent {
            MonthPager(
                month,
                onMonthSettled = { reported += it; month = it },
                pageState = { MutableStateFlow(it) },
                loading = NOT_A_MONTH,
                modifier = Modifier.fillMaxSize().testTag("pager"),
            ) { shown ->
                var taps by rememberSaveable { mutableIntStateOf(0) }
                Button(onClick = { taps++ }) { Text("$shown taps $taps") }
            }
        }
    }

    @Test
    fun `a month that was left starts afresh`() {
        showWithSavedState()
        compose.onNodeWithText("2026-04 taps 0").performClick()
        compose.onNodeWithText("2026-04 taps 1").assertIsDisplayed()

        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.awaitEquals(ym("2026-05")) { month }
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.awaitEquals(ym("2026-04")) { month }

        compose.onNodeWithText("2026-04 taps 0").assertIsDisplayed()
    }

    @Test
    fun `a swipe that springs back keeps the page as it was`() {
        showWithSavedState()
        compose.onNodeWithText("2026-04 taps 0").performClick()

        compose.onNodeWithTag("pager").performTouchInput {
            down(center)
            moveBy(Offset(-40f, 0f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("2026-04 taps 1").assertIsDisplayed()
    }

    @Test
    fun `the page's saved state survives being recreated, even on a month left before`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MonthPager(
                month,
                onMonthSettled = { reported += it; month = it },
                pageState = { MutableStateFlow(it) },
                loading = NOT_A_MONTH,
                modifier = Modifier.fillMaxSize().testTag("pager"),
            ) { shown ->
                var taps by rememberSaveable { mutableIntStateOf(0) }
                Button(onClick = { taps++ }) { Text("$shown taps $taps") }
            }
        }
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.awaitEquals(ym("2026-05")) { month }
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.awaitEquals(ym("2026-04")) { month }
        compose.onNodeWithText("2026-04 taps 0").performClick()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2026-04 taps 1").assertIsDisplayed()
    }

    @Test
    fun `years wrap correctly across pages`() {
        month = ym("2026-12")
        show()
        compose.onNodeWithText("2026-12").assertIsDisplayed()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("2027-01").assertIsDisplayed()
    }
}
