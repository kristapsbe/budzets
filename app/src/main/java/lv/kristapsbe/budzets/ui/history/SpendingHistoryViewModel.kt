package lv.kristapsbe.budzets.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.calculation.SpendingHistory
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.domain.surplus.SurplusHistory
import java.time.Clock
import java.time.YearMonth

/**
 * One band of the chart: a category, or the categories folded together when there are more than
 * the palette has colours ([id] null).
 *
 * @property slot the category's colour in the chart palette; -1 for the folded band.
 */
data class ChartSeries(
    val id: CategoryId?,
    val name: String,
    val isArchived: Boolean,
    val isDeleted: Boolean,
    val slot: Int,
) {
    val key: String get() = seriesKey(id)
}

/** A series' key for selection state; the folded band has one of its own. */
fun seriesKey(id: CategoryId?): String = id?.value ?: OTHERS_KEY

private const val OTHERS_KEY = "*others"

sealed interface SpendingHistoryUiState {
    data object Loading : SpendingHistoryUiState
    data class Error(val message: String?) : SpendingHistoryUiState
    data class Ready(
        val currency: CurrencySpec,
        /** Oldest first, from the first month with spending to [currentMonth]; empty without expenses. */
        val months: List<MonthStack>,
        /** The same months as [months], in the same order, as the surplus sees them (Q39–Q42). */
        val surplus: List<MonthSurplus>,
        /** In display order, the folded band last. */
        val series: List<ChartSeries>,
        val currentMonth: YearMonth,
        /** The month whose breakdown is shown; null only when [months] is empty. */
        val selectedMonth: YearMonth?,
        /** The [ChartSeries.key] whose band stands out, or null. */
        val highlighted: String?,
    ) : SpendingHistoryUiState {
        val selected: MonthStack? get() = months.firstOrNull { it.month == selectedMonth }
        val selectedSurplus: MonthSurplus? get() = surplus.firstOrNull { it.month == selectedMonth }
        fun seriesFor(id: CategoryId?): ChartSeries? = series.firstOrNull { it.id == id }
    }
}

/** Spending (Q35–Q38) and surplus (Q39–Q42) by month, for the charts. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingHistoryViewModel(
    private val savedState: SavedStateHandle,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val surplus: SurplusRepository,
    clock: Clock,
) : ViewModel() {

    /** Fixed for the screen's life, as the Overview's current month is between changes. */
    private val currentMonth = YearMonth.now(clock)
    private val reload = MutableStateFlow(0)

    /** The months, bands and surplus results, without the selection. */
    private data class Chart(
        val currency: CurrencySpec,
        val months: List<MonthStack>,
        val series: List<ChartSeries>,
        val surplus: List<MonthSurplus>,
    )

    private val chart: Flow<Result<Chart>> = reload.flatMapLatest {
        combine(
            budgets.observeMonthSummaries(currentMonth),
            categories.categories,
            settings.currency,
            // The raw entries, as closes posted them; the total doesn't depend on deleted pockets.
            surplus.ledger,
            surplus.closedMonths,
        ) { summaries, all, currency, ledger, closed ->
            val displayOrder = all.withIndex().associate { (index, category) -> category.id to index }
            val stacks = SpendingHistory.stacks(summaries, displayOrder)
            val byId = all.associateBy { it.id }
            val series = stacks.series.mapIndexed { slot, id ->
                val category = byId[id]
                ChartSeries(
                    id = id,
                    name = category?.name ?: "?",
                    isArchived = category != null && !category.toRef().isActiveIn(currentMonth),
                    isDeleted = category?.isDeleted == true,
                    slot = slot,
                )
            } + listOfNotNull(if (stacks.hasOthers) ChartSeries(null, "", isArchived = false, isDeleted = false, slot = -1) else null)
            Result.success(Chart(currency, stacks.months, series, SurplusHistory.months(summaries, ledger, closed, currentMonth)))
        }
            // The stacking runs off the main thread; only the finished state reaches it.
            .flowOn(Dispatchers.Default)
            .catch { emit(Result.failure(it)) }
    }

    val uiState: StateFlow<SpendingHistoryUiState> =
        combine(
            chart,
            savedState.getStateFlow<String?>(KEY_MONTH, null),
            savedState.getStateFlow<String?>(KEY_HIGHLIGHT, null),
        ) { result, month, highlight ->
            result.fold(
                onSuccess = { chart ->
                    val chosen = month?.let(YearMonth::parse)?.takeIf { m -> chart.months.any { it.month == m } }
                    SpendingHistoryUiState.Ready(
                        currency = chart.currency,
                        months = chart.months,
                        surplus = chart.surplus,
                        series = chart.series,
                        currentMonth = currentMonth,
                        selectedMonth = chosen ?: chart.months.lastOrNull()?.month,
                        highlighted = highlight?.takeIf { key -> chart.series.any { it.key == key } },
                    )
                },
                onFailure = { SpendingHistoryUiState.Error(it.message) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingHistoryUiState.Loading)

    fun selectMonth(month: YearMonth) { savedState[KEY_MONTH] = month.toString() }

    /** Highlights the band with [key], or clears the highlight if it is already that one. */
    fun toggleHighlight(key: String) {
        savedState[KEY_HIGHLIGHT] = key.takeUnless { savedState.get<String>(KEY_HIGHLIGHT) == it }
    }

    fun retry() = reload.update { it + 1 }

    companion object {
        private const val KEY_MONTH = "month"
        private const val KEY_HIGHLIGHT = "highlight"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                SpendingHistoryViewModel(
                    createSavedStateHandle(),
                    container.budgets,
                    container.categories,
                    container.settings,
                    container.surplus,
                    container.clock,
                )
            }
        }
    }
}
