package lv.kristapsbe.budzets.ui.budgets

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.BudgetPlanning
import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlan
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import java.time.Clock
import java.time.YearMonth

/**
 * One budget as it applies to the selected month.
 *
 * @property plan null when no budget is in force for the month.
 * @property target the targets of the version in force, for "€10.00/day" style summaries.
 * @property since when that version started, if before the selected month.
 * @property nextChange when a later version takes over, if one is scheduled.
 */
data class BudgetLine(
    val plan: MonthlyPlan?,
    val target: BudgetTarget?,
    val since: YearMonth?,
    val nextChange: YearMonth?,
)

data class CategoryBudgetRow(
    val id: CategoryId,
    val name: String,
    val budget: BudgetLine,
    /** Archived later than the selected month (still in that month's plans). */
    val archivedFrom: YearMonth?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class ArchivedRow(val id: CategoryId, val name: String, val archivedFrom: YearMonth)

enum class BudgetsMessage { DELETE_REFUSED, DELETED, ACTION_FAILED }

sealed interface BudgetsUiState {
    data object Loading : BudgetsUiState
    data class Error(val message: String?) : BudgetsUiState
    data class Ready(
        val month: YearMonth,
        val isCurrentMonth: Boolean,
        val isClosed: Boolean,
        val currency: CurrencySpec,
        val overall: BudgetLine,
        /** The overall plan, or the sum of category plans when [overallDerived]. */
        val overallBase: Money?,
        val overallDerived: Boolean,
        val categoryTotal: Money,
        val exceedsOverall: Boolean,
        /** Overall base not assigned to any category; becomes unallocated surplus at close. */
        val unallocated: Money,
        val categories: List<CategoryBudgetRow>,
        val archived: List<ArchivedRow>,
    ) : BudgetsUiState {
        val hasCategories: Boolean get() = categories.isNotEmpty() || archived.isNotEmpty()
    }
}

/**
 * Budgets for the selected month plus category management. Budget versions apply from the month
 * they're saved for (Q11), so the selected month ("YYYY-MM", in [SavedStateHandle]) is also the
 * month an edit starts from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModel(
    private val savedState: SavedStateHandle,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val surplus: SurplusRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedMonth = savedState.getStateFlow<String?>(KEY_MONTH, null)
    private val reload = MutableStateFlow(0)

    private val _message = MutableStateFlow<BudgetsMessage?>(null)
    /** One-off feedback for a snackbar. Call [messageShown] once shown. */
    val message: StateFlow<BudgetsMessage?> = _message.asStateFlow()

    private val month: YearMonth get() = savedState.get<String>(KEY_MONTH)?.let(YearMonth::parse) ?: YearMonth.now(clock)

    val uiState: StateFlow<BudgetsUiState> =
        combine(selectedMonth, reload) { month, _ -> month?.let(YearMonth::parse) ?: YearMonth.now(clock) }
            .flatMapLatest { month ->
                combine(budgets.configs, categories.categories, settings.currency, surplus.closedMonths) { configs, all, currency, closed ->
                    ready(month, configs, all, currency, month in closed) as BudgetsUiState
                }.catch { emit(BudgetsUiState.Error(it.message)) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState.Loading)

    private fun ready(month: YearMonth, configs: List<BudgetConfig>, all: List<Category>, currency: CurrencySpec, isClosed: Boolean): BudgetsUiState.Ready {
        val resolved = BudgetResolver.resolve(month, configs, all.map { it.toRef() })

        fun line(scope: BudgetScope, plan: MonthlyPlan?): BudgetLine {
            val config = BudgetResolver.configFor(configs, scope, month)
            return BudgetLine(
                plan = plan,
                target = config?.target?.takeIf { it.isConfigured },
                since = config?.effectiveFrom?.takeIf { it < month && plan != null },
                nextChange = BudgetPlanning.nextChangeAfter(configs, scope, month),
            )
        }

        // Order can only be changed among categories that aren't archived.
        val movable = all.filterNot { it.isArchived }.map { it.id }
        val rows = all.filter { it.toRef().isActiveIn(month) }.map { c ->
            val index = movable.indexOf(c.id)
            CategoryBudgetRow(
                id = c.id,
                name = c.name,
                budget = line(BudgetScope.Category(c.id), resolved.categoryPlans[c.id]),
                archivedFrom = c.archivedFrom,
                canMoveUp = index > 0,
                canMoveDown = index >= 0 && index < movable.lastIndex,
            )
        }
        val archived = all.filter { !it.toRef().isActiveIn(month) }.map { ArchivedRow(it.id, it.name, it.archivedFrom!!) }
        val categoryTotal = BudgetPlanning.categoryTotal(resolved)
        val unallocated = resolved.overallBase
            ?.takeIf { !resolved.overallDerived }
            ?.let { it - categoryTotal }
            ?.takeIf { it.isPositive } ?: Money.ZERO

        return BudgetsUiState.Ready(
            month = month,
            isCurrentMonth = month == YearMonth.now(clock),
            isClosed = isClosed,
            currency = currency,
            overall = line(BudgetScope.Overall, resolved.overallPlan),
            overallBase = resolved.overallBase,
            overallDerived = resolved.overallDerived,
            categoryTotal = categoryTotal,
            exceedsOverall = BudgetPlanning.categoryPlansExceedOverall(resolved),
            unallocated = unallocated,
            categories = rows,
            archived = archived,
        )
    }

    fun previousMonth() { savedState[KEY_MONTH] = month.minusMonths(1).toString() }
    fun nextMonth() { savedState[KEY_MONTH] = month.plusMonths(1).toString() }
    fun goToCurrentMonth() { savedState[KEY_MONTH] = null }
    fun retry() = reload.update { it + 1 }

    fun addStarterCategories(names: List<String>) = launchAction { categories.createStarterCategories(names) }
    fun addCategory(name: String) = launchAction { categories.create(name) }
    fun renameCategory(id: CategoryId, name: String) = launchAction { categories.rename(id, name) }

    /** Archived from the current month: earlier months keep the category in their plans. */
    fun archive(id: CategoryId) = launchAction { categories.archive(id) }
    fun unarchive(id: CategoryId) = launchAction { categories.unarchive(id) }

    /** Swaps [id] with its neighbour among the categories that aren't archived. */
    fun move(id: CategoryId, up: Boolean) = launchAction {
        val order = categories.categories.first().filterNot { it.isArchived }.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = if (up) from - 1 else from + 1
        if (from >= 0 && to in order.indices) {
            order[from] = order[to].also { order[to] = order[from] }
            categories.reorder(order)
        }
    }

    /** Only a category with no history can be deleted; otherwise it must stay archived. */
    fun delete(id: CategoryId) = launchAction {
        _message.value = if (categories.deleteIfUnused(id)) BudgetsMessage.DELETED else BudgetsMessage.DELETE_REFUSED
    }

    fun messageShown(message: BudgetsMessage) {
        _message.compareAndSet(message, null)
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _message.value = BudgetsMessage.ACTION_FAILED }
        }
    }

    companion object {
        private const val KEY_MONTH = "month"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                BudgetsViewModel(createSavedStateHandle(), container.budgets, container.categories, container.surplus, container.settings, container.clock)
            }
        }
    }
}
