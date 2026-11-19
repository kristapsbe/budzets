package lv.kristapsbe.budzets.ui.components

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
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

    /** The state of the month [selected] names. It keeps the previous month's until the new one has loaded. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun following(selected: Flow<YearMonth>): StateFlow<T> =
        selected.flatMapLatest { month -> get(month).filterNot { it == loading } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), loading)
}

/**
 * The month a [MonthPager] screen shows, in [savedState] as "YYYY-MM" so it survives process death.
 * The current month is stored as null, so it goes on following the date. The key is `month`, the
 * name routes give a month argument (`CategorySpendingRoute`), so a screen opened on one starts there.
 */
class SelectedMonth(private val savedState: SavedStateHandle, private val clock: Clock, scope: CoroutineScope) {

    private val value: YearMonth get() = savedState.get<String>(KEY)?.let(YearMonth::parse) ?: YearMonth.now(clock)

    /** The month on screen, known at once, before its figures have loaded. */
    val flow: StateFlow<YearMonth> =
        savedState.getStateFlow<String?>(KEY, null)
            .map { it?.let(YearMonth::parse) ?: YearMonth.now(clock) }
            .stateIn(scope, SharingStarted.Eagerly, value)

    fun previous() { savedState[KEY] = value.minusMonths(1).toString() }
    fun next() { savedState[KEY] = value.plusMonths(1).toString() }
    fun current() { savedState[KEY] = null }

    /** From a swipe. The current month is stored as null, so it goes on following the date. */
    fun select(month: YearMonth) { savedState[KEY] = month.takeUnless { it == YearMonth.now(clock) }?.toString() }

    private companion object {
        const val KEY = "month"
    }
}
