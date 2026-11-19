package lv.kristapsbe.budzets.ui.budgets

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.container
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.BudgetPlanning
import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlan
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlanRule
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.surplus.ClosingSchedule
import lv.kristapsbe.budzets.ui.components.MonthStates
import lv.kristapsbe.budzets.ui.components.SelectedMonth
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
    /** Deleted, but still in the plan of this earlier open month. It can't be changed any more. */
    val isDeleted: Boolean = false,
) {
    /** Whether it can be dragged, or dropped on: categories that aren't archived, when there are two. */
    val canMove: Boolean get() = canMoveUp || canMoveDown
}

data class ArchivedRow(val id: CategoryId, val name: String, val archivedFrom: YearMonth)

enum class BudgetsMessage { DELETED, ACTION_FAILED }

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
 * month an edit starts from. A closed month is read-only and shows the budgets it was closed with.
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

    private val reload = MutableStateFlow(0)

    private val _message = MutableStateFlow<BudgetsMessage?>(null)
    /** One-off feedback for a snackbar. Call [messageShown] once shown. */
    val message: StateFlow<BudgetsMessage?> = _message.asStateFlow()

    private val selection = SelectedMonth(savedState, clock, viewModelScope)

    /** The month on screen, known at once, before its budgets have loaded. */
    val selectedMonth: StateFlow<YearMonth> = selection.flow

    private val months = MonthStates<BudgetsUiState>(viewModelScope, BudgetsUiState.Loading) { month ->
        reload.flatMapLatest { observeMonth(month) }.flowOn(Dispatchers.Default)
    }

    /** The state of any month, for its page in the month pager. */
    fun stateFor(month: YearMonth): StateFlow<BudgetsUiState> = months[month]

    /** The selected month's state. It keeps the previous month until the new one has loaded. */
    val uiState: StateFlow<BudgetsUiState> = months.following(selectedMonth)

    private fun observeMonth(month: YearMonth): Flow<BudgetsUiState> =
        surplus.closedMonths.map { month in it }.distinctUntilChanged()
            .flatMapLatest { isClosed -> if (isClosed) observeClosed(month) else observeOpen(month) }
            .catch { emit(BudgetsUiState.Error(it.message)) }

    private fun observeOpen(month: YearMonth): Flow<BudgetsUiState> =
        combine(budgets.configs, categories.categories, settings.currency) { configs, all, currency -> open(month, configs, all, currency) }

    private fun observeClosed(month: YearMonth): Flow<BudgetsUiState> =
        combine(budgets.observeMonthSummary(month), categories.categories, settings.currency) { summary, all, currency -> closed(summary, all, currency) }

    /**
     * The snapshot keeps each budget's monthly amount but not its daily or weekly targets, so
     * that's what a closed month shows. Categories added since aren't in it.
     */
    private fun closed(summary: MonthSummary, all: List<Category>, currency: CurrencySpec): BudgetsUiState.Ready {
        val month = summary.month
        fun line(base: Money?): BudgetLine {
            val target = base?.let { BudgetTarget(monthlyMinor = it.minor) }
            return BudgetLine(target?.let { MonthlyPlanRule.plan(it, month) }, target, since = null, nextChange = null)
        }
        return BudgetsUiState.Ready(
            month = month,
            isCurrentMonth = month == YearMonth.now(clock),
            isClosed = true,
            currency = currency,
            overall = line(summary.overallBase.takeUnless { summary.overallDerived }),
            overallBase = summary.overallBase,
            overallDerived = summary.overallDerived,
            categoryTotal = summary.categoryBaseTotal,
            exceedsOverall = summary.categoryPlansExceedOverall,
            unallocated = summary.unallocatedBase,
            categories = all.filter { it.id in summary.categories }.map { c ->
                CategoryBudgetRow(c.id, c.name, line(summary.categories.getValue(c.id).base), archivedFrom = null, canMoveUp = false, canMoveDown = false)
            },
            archived = emptyList(),
        )
    }

    private fun open(month: YearMonth, configs: List<BudgetConfig>, all: List<Category>, currency: CurrencySpec): BudgetsUiState.Ready {
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
                isDeleted = c.isDeleted,
            )
        }
        val archived = all.filter { !it.isDeleted && !it.toRef().isActiveIn(month) }.map { ArchivedRow(it.id, it.name, it.archivedFrom!!) }
        val categoryTotal = BudgetPlanning.categoryTotal(resolved)
        val unallocated = resolved.overallBase
            ?.takeIf { !resolved.overallDerived }
            ?.let { it - categoryTotal }
            ?.takeIf { it.isPositive } ?: Money.ZERO

        return BudgetsUiState.Ready(
            month = month,
            isCurrentMonth = month == YearMonth.now(clock),
            isClosed = false,
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

    fun previousMonth() = selection.previous()
    fun nextMonth() = selection.next()
    fun goToCurrentMonth() = selection.current()

    fun selectMonth(month: YearMonth) = selection.select(month)

    fun retry() = reload.update { it + 1 }

    fun addStarterCategories(names: List<String>) = launchAction { categories.createStarterCategories(names) }
    fun addCategory(name: String) = launchAction { categories.create(name) }
    fun renameCategory(id: CategoryId, name: String) = launchAction { categories.rename(id, name) }

    /** Archived from the first open month: earlier months keep the category in their plans. */
    fun archive(id: CategoryId) = launchAction {
        categories.archive(id, ClosingSchedule.firstOpenMonth(YearMonth.now(clock), surplus.closedMonths.first()))
    }
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

    /** Saves the order left by a drag, of the categories that can move. Any others stay after them. */
    fun reorder(order: List<CategoryId>) = launchAction { categories.reorder(order) }

    fun delete(id: CategoryId) = launchAction {
        surplus.deleteCategory(id)
        _message.value = BudgetsMessage.DELETED
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
        val Factory = viewModelFactory {
            initializer {
                BudgetsViewModel(createSavedStateHandle(), container.budgets, container.categories, container.surplus, container.settings, container.clock)
            }
        }
    }
}
