package lv.kristapsbe.budzets.ui.expenses

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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Expense
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

data class ExpenseRow(
    val id: ExpenseId,
    val amount: Money,
    val categoryName: String,
    val isArchivedCategory: Boolean,
    val note: String?,
)

data class DayGroup(val date: LocalDate, val total: Money, val rows: List<ExpenseRow>)

data class CategoryChip(val id: CategoryId, val name: String)

sealed interface TransactionsUiState {
    data object Loading : TransactionsUiState
    data class Error(val message: String?) : TransactionsUiState
    data class Ready(
        val month: YearMonth,
        val isCurrentMonth: Boolean,
        val today: LocalDate,
        val currency: CurrencySpec,
        val chips: List<CategoryChip>,
        val selectedCategory: CategoryId?,
        val query: String,
        /** Newest day first; within a day, newest entry first. */
        val days: List<DayGroup>,
        /** Of the filtered expenses. Unfiltered, it equals the overview's spent for [month]. */
        val total: Money,
        val count: Int,
        val hasCategories: Boolean,
    ) : TransactionsUiState {
        val selectedCategoryName: String? get() = chips.firstOrNull { it.id == selectedCategory }?.name
    }
}

sealed interface DeleteEvent {
    data class Deleted(val expense: Expense) : DeleteEvent
    data object RestoreFailed : DeleteEvent
}

/**
 * One month of expenses, filterable by category and note text. The filters live in
 * [SavedStateHandle]; `month` ("YYYY-MM") and `categoryId` double as the route arguments of
 * [lv.kristapsbe.budzets.ui.navigation.CategorySpendingRoute], so a drill-down starts filtered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val savedState: SavedStateHandle,
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedMonth = savedState.getStateFlow<String?>(KEY_MONTH, null)
    private val selectedCategory = savedState.getStateFlow<String?>(KEY_CATEGORY, null)
    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val reload = MutableStateFlow(0)

    private val _deleteEvent = MutableStateFlow<DeleteEvent?>(null)
    /** Drives the undo snackbar. Call [eventHandled] once it has been shown. */
    val deleteEvent: StateFlow<DeleteEvent?> = _deleteEvent.asStateFlow()

    private val month: YearMonth get() = savedState.get<String>(KEY_MONTH)?.let(YearMonth::parse) ?: YearMonth.now(clock)

    val uiState: StateFlow<TransactionsUiState> =
        combine(selectedMonth, reload) { month, _ -> month?.let(YearMonth::parse) ?: YearMonth.now(clock) }
            .flatMapLatest { month -> observeMonth(month) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState.Loading)

    private fun observeMonth(month: YearMonth) =
        combine(
            expenses.observeMonth(month),
            categories.categories,
            settings.currency,
            selectedCategory,
            query,
        ) { monthExpenses, allCategories, currency, categoryId, text ->
            val today = LocalDate.now(clock)
            val selected = categoryId?.let(::CategoryId)
            val byId = allCategories.associateBy { it.id }
            val needle = text.trim()
            val shown = monthExpenses.filter { e ->
                (selected == null || e.categoryId == selected) &&
                    (needle.isEmpty() || e.note?.contains(needle, ignoreCase = true) == true)
            }
            TransactionsUiState.Ready(
                month = month,
                isCurrentMonth = month == YearMonth.from(today),
                today = today,
                currency = currency,
                chips = chips(month, allCategories, monthExpenses, selected),
                selectedCategory = selected,
                query = text,
                days = shown.groupBy { it.date }.map { (date, dayExpenses) ->
                    DayGroup(date, dayExpenses.map { it.amount }.sumMoney(), dayExpenses.map { it.toRow(byId) })
                },
                total = shown.map { it.amount }.sumMoney(),
                count = shown.size,
                hasCategories = allCategories.isNotEmpty(),
            ) as TransactionsUiState
        }.catch { emit(TransactionsUiState.Error(it.message)) }

    /**
     * Categories active in [month] in display order, then archived ones that have spending in it.
     * The selected category is always included so the active filter stays visible.
     */
    private fun chips(month: YearMonth, all: List<Category>, monthExpenses: List<Expense>, selected: CategoryId?): List<CategoryChip> {
        val used = monthExpenses.map { it.categoryId }.toSet()
        return all.filter { it.toRef().isActiveIn(month) || it.id in used || it.id == selected }
            .map { CategoryChip(it.id, it.name) }
    }

    private fun Expense.toRow(byId: Map<CategoryId, Category>): ExpenseRow {
        val category = byId[categoryId]
        return ExpenseRow(id, amount, category?.name ?: "?", category?.isArchived == true, note)
    }

    fun previousMonth() { savedState[KEY_MONTH] = month.minusMonths(1).toString() }
    fun nextMonth() { savedState[KEY_MONTH] = month.plusMonths(1).toString() }
    fun goToCurrentMonth() { savedState[KEY_MONTH] = null }
    fun selectCategory(id: CategoryId?) { savedState[KEY_CATEGORY] = id?.value }
    fun setQuery(text: String) { savedState[KEY_QUERY] = text }
    fun retry() = reload.update { it + 1 }

    /** Deletes immediately; the screen offers undo via [undoDelete]. */
    fun delete(id: ExpenseId) {
        viewModelScope.launch {
            // null if it was already gone (e.g. a repeated swipe callback): nothing to undo.
            expenses.delete(id)?.let { _deleteEvent.value = DeleteEvent.Deleted(it) }
        }
    }

    fun undoDelete(event: DeleteEvent.Deleted) {
        _deleteEvent.compareAndSet(event, null)
        viewModelScope.launch {
            runCatching { expenses.restore(event.expense) }.onFailure { _deleteEvent.value = DeleteEvent.RestoreFailed }
        }
    }

    /** Clears [event] unless a newer one has replaced it meanwhile. */
    fun eventHandled(event: DeleteEvent) {
        _deleteEvent.compareAndSet(event, null)
    }

    companion object {
        /** Same names as [lv.kristapsbe.budzets.ui.navigation.CategorySpendingRoute]'s properties. */
        private const val KEY_MONTH = "month"
        private const val KEY_CATEGORY = "categoryId"
        private const val KEY_QUERY = "query"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                TransactionsViewModel(createSavedStateHandle(), container.expenses, container.categories, container.settings, container.clock)
            }
        }
    }
}
