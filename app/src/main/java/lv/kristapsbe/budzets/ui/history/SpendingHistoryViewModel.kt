package lv.kristapsbe.budzets.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
import lv.kristapsbe.budzets.container
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.BurnDown
import lv.kristapsbe.budzets.domain.calculation.BurnDownCalculator
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.calculation.MonthSummaryCalculator
import lv.kristapsbe.budzets.domain.calculation.SpendingHistory
import lv.kristapsbe.budzets.domain.forecast.Estimate
import lv.kristapsbe.budzets.domain.forecast.ForecastBasis
import lv.kristapsbe.budzets.domain.forecast.ForecastRules
import lv.kristapsbe.budzets.domain.forecast.ForecastStack
import lv.kristapsbe.budzets.domain.forecast.ForecastStacks
import lv.kristapsbe.budzets.domain.forecast.MonthEndSurplus
import lv.kristapsbe.budzets.domain.forecast.SpendingForecaster
import lv.kristapsbe.budzets.domain.forecast.SurplusForecast
import lv.kristapsbe.budzets.domain.forecast.SurplusProjection
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.domain.surplus.SurplusHistory
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import java.time.Clock
import java.time.LocalDate
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

/**
 * The forecasts as the charts draw them (Q54–Q56).
 *
 * @property stacks the [ForecastRules.HORIZON] months after the current one, stacked by band.
 * @property currentEnd the month under way's likely total spending at its end; null when today
 *   isn't in it.
 * @property surplus the same months as [stacks], as the surplus would likely see them.
 * @property monthEnd where the month under way would likely leave the surplus (Q55).
 */
data class ChartForecast(
    val basis: ForecastBasis,
    val stacks: List<ForecastStack>,
    val currentEnd: Estimate?,
    val surplus: List<SurplusProjection>,
    val monthEnd: MonthEndSurplus?,
) {
    val months: List<YearMonth> get() = stacks.map { it.month }
}

sealed interface SpendingHistoryUiState {
    data object Loading : SpendingHistoryUiState
    data class Error(val message: String?) : SpendingHistoryUiState
    data class Ready(
        val currency: CurrencySpec,
        /**
         * Oldest first, from the first month with spending or a close to [currentMonth]; never empty,
         * since [currentMonth] is always there.
         */
        val months: List<MonthStack>,
        /** The same months as [months], in the same order, as the surplus sees them (Q39–Q42). */
        val surplus: List<MonthSurplus>,
        /** In display order, the folded band last. */
        val series: List<ChartSeries>,
        val currentMonth: YearMonth,
        /** The month whose breakdown is shown. */
        val selectedMonth: YearMonth?,
        /** The [ChartSeries.key] whose band stands out, or null. */
        val highlighted: String?,
        /** [selectedMonth]'s budget day by day (Q50); null when it has no budget or hasn't started. */
        val burnDown: BurnDown? = null,
        /** The coming months after [currentMonth] (Q54); null with too little history for one. */
        val forecast: ChartForecast? = null,
        /** Finished months with spending so far, for saying when forecasts start. */
        val finishedMonths: Int = 0,
    ) : SpendingHistoryUiState {
        val selected: MonthStack? get() = months.firstOrNull { it.month == selectedMonth }
        val selectedSurplus: MonthSurplus? get() = surplus.firstOrNull { it.month == selectedMonth }
        val selectedForecast: ForecastStack? get() = forecast?.stacks?.firstOrNull { it.month == selectedMonth }
        val selectedProjection: SurplusProjection? get() = forecast?.surplus?.firstOrNull { it.month == selectedMonth }

        /** The real months, then the forecast ones: what the month charts lay out. */
        val chartMonths: List<YearMonth> get() = months.map { it.month } + forecast?.months.orEmpty()
        fun seriesFor(id: CategoryId?): ChartSeries? = series.firstOrNull { it.id == id }
    }
}

