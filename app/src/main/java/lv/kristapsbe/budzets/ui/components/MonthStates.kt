package lv.kristapsbe.budzets.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

/**
 * One shared state per month, for the pages of a [MonthPager].
 *
 * Each keeps its last value with nobody watching, as a ViewModel's `uiState` does. A page that comes
 * back after a rotation, an editor or a swipe away has its figures at once, so it never shows
 * Loading and its list can restore its scroll position. The upstream stops 5 s after the last page
 * leaves.
 *
 * Main thread only. Nothing is evicted: a session visits few months and each holds one state.
 */
class MonthStates<T>(
    private val scope: CoroutineScope,
    private val loading: T,
    private val observe: (YearMonth) -> Flow<T>,
) {
    private val states = HashMap<YearMonth, StateFlow<T>>()

    operator fun get(month: YearMonth): StateFlow<T> =
        states.getOrPut(month) { observe(month).stateIn(scope, SharingStarted.WhileSubscribed(5_000), loading) }
}
