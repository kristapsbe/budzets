package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.time.YearMonth

/** Pages are absolute months, so there's no anchor month. Years 0 to 9999, as `YearMonth` prints. */
private const val PAGE_COUNT = 10_000 * 12

private fun YearMonth.toPage(): Int = year * 12 + monthValue - 1

private fun monthOf(page: Int): YearMonth = YearMonth.of(page / 12, page % 12 + 1)

/**
 * Swipes between months, showing each month's own state under the finger.
 *
 * The ViewModel's month is the source of truth, so the pager's position isn't saved. Arrows change
 * [month] and the pager jumps there once that month has loaded; a swipe moves the pager and reports
 * the month through [onMonthSettled] when it stops.
 *
 * Arrows jump rather than slide because they're on the page. Touching a pager while it slides stops
 * it and swallows the tap, and the arrow has moved away, so quick taps would be lost. Waiting for
 * the figures means no empty page shows, and an arrow is always there for the next tap.
 *
 * Only the visible page is live, so at rest this does the same database work as a single month. A
 * neighbour starts when a swipe reveals it, and one seen before has its figures at once.
 *
 * A month that's been left starts afresh, at the top of its list, so the month's name shows. Only
 * leaving does that: a swipe that springs back, an editor or a rotation keeps the list's position.
 *
 * @param pageState gives the state to show for a month; see [MonthStates].
 * @param loading the state a month has until it loads.
 */
@Composable
fun <T> MonthPager(
    month: YearMonth,
    onMonthSettled: (YearMonth) -> Unit,
    pageState: (YearMonth) -> StateFlow<T>,
    loading: T,
    modifier: Modifier = Modifier,
    page: @Composable (T) -> Unit,
) {
    val pager = remember { PagerState(currentPage = month.toPage()) { PAGE_COUNT } }
    val settled by rememberUpdatedState(onMonthSettled)
    val stateFor by rememberUpdatedState(pageState)
    // By page. A new count is a new key around the page, which starts its saved state afresh.
    var timesLeft by rememberSaveable { mutableStateOf(emptyMap<Int, Int>()) }

    // Nothing to do after a swipe, when the pager is already there. It can't take over from a
    // finger either: a drag has the higher scroll priority, so this is cancelled instead.
    LaunchedEffect(month) {
        stateFor(month).first { it != loading }
        val target = month.toPage()
        if (pager.currentPage != target || pager.currentPageOffsetFraction != 0f) pager.scrollToPage(target)
    }

    // Not `settledPage`: that changes when a drag hands over to its fling, before the pager stops.
    LaunchedEffect(pager) {
        var reported = pager.currentPage
        snapshotFlow { pager.currentPage.takeUnless { pager.isScrollInProgress } }.collectLatest { page ->
            if (page == null) return@collectLatest
            // That handover leaves the pager idle for an instant on a page it may only be passing.
            // A frame later it's moving again.
            withFrameNanos { }
            if (page != reported && !pager.isScrollInProgress) {
                timesLeft = timesLeft + (reported to (timesLeft[reported] ?: 0) + 1)
                reported = page
                settled(monthOf(page))
            }
        }
    }

    HorizontalPager(state = pager, modifier = modifier, beyondViewportPageCount = 0, key = { it }) { index ->
        val state by remember(index) { stateFor(monthOf(index)) }.collectAsStateWithLifecycle()
        key(timesLeft[index]) { page(state) }
    }
}