/**
 * Spending (Q35–Q38) and surplus (Q39–Q42) by month, the selected month's burn-down (Q50–Q52) and
 * the forecasts for the coming months (Q54–Q56), for the charts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingHistoryViewModel(
    private val savedState: SavedStateHandle,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val surplus: SurplusRepository,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
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
        /** Every month's burn-down; small enough (a point a day) to work out for all of them at once. */
        val burnDowns: Map<YearMonth, BurnDown?>,
        val forecast: ChartForecast?,
        val finishedMonths: Int,
    )

    private val chart: Flow<Result<Chart>> = reload.flatMapLatest {
        combine(
            // With the coming months' plans for the forecasts (Q54).
            budgets.observeMonthSummaries(currentMonth, planThrough = currentMonth.plusMonths(ForecastRules.HORIZON.toLong())),
            categories.categories,
            settings.currency,
            // The raw entries, as closes posted them; the total doesn't depend on deleted pockets.
            combine(surplus.ledger, surplus.closedMonths, surplus.boosts) { ledger, closed, boosts -> Triple(ledger, closed, boosts) },
            expenses.dailySpending,
        ) { allSummaries, all, currency, (ledger, closed, boosts), daily ->
            val summaries = allSummaries.filter { it.month <= currentMonth }
            val planned = allSummaries.filter { it.month > currentMonth }.associateBy { it.month }
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
            // Debt comes out of the month under way as on the Overview (Q43), so its burn-down ends
            // at the Overview's "left". Balances count deleted categories' pockets as Unallocated.
            val deleted = all.filter { it.isDeleted }.map { it.id }.toSet()
            val balances = SurplusLedger.balances(SurplusLedger.merged(ledger, deleted))
            val today = LocalDate.now(clock)
            val dailyByMonth = daily.entries.groupBy({ YearMonth.from(it.key) }, { it.key to it.value })
            val boostsByMonth = boosts.groupBy { it.targetMonth }
            val surplusMonths = SurplusHistory.months(summaries, ledger, closed)

            // Q54–Q56: from each finished month's spending per category.
            val history = summaries.associate { summary -> summary.month to summary.categories.mapValues { it.value.spent } }
            val forecast = SpendingForecaster.forecast(history, daily, all.map { it.toRef() }, currentMonth, today)
            val chartForecast = forecast?.let { f ->
                val current = surplusMonths.lastOrNull()?.takeIf { it.month == currentMonth }
                // Q55: the coming months' total runs on from where this month will likely end.
                val monthEnd = f.monthEnd?.let { end -> current?.let { SurplusForecast.monthEnd(it, end.rest) } }
                val start = monthEnd?.runningTotal ?: current?.runningTotal ?: Money.ZERO
                ChartForecast(
                    basis = f.basis,
                    stacks = ForecastStacks.build(f, stacks.series.toSet(), displayOrder, planned),
                    currentEnd = f.monthEnd?.total,
                    surplus = SurplusForecast.months(planned, f, ledger, start),
                    monthEnd = monthEnd,
                )
            }

            val burnDowns = summaries.associate { summary ->
                val month = summary.month
                val carried = MonthSummaryCalculator.withDebt(summary, balances, YearMonth.from(today), closed)
                val burnDown = BurnDownCalculator.of(carried, boostsByMonth[month].orEmpty(), dailyByMonth[month].orEmpty().toMap(), today, clock.zone)
                val end = forecast?.monthEnd
                month to if (burnDown != null && end != null) burnDown.withProjection(end) else burnDown
            }
            Result.success(
                Chart(
                    currency, stacks.months, series, surplusMonths, burnDowns, chartForecast,
                    SpendingForecaster.finishedMonths(history, currentMonth),
                ),
            )
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
                    val months = chart.months.map { it.month } + chart.forecast?.months.orEmpty()
                    val chosen = month?.let(YearMonth::parse)?.takeIf { it in months }
                    val selected = chosen ?: chart.months.lastOrNull()?.month
                    SpendingHistoryUiState.Ready(
                        currency = chart.currency,
                        months = chart.months,
                        surplus = chart.surplus,
                        series = chart.series,
                        currentMonth = currentMonth,
                        selectedMonth = selected,
                        highlighted = highlight?.takeIf { key -> chart.series.any { it.key == key } },
                        burnDown = chart.burnDowns[selected],
                        forecast = chart.forecast,
                        finishedMonths = chart.finishedMonths,
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
                SpendingHistoryViewModel(
                    createSavedStateHandle(),
                    container.budgets,
                    container.categories,
                    container.settings,
                    container.surplus,
                    container.expenses,
                    container.clock,
                )
            }
        }
    }
}
