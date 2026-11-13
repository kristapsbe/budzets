package lv.kristapsbe.budzets.ui.overview

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.categoryName
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.MonthPager
import lv.kristapsbe.budzets.ui.components.MonthSelector
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName
import java.time.YearMonth

@Composable
fun OverviewScreen(
    onAddExpense: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenSurplus: () -> Unit,
    onOpenCategory: (CategoryId, YearMonth) -> Unit,
    onReviewClose: (YearMonth) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: OverviewViewModel = viewModel(factory = OverviewViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val month by viewModel.selectedMonth.collectAsStateWithLifecycle()
    // Marks the app as fully drawn once the numbers are on screen, for startup measurements.
    ReportDrawnWhen { state !is OverviewUiState.Loading }
    val starterNames = stringArrayResource(R.array.starter_categories).toList()
    OverviewContent(
        state = state,
        month = month,
        pageState = viewModel::stateFor,
        actions = OverviewActions(
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::goToCurrentMonth,
            onSelectMonth = viewModel::selectMonth,
            onRetry = viewModel::retry,
            onAddExpense = onAddExpense,
            onAddStarterCategories = { viewModel.addStarterCategories(starterNames) },
            onOpenBudgets = onOpenBudgets,
            onOpenSurplus = onOpenSurplus,
            onOpenCategory = onOpenCategory,
            onReviewClose = onReviewClose,
            onOpenSettings = onOpenSettings,
            onOpenHistory = onOpenHistory,
        ),
    )
}

data class OverviewActions(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onCurrentMonth: () -> Unit = {},
    val onSelectMonth: (YearMonth) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAddExpense: () -> Unit = {},
    val onAddStarterCategories: () -> Unit = {},
    val onOpenBudgets: () -> Unit = {},
    val onOpenSurplus: () -> Unit = {},
    val onOpenCategory: (CategoryId, YearMonth) -> Unit = { _, _ -> },
    val onReviewClose: (YearMonth) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
)

/**
 * @param state the selected month, for what surrounds the pages.
 * @param month the selected month, known before its state is.
 * @param pageState each page's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewContent(
    state: OverviewUiState,
    actions: OverviewActions,
    month: YearMonth = (state as? OverviewUiState.Ready)?.month ?: YearMonth.now(),
    pageState: (YearMonth) -> StateFlow<OverviewUiState> = { MutableStateFlow(state) },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_overview)) },
                actions = {
                    IconButton(onClick = actions.onOpenHistory) {
                        Icon(painterResource(R.drawable.ic_stacked_chart), contentDescription = stringResource(R.string.spending_by_month))
                    }
                },
            )
        },
        floatingActionButton = {
            // Hidden until categories exist: an expense needs one.
            if (state is OverviewUiState.Ready && state.hasCategories) {
                // Content-slot overload: always expanded, so the label is visible and in the semantics tree.
                ExtendedFloatingActionButton(onClick = actions.onAddExpense) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_expense))
                }
            }
        },
    ) { padding ->
        MonthPager(month, actions.onSelectMonth, pageState, OverviewUiState.Loading, Modifier.fillMaxSize()) { page ->
            when (page) {
                OverviewUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
                is OverviewUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
                }
                is OverviewUiState.Ready -> ReadyContent(page, actions, padding)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: OverviewUiState.Ready, actions: OverviewActions, padding: PaddingValues) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            // room for the FAB
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MonthSelector(state.month, state.isCurrentMonth, actions.onPreviousMonth, actions.onNextMonth, actions.onCurrentMonth)
        }

        if (!state.hasCategories) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.empty_no_categories_title),
                    body = stringResource(R.string.empty_no_categories_body),
                    action = stringResource(R.string.add_starter_categories),
                    onAction = actions.onAddStarterCategories,
                )
            }
            return@LazyColumn
        }

        // Q15: remind about budgeted past months that still need closing.
        if (state.isCurrentMonth && state.unclosedPastMonths.isNotEmpty()) {
            item {
                val latest = state.unclosedPastMonths.first()
                val earlier = state.unclosedPastMonths.size - 1
                Banner(
                    Icons.Filled.Info,
                    if (earlier == 0) stringResource(R.string.reminder_unclosed, latest.displayName(locale))
                    else pluralStringResource(R.plurals.reminder_unclosed_more, earlier, latest.displayName(locale), earlier),
                    action = stringResource(R.string.review_and_close),
                    onAction = { actions.onReviewClose(latest) },
                )
            }
        }
        // Q25: a nudge when there is data and no recent backup.
        if (state.isCurrentMonth && state.backupReminder != null) {
            item {
                val days = state.backupReminder.daysSinceLast
                Banner(
                    Icons.Filled.Info,
                    if (days == null) stringResource(R.string.reminder_backup_never)
                    else pluralStringResource(R.plurals.reminder_backup_old, days.toInt(), days.toInt()),
                    action = stringResource(R.string.reminder_backup_action),
                    onAction = actions.onOpenSettings,
                )
            }
        }
        when (state.closeStatus) {
            CloseStatus.CLOSED -> item {
                Banner(
                    Icons.Filled.Lock,
                    stringResource(R.string.month_closed),
                    action = stringResource(R.string.view_closing),
                    onAction = { actions.onReviewClose(state.month) },
                )
            }
            CloseStatus.CHANGED_SINCE_CLOSING -> item {
                Banner(
                    Icons.Filled.Warning,
                    stringResource(R.string.month_changed_since_closing),
                    warning = true,
                    action = stringResource(R.string.review_and_close),
                    onAction = { actions.onReviewClose(state.month) },
                )
            }
            // A past month left open gets the same prompt; the current one has a quieter link below.
            CloseStatus.OPEN -> if (state.canClose && !state.isCurrentMonth) {
                item {
                    Banner(
                        Icons.Filled.Info,
                        stringResource(R.string.month_not_closed),
                        action = stringResource(R.string.review_and_close),
                        onAction = { actions.onReviewClose(state.month) },
                    )
                }
            }
        }
        if (state.oneOffTotal.isPositive) {
            item { Banner(Icons.Filled.Info, stringResource(R.string.overview_one_off, money.format(state.oneOffTotal))) }
        }
        if (state.summary.categoryPlansExceedOverall) {
            item {
                Banner(
                    Icons.Filled.Warning,
                    stringResource(
                        R.string.categories_exceed_overall,
                        money.format(state.summary.categoryBaseTotal),
                        money.format(state.summary.overallBase ?: Money.ZERO),
                    ),
                    warning = true,
                )
            }
        }

        if (state.hasBudget) {
            item { SummaryCard(state, money) }
        } else {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.empty_no_budget_title),
                    body = stringResource(R.string.empty_no_budget_body),
                    action = stringResource(R.string.set_up_budgets),
                    onAction = actions.onOpenBudgets,
                    footer = if (state.hasSpending) stringResource(R.string.spent_amount, money.format(state.summary.totalSpent)) else null,
                )
            }
        }

        item { SurplusShortcut(state.totalSurplus, money, actions.onOpenSurplus) }

        if (!state.hasSpending) {
            item {
                Text(
                    stringResource(R.string.empty_no_spending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                stringResource(R.string.categories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
        }
        items(state.rows, key = { it.id.value }) { row ->
            CategoryRowItem(row, money, onClick = { actions.onOpenCategory(row.id, state.month) })
        }

        if (state.isCurrentMonth && state.closeStatus == CloseStatus.OPEN) {
            item {
                TextButton(onClick = { actions.onReviewClose(state.month) }) { Text(stringResource(R.string.review_and_close_this_month)) }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: OverviewUiState.Ready, money: MoneyFormatter) {
    val summary = state.summary
    val remaining = summary.overallRemaining
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (remaining.isNegative) stringResource(R.string.over_by_amount, money.format(-remaining))
                else stringResource(R.string.remaining_amount, money.format(remaining)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (remaining.isNegative) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            Text(
                stringResource(R.string.spent_of_budget, money.format(summary.totalSpent), money.format(summary.overallEffective)),
                style = MaterialTheme.typography.bodyLarge,
            )
            BudgetProgress(summary.totalSpent, summary.overallEffective)

            AmountLine(
                stringResource(if (summary.overallDerived) R.string.base_budget_derived else R.string.base_budget),
                money.format(summary.overallBase ?: Money.ZERO),
            )
            if (summary.totalBoosts.isPositive) {
                AmountLine(stringResource(R.string.surplus_boosts), "+" + money.format(summary.totalBoosts), highlight = true)
            }

            HorizontalDivider()
            PaceLine(state, money)
        }
    }
}

@Composable
private fun PaceLine(state: OverviewUiState.Ready, money: MoneyFormatter) {
    val pace = state.pace
    val (icon, label, tint) = when (pace.status) {
        PaceStatus.ON_TRACK -> Triple(Icons.Filled.CheckCircle, R.string.status_on_track, MaterialTheme.colorScheme.primary)
        PaceStatus.WATCH -> Triple(Icons.Filled.Warning, R.string.status_watch, MaterialTheme.colorScheme.tertiary)
        PaceStatus.OVER -> Triple(Icons.Filled.Warning, R.string.status_over, MaterialTheme.colorScheme.error)
        PaceStatus.NO_BUDGET -> Triple(Icons.Filled.Info, R.string.status_no_budget, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Status is always given as text next to the icon, never by colour alone.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    }
    state.daily?.let { daily ->
        val over = daily.leftToday.isNegative
        Text(
            stringResource(R.string.daily_amount, money.format(daily.perDay)) + " · " +
                if (over) stringResource(R.string.over_today, money.format(-daily.leftToday))
                else stringResource(R.string.left_today, money.format(daily.leftToday)),
            style = MaterialTheme.typography.bodyLarge,
            // The words say "over" as well, so colour isn't the only signal.
            color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    if (state.isCurrentMonth) {
        pace.expectedSpend?.let {
            Text(
                stringResource(R.string.pace_guide, money.format(it)) + " · " +
                    pluralStringResource(R.plurals.days_left, pace.daysRemaining, pace.daysRemaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryRowItem(row: CategoryRow, money: MoneyFormatter, onClick: () -> Unit) {
    val s = row.summary
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                categoryName(row.name, row.isArchived, row.isDeleted),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (s.base != null || s.boost.isPositive) {
                if (s.isOver) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.over_by_amount, money.format(-s.remaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(stringResource(R.string.remaining_amount, money.format(s.remaining)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (s.base == null && !s.boost.isPositive) {
            Text(
                stringResource(R.string.category_no_budget, money.format(s.spent)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BudgetProgress(s.spent, s.effective)
            Row {
                Text(
                    stringResource(R.string.category_spent_of, money.format(s.spent), money.format(s.effective)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (s.boost.isPositive) {
                    Text(
                        stringResource(R.string.category_boost, money.format(s.boost)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetProgress(spent: Money, budget: Money) {
    val fraction = if (budget.isPositive) (spent.minor.toDouble() / budget.minor).toFloat() else if (spent.isPositive) 1f else 0f
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = if (fraction > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AmountLine(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
        )
    }
}

@Composable
private fun SurplusShortcut(total: Money, money: MoneyFormatter, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(stringResource(R.string.saved_surplus), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(money.format(total), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.saved_surplus_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String, action: String, onAction: () -> Unit, footer: String? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            footer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
