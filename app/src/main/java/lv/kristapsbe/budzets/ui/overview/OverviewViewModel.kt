package lv.kristapsbe.budzets.ui.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.Pace
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.ClosingSchedule
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

enum class CloseStatus { OPEN, CLOSED, CHANGED_SINCE_CLOSING }

data class CategoryRow(val id: CategoryId, val name: String, val isArchived: Boolean, val summary: CategorySummary)

sealed interface OverviewUiState {
    data object Loading : OverviewUiState
    data class Error(val message: String?) : OverviewUiState
    data class Ready(
        val month: YearMonth,
        val isCurrentMonth: Boolean,
        val currency: CurrencySpec,
        val summary: MonthSummary,
        val pace: Pace,
        val rows: List<CategoryRow>,
        val closeStatus: CloseStatus,
        /** Net of all surplus pockets; may be negative after overspending. */
        val totalSurplus: Money,
        val hasCategories: Boolean,
        /** Budgeted past months not closed yet, newest first (Q15 reminder). */
        val unclosedPastMonths: List<YearMonth> = emptyList(),
        /** Past months and the current one can be closed (Q14); future ones can't. */
        val canClose: Boolean = false,
    ) : OverviewUiState {

        val hasBudget: Boolean get() = summary.overallEffective.isPositive
        val hasSpending: Boolean get() = summary.totalSpent.isPositive
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModel(
    private val savedState: SavedStateHandle,
    private val budgets: BudgetRepository,
    private val surplus: SurplusRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    /** "YYYY-MM" in [SavedStateHandle] so it survives process death; null follows the current month. */
    private val selectedMonth = savedState.getStateFlow<String?>(KEY_MONTH, null)
    private val reload = MutableStateFlow(0)

    private val month: YearMonth get() = savedState.get<String>(KEY_MONTH)?.let(YearMonth::parse) ?: YearMonth.now(clock)

    val uiState: StateFlow<OverviewUiState> =
        combine(selectedMonth, reload) { month, _ -> month?.let(YearMonth::parse) ?: YearMonth.now(clock) }
            .flatMapLatest { month -> observeMonth(month) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState.Loading)

    private fun observeMonth(month: YearMonth) =
        combine(
            combine(
                budgets.observeMonthSummary(month),
                surplus.closedMonths,
                surplus.observeHasChangesSinceClosing(month),
            ) { summary, closed, changed ->
                val status = when {
                    month !in closed -> CloseStatus.OPEN
                    changed -> CloseStatus.CHANGED_SINCE_CLOSING
                    else -> CloseStatus.CLOSED
                }
                Triple(summary, status, closed)
            },
            categories.categories,
            settings.currency,
            surplus.balances,
            budgets.configs,
        ) { (summary, status, closed), allCategories, currency, balances, configs ->
            val today = LocalDate.now(clock)
            OverviewUiState.Ready(
                month = month,
                isCurrentMonth = month == YearMonth.from(today),
                currency = currency,
                summary = summary,
                pace = PaceCalculator.pace(summary, today),
                rows = rows(month, summary, allCategories),
                closeStatus = status,
                totalSurplus = balances.values.sumMoney(),
                hasCategories = allCategories.isNotEmpty(),
                unclosedPastMonths = ClosingSchedule.unclosedBudgetedPastMonths(
                    configs, allCategories.map { it.toRef() }, closed, YearMonth.from(today),
                ),
                canClose = month <= YearMonth.from(today),
            ) as OverviewUiState
        }.catch { emit(OverviewUiState.Error(it.message)) }

    /**
     * Categories active in [month] (in display order, even without a budget), followed by any
     * archived category that still has spending or a boost in the month.
     */
    private fun rows(month: YearMonth, summary: MonthSummary, all: List<Category>): List<CategoryRow> {
        val active = all.filter { it.toRef().isActiveIn(month) }
        val activeIds = active.map { it.id }.toSet()
        val byId = all.associateBy { it.id }
        val activeRows = active.map { c ->
            CategoryRow(c.id, c.name, false, summary.categories[c.id] ?: CategorySummary(c.id, null, Money.ZERO, Money.ZERO))
        }
        val otherRows = summary.categories.values.filter { it.categoryId !in activeIds }.map { s ->
            CategoryRow(s.categoryId, byId[s.categoryId]?.name ?: "?", true, s)
        }
        return activeRows + otherRows
    }

    fun previousMonth() { savedState[KEY_MONTH] = month.minusMonths(1).toString() }
    fun nextMonth() { savedState[KEY_MONTH] = month.plusMonths(1).toString() }
    fun goToCurrentMonth() { savedState[KEY_MONTH] = null }
    fun retry() = reload.update { it + 1 }

    fun addStarterCategories(names: List<String>) {
        viewModelScope.launch { categories.createStarterCategories(names) }
    }

    companion object {
        private const val KEY_MONTH = "month"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                OverviewViewModel(createSavedStateHandle(), container.budgets, container.surplus, container.categories, container.settings, container.clock)
            }
        }
    }
}
