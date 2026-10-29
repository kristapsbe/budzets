package lv.kristapsbe.budzets.ui.close

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.MonthCloseState
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.ClosureError
import lv.kristapsbe.budzets.ui.surplus.PocketLabel
import lv.kristapsbe.budzets.ui.surplus.label
import lv.kristapsbe.budzets.ui.surplus.pocketOrder
import java.time.Clock
import java.time.Instant
import java.time.YearMonth

enum class MonthStatus {
    /** Never closed, or reopened. */
    OPEN,
    CLOSED,

    /** Closed, but expenses or budgets changed since; closing again posts the difference. */
    CHANGED,
}

/**
 * One pocket's part in the month's closure.
 *
 * @property summary the category's budget and spending; null for the unallocated pocket.
 * @property effect the month's total effect on the pocket once closed (credit > 0, debit < 0).
 * @property pending what closing now would post; differs from [effect] only when re-closing.
 */
data class CloseLine(
    val label: PocketLabel,
    val summary: CategorySummary?,
    val effect: Money,
    val pending: Money,
) {
    /** Spending in a category with no budget (and no boost) can only become a debit. */
    val isUnbudgetedSpending: Boolean get() = summary != null && summary.base == null && !summary.boost.isPositive && summary.spent.isPositive
}

sealed interface MonthCloseUiState {
    data object Loading : MonthCloseUiState
    data class Error(val message: String?) : MonthCloseUiState
    data class Ready(
        val month: YearMonth,
        val currency: CurrencySpec,
        val isCurrentMonth: Boolean,
        /** Future months can't be closed. */
        val canClose: Boolean,
        val status: MonthStatus,
        val closedAt: Instant?,
        val revision: Int?,
        val wasReopened: Boolean,
        val hasBudget: Boolean,
        val lines: List<CloseLine>,
        val netEffect: Money,
        val pendingNet: Money,
        val isWorking: Boolean,
    ) : MonthCloseUiState
}

sealed interface CloseEvent {
    data object Closed : CloseEvent
    data object Reopened : CloseEvent

    /**
     * Reopening would take these pockets to the given negative balances, because their surplus
     * was already moved or reserved.
     */
    data class ReopenRefused(val pockets: List<Pair<PocketLabel, Money>>) : CloseEvent
    data object Failed : CloseEvent
}

/** The month-end review: preview, close, close again, and reopen. Route argument: `month`. */
class MonthCloseViewModel(
    savedState: SavedStateHandle,
    private val surplus: SurplusRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val month: YearMonth = YearMonth.parse(requireNotNull(savedState.get<String>(ARG_MONTH)) { "month is required" })
    private val working = MutableStateFlow(false)
    private val _event = MutableStateFlow<CloseEvent?>(null)
    val event: StateFlow<CloseEvent?> = _event.asStateFlow()

    val uiState: StateFlow<MonthCloseUiState> =
        combine(surplus.observeCloseState(month), categories.categories, settings.currency, working) { state, all, currency, busy ->
            ready(state, all, currency, busy) as MonthCloseUiState
        }
            .catch { emit(MonthCloseUiState.Error(it.message)) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthCloseUiState.Loading)

    private fun ready(state: MonthCloseState, all: List<Category>, currency: CurrencySpec, busy: Boolean): MonthCloseUiState.Ready {
        val review = state.review
        val byId = all.associateBy { it.id }
        val pending = review.pending
        val pockets = (review.summary.categories.keys.map { SurplusPocket.Category(it) } + review.target.keys + review.recorded.keys)
            .distinct()
            .sortedWith(pocketOrder(all))
        val lines = pockets.map { pocket ->
            CloseLine(
                label = pocket.label(byId),
                summary = (pocket as? SurplusPocket.Category)?.let { review.summary.categories[it.id] },
                effect = review.target[pocket] ?: Money.ZERO,
                pending = pending[pocket] ?: Money.ZERO,
            )
        }
        val latest = state.latest
        val current = YearMonth.now(clock)
        return MonthCloseUiState.Ready(
            month = month,
            currency = currency,
            isCurrentMonth = month == current,
            canClose = month <= current,
            status = when {
                latest == null || !latest.isClosed -> MonthStatus.OPEN
                pending.isNotEmpty() -> MonthStatus.CHANGED
                else -> MonthStatus.CLOSED
            },
            closedAt = latest?.closedAt,
            revision = latest?.revision,
            wasReopened = latest?.isClosed == false,
            hasBudget = review.summary.overallEffective.isPositive,
            lines = lines,
            netEffect = review.target.values.sumMoney(),
            pendingNet = pending.values.sumMoney(),
            isWorking = busy,
        )
    }

    /** First close, or close again after changes. */
    fun close() = run {
        when (surplus.closeMonth(month)) {
            is Outcome.Ok -> CloseEvent.Closed
            // Nothing new to post: the screen already shows the month as closed.
            is Outcome.Err -> null
        }
    }

    fun reopen() = run {
        when (val result = surplus.reopenMonth(month)) {
            is Outcome.Ok -> CloseEvent.Reopened
            is Outcome.Err -> when (val error = result.error) {
                is ClosureError.WouldGoNegative -> {
                    val byId = categories.categories.first().associateBy { it.id }
                    CloseEvent.ReopenRefused(error.pockets.map { (pocket, shortfall) -> pocket.label(byId) to shortfall })
                }
                else -> CloseEvent.Failed
            }
        }
    }

    fun eventHandled(event: CloseEvent) {
        _event.compareAndSet(event, null)
    }

    private fun run(action: suspend () -> CloseEvent?) {
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            val event = runCatching { action() }.getOrElse { CloseEvent.Failed }
            working.value = false
            event?.let { _event.value = it }
        }
    }

    companion object {
        /** Matches [lv.kristapsbe.budzets.ui.navigation.MonthCloseRoute]'s property. */
        const val ARG_MONTH = "month"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                MonthCloseViewModel(createSavedStateHandle(), container.surplus, container.categories, container.settings, container.clock)
            }
        }
    }
}
