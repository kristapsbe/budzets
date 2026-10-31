package lv.kristapsbe.budzets.ui.components

import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertEquals

/**
 * For what a [MonthPager] reports: that comes a frame after it settles, so `waitForIdle` can return
 * first. Still fails as an assertion, showing the difference, rather than as a bare timeout.
 */
fun <T> ComposeTestRule.awaitEquals(expected: T, actual: () -> T) {
    runCatching { waitUntil(2_000) { actual() == expected } }
    assertEquals(expected, actual())
}
