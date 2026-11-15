package lv.kristapsbe.budzets.ui.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummaryCalculator
import lv.kristapsbe.budzets.domain.calculation.Pace
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.ClosingSchedule
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.ui.components.MonthStates
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class CloseStatus { OPEN, CLOSED, CHANGED_SINCE_CLOSING }

data class CategoryRow(val id: CategoryId, val name: String, val isArchived: Boolean, val summary: CategorySummary, val isDeleted: Boolean = false)

/** Q25: shown while there is data and no backup, or none for [DAYS] days. [daysSinceLast] is null if never. */
data class BackupReminder(val daysSinceLast: Long?) {
    companion object {
        const val DAYS = 30L

        fun due(lastBackupAt: Instant?, hasData: Boolean, now: Instant): BackupReminder? {
            if (!hasData) return null
            if (lastBackupAt == null) return BackupReminder(null)
            val days = Duration.between(lastBackupAt, now).toDays()
            return if (days >= DAYS) BackupReminder(days) else null
        }
    }
}

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
        /** The surplus pockets above zero, added up. Debt is kept apart in [debt], not netted. */
        val savedSurplus: Money,
        val hasCategories: Boolean,
        /** Budgeted past months not closed yet, newest first (Q15 reminder). */
        val unclosedPastMonths: List<YearMonth> = emptyList(),
        /** Past months and the current one can be closed (Q14); future ones can't. */
        val canClose: Boolean = false,
        val backupReminder: BackupReminder? = null,
        /** One-off boosts added to this month. They're in the unallocated surplus, not its budget. */
        val oneOffTotal: Money = Money.ZERO,
        /** What can be spent per day for the rest of the month (Q34); current month only. */
        val daily: DailyAllowance? = null,
        /**
         * What the surplus pockets below zero owe in all (Q43). In the month under way, while it is
         * open, [summary] already has it taken out; any other month's figures are without it.
         */
        val debt: Money = Money.ZERO,
    ) : OverviewUiState {

        /** Asked of the budget before debt: debt as large as the budget is "over", not "no budget". */
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
    /** For whether amounts exist (with none, no backup reminder) and for today's spending. */
    private val expenses: ExpenseRepository,
    private val clock: Clock,
) : ViewModel() {

    /** "YYYY-MM" in [SavedStateHandle] so it survives process death; null follows the current month. */
    private val savedMonth = savedState.getStateFlow<String?>(KEY_MONTH, null)
    private val reload = MutableStateFlow(0)

    private val month: YearMonth get() = savedState.get<String>(KEY_MONTH)?.let(YearMonth::parse) ?: YearMonth.now(clock)

    /** The month on screen, known at once, before its figures have loaded. */
    val selectedMonth: StateFlow<YearMonth> =
        savedMonth.map { it?.let(YearMonth::parse) ?: YearMonth.now(clock) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, month)

    private val months = MonthStates<OverviewUiState>(viewModelScope, OverviewUiState.Loading) { month ->
        reload.flatMapLatest { observeMonth(month) }
            // Mapping and calculations run off the main thread; only the finished state reaches it.
            .flowOn(Dispatchers.Default)
    }

    /** The state of any month, for its page in the month pager. */
    fun stateFor(month: YearMonth): StateFlow<OverviewUiState> = months[month]

    /** The selected month's state. It keeps the previous month until the new one has loaded. */
    val uiState: StateFlow<OverviewUiState> =
        selectedMonth.flatMapLatest { month -> stateFor(month).filterNot { it is OverviewUiState.Loading } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState.Loading)

    /** The month's figures with what follows from them and the ledger, so each is read once. */
    private data class MonthFigures(
        val summary: MonthSummary,
        val status: CloseStatus,
        val closed: Set<YearMonth>,
        /** Per pocket as posted. Deleted categories are merged once the categories are known. */
        val ledger: List<LedgerEntry>,
        val oneOffTotal: Money,
    )

    private fun observeMonth(month: YearMonth) =
        combine(
            // One subscription each: the close status and the surplus balances share the ledger and summary.
            combine(budgets.observeMonthSummary(month), surplus.ledger, surplus.closedMonths) { summary, ledger, closed ->
                val status = when {
                    month !in closed -> CloseStatus.OPEN
                    surplus.hasChangesSinceClosing(ledger, summary) -> CloseStatus.CHANGED_SINCE_CLOSING
                    else -> CloseStatus.CLOSED
                }
                val oneOffs = SurplusLedger.activeOneOffs(ledger).filter { it.targetMonth == month }
                MonthFigures(summary, status, closed, ledger, oneOffs.map { it.amount }.sumMoney())
            },
            categories.categories,
            combine(settings.currency, settings.lastBackupAt, expenses.hasMonetaryData) { currency, lastBackup, hasData ->
                currency to BackupReminder.due(lastBackup, hasData, clock.instant())
            },
            budgets.configs,
            // Per day rather than for today alone: this flow outlives the date it was built on, and
            // today is read at each emission. A past month is never current again, so it skips the query.
            if (month < YearMonth.now(clock)) flowOf(emptyMap<LocalDate, Money>()) else expenses.observeDailySpending(month),
        ) { (plain, status, closed, ledger, oneOffTotal), allCategories, (currency, backupReminder), configs, byDay ->
            val today = LocalDate.now(clock)
            val current = YearMonth.from(today)
            val deleted = allCategories.filter { it.isDeleted }.map { it.id }.toSet()
            val balances = SurplusLedger.balances(SurplusLedger.merged(ledger, deleted))
            // Q43: debt comes out of the month under way; every other month reads as it was.
            val summary = MonthSummaryCalculator.withDebt(plain, balances, current, closed)
            OverviewUiState.Ready(
                month = month,
                isCurrentMonth = month == current,
                currency = currency,
                summary = summary,
                pace = PaceCalculator.pace(summary, today),
                rows = rows(month, summary, allCategories, isClosed = month in closed),
                closeStatus = status,
                savedSurplus = SurplusLedger.saved(balances),
                hasCategories = allCategories.any { !it.isDeleted },
                unclosedPastMonths = ClosingSchedule.unclosedBudgetedPastMonths(
                    configs, allCategories.map { it.toRef() }, closed, current,
                ),
                canClose = month <= current,
                backupReminder = backupReminder,
                oneOffTotal = oneOffTotal,
                daily = PaceCalculator.dailyAllowance(summary, today, byDay[today] ?: Money.ZERO),
                debt = SurplusLedger.owed(balances),
            ) as OverviewUiState
        }.catch { emit(OverviewUiState.Error(it.message)) }

    /**
     * Categories active in [month] (in display order, even with no amount set), followed by any
     * archived category that still has spending or a boost in the month, or debt that comes out of
     * it. A closed month lists what it was closed with, however categories have been added,
     * archived or deleted since.
     */
    private fun rows(month: YearMonth, summary: MonthSummary, all: List<Category>, isClosed: Boolean): List<CategoryRow> {
        val active = all.filter { if (isClosed) it.id in summary.categories else it.toRef().isActiveIn(month) }
        val activeIds = active.map { it.id }.toSet()
        val byId = all.associateBy { it.id }
        val activeRows = active.map { c ->
            CategoryRow(c.id, c.name, false, summary.categories[c.id] ?: CategorySummary(c.id, null, Money.ZERO, Money.ZERO), isDeleted = !isClosed && c.isDeleted)
        }
        val otherRows = summary.categories.values.filter { it.categoryId !in activeIds }.map { s ->
            val category = byId[s.categoryId]
            CategoryRow(s.categoryId, category?.name ?: "?", true, s, isDeleted = category?.isDeleted == true)
        }
        return activeRows + otherRows
    }

    fun previousMonth() { savedState[KEY_MONTH] = month.minusMonths(1).toString() }
    fun nextMonth() { savedState[KEY_MONTH] = month.plusMonths(1).toString() }
    fun goToCurrentMonth() { savedState[KEY_MONTH] = null }

    /** From a swipe. The current month is stored as null, so it goes on following the date. */
    fun selectMonth(month: YearMonth) { savedState[KEY_MONTH] = month.takeUnless { it == YearMonth.now(clock) }?.toString() }

    fun retry() = reload.update { it + 1 }

    fun addStarterCategories(names: List<String>) {
        viewModelScope.launch { categories.createStarterCategories(names) }
    }

    companion object {
        private const val KEY_MONTH = "month"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                OverviewViewModel(createSavedStateHandle(), container.budgets, container.surplus, container.categories, container.settings, container.expenses, container.clock)
            }
        }
    }
}
