package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

/** Inside a real [MonthPager], because who gets a swipe is the point of the component. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwipeToDeleteTest {

    @get:Rule
    val compose = createComposeRule()

    private val april = ym("2026-04")
    private var deletes = 0
    private val months = mutableListOf<YearMonth>()

    private fun show(enabled: Boolean = true, confirmsDelete: Boolean = false) {
        compose.setContent {
            MonthPager(april, { months += it }, { MutableStateFlow(it) }, YearMonth.of(1, 1), Modifier.fillMaxSize()) {
                SwipeToDelete(onDelete = { deletes++ }, enabled = enabled, confirmsDelete = confirmsDelete) {
                    Text("Row", Modifier.fillMaxWidth().height(72.dp).testTag("row"))
                }
            }
        }
    }

    private val row get() = compose.onNodeWithTag("row")

    @Test
    fun `a full swipe left deletes and leaves the month alone`() {
        show()
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, deletes)
        assertEquals(emptyList<YearMonth>(), months)
    }

    @Test
    fun `a slow swipe short of the threshold springs back`() {
        show()
        row.performTouchInput { swipeLeft(startX = right, endX = right - width * 0.3f, durationMillis = 2_000) }
        compose.waitForIdle()
        assertEquals(0, deletes)
        assertEquals(emptyList<YearMonth>(), months)
    }

    @Test
    fun `a slow swipe past the threshold deletes`() {
        show()
        row.performTouchInput { swipeLeft(startX = right, endX = right - width * 0.5f, durationMillis = 2_000) }
        compose.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    fun `a fast flick deletes, but only after a real distance`() {
        show()
        row.performTouchInput { swipeLeft(startX = right, endX = right - 36.dp.toPx(), durationMillis = 30) }
        compose.waitForIdle()
        assertEquals(0, deletes)

        // 120 dp in 60 ms: short of the 40% on this 320 dp row, so only the speed can delete it.
        row.performTouchInput { swipeLeft(startX = right, endX = right - 120.dp.toPx(), durationMillis = 60) }
        compose.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    fun `a swipe right passes through to the previous month`() {
        show()
        row.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, deletes)
        compose.awaitEquals(listOf(ym("2026-03"))) { months.toList() }
    }

    @Test
    fun `disabled, a swipe in either direction changes month`() {
        show(enabled = false)
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, deletes)
        compose.awaitEquals(listOf(ym("2026-05"))) { months.toList() }

        row.performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.awaitEquals(listOf(ym("2026-05"), ym("2026-04"))) { months.toList() }
    }

    @Test
    fun `waiting for a confirmation, the row comes back and can be swiped again`() {
        show(confirmsDelete = true)
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, deletes)

        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2, deletes)
    }
}
