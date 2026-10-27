package lv.kristapsbe.budzets.ui.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.ui.components.MonthSelector
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.dayName
import lv.kristapsbe.budzets.util.displayName
import java.time.LocalDate
import java.util.Locale

/** @param onBack shown as an up arrow when this is a drill-down rather than the Spending tab. */
@Composable
fun TransactionsScreen(
    onAddExpense: (CategoryId?) -> Unit,
    onOpenExpense: (ExpenseId) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: TransactionsViewModel = viewModel(factory = TransactionsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleteEvent by viewModel.deleteEvent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val deletedText = stringResource(R.string.expense_deleted)
    val undoText = stringResource(R.string.undo)
    val restoreFailedText = stringResource(R.string.restore_failed)
    LaunchedEffect(deleteEvent) {
        when (val event = deleteEvent) {
            is DeleteEvent.Deleted -> {
                // With an action the default duration is Indefinite; an undo offer should expire.
                val result = snackbar.showSnackbar(deletedText, actionLabel = undoText, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event) else viewModel.eventHandled(event)
            }
            DeleteEvent.RestoreFailed -> {
                snackbar.showSnackbar(restoreFailedText)
                viewModel.eventHandled(event)
            }
            null -> Unit
        }
    }

    TransactionsContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        actions = TransactionsActions(
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::goToCurrentMonth,
            onSelectCategory = viewModel::selectCategory,
            onQueryChange = viewModel::setQuery,
            onRetry = viewModel::retry,
            onAddExpense = onAddExpense,
            onOpenExpense = onOpenExpense,
            onDelete = viewModel::delete,
        ),
    )
}

data class TransactionsActions(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onCurrentMonth: () -> Unit = {},
    val onSelectCategory: (CategoryId?) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAddExpense: (CategoryId?) -> Unit = {},
    val onOpenExpense: (ExpenseId) -> Unit = {},
    val onDelete: (ExpenseId) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsContent(
    state: TransactionsUiState,
    actions: TransactionsActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: (() -> Unit)? = null,
) {
    val ready = state as? TransactionsUiState.Ready
    // The field's text is local so typing stays synchronous; the ViewModel only gets the filter.
    var searchOpen by rememberSaveable { mutableStateOf(ready?.query?.isNotEmpty() == true) }
    var query by rememberSaveable { mutableStateOf(ready?.query.orEmpty()) }
    // Focus only when the user opens the field, not each time it scrolls back into view.
    var focusSearch by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_transactions)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (searchOpen) {
                        IconButton(onClick = { searchOpen = false; query = ""; actions.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_search))
                        }
                    } else {
                        IconButton(onClick = { searchOpen = true; focusSearch = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_notes))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready != null && ready.hasCategories) {
                ExtendedFloatingActionButton(onClick = { actions.onAddExpense(ready.selectedCategory) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_expense))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            TransactionsUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            is TransactionsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
            }
            is TransactionsUiState.Ready -> ReadyList(
                state = state,
                actions = actions,
                padding = padding,
                search = if (searchOpen) query else null,
                onSearchChange = { query = it; actions.onQueryChange(it) },
                focusSearch = focusSearch,
                onSearchFocused = { focusSearch = false },
            )
        }
    }
}

/** @param search the search field's text, or null when the field is closed. */
@Composable
private fun ReadyList(
    state: TransactionsUiState.Ready,
    actions: TransactionsActions,
    padding: PaddingValues,
    search: String?,
    onSearchChange: (String) -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 4.dp,
            // room for the FAB
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
    ) {
        item(key = "month") {
            MonthSelector(
                state.month,
                state.isCurrentMonth,
                actions.onPreviousMonth,
                actions.onNextMonth,
                actions.onCurrentMonth,
                Modifier.padding(horizontal = 16.dp),
            )
        }

        if (state.hasCategories) {
            item(key = "filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedCategory == null,
                            onClick = { actions.onSelectCategory(null) },
                            label = { Text(stringResource(R.string.all_categories)) },
                        )
                    }
                    items(state.chips, key = { it.id.value }) { chip ->
                        FilterChip(
                            selected = chip.id == state.selectedCategory,
                            onClick = { actions.onSelectCategory(chip.id) },
                            label = { Text(chip.name) },
                        )
                    }
                }
            }
        }

        if (search != null) {
            item(key = "search") {
                val focus = remember { FocusRequester() }
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        focus.requestFocus()
                        onSearchFocused()
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    label = { Text(stringResource(R.string.search_notes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                )
            }
        }

        if (state.count > 0) {
            item(key = "total") {
                Text(
                    stringResource(R.string.spent_amount, money.format(state.total)) + " · " +
                        pluralStringResource(R.plurals.expense_count, state.count, state.count),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            item(key = "empty") {
                Text(
                    emptyText(state, locale),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        state.days.forEach { day ->
            item(key = "day-${day.date}") {
                DayHeader(day, state.today, money, locale)
            }
            items(day.rows, key = { it.id.value }) { row ->
                SwipeToDeleteRow(row, money, onOpen = { actions.onOpenExpense(row.id) }, onDelete = { actions.onDelete(row.id) })
            }
        }
    }
}

@Composable
private fun emptyText(state: TransactionsUiState.Ready, locale: Locale): String {
    val month = state.month.displayName(locale)
    return when {
        !state.hasCategories -> stringResource(R.string.empty_spending_no_categories)
        state.query.isNotBlank() -> stringResource(R.string.empty_search, state.query.trim())
        state.selectedCategoryName != null -> stringResource(R.string.empty_category_spending, state.selectedCategoryName!!, month)
        else -> stringResource(R.string.empty_month_spending, month)
    }
}

@Composable
private fun DayHeader(day: DayGroup, today: LocalDate, money: MoneyFormatter, locale: Locale) {
    val label = when (day.date) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> day.date.dayName(locale, today.year)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(money.format(day.total), style = MaterialTheme.typography.titleSmall)
    }
}

/** Swipe toward the start to delete; TalkBack gets the same as a "Delete" custom action. */
@Composable
private fun SwipeToDeleteRow(row: ExpenseRow, money: MoneyFormatter, onOpen: () -> Unit, onDelete: () -> Unit) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Plain remember, not the saveable default: a restored (undone) row must come back settled,
    // not with a saved "dismissed" state that would delete it again.
    val dismissState = remember(row.id) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val currentOnDelete by rememberUpdatedState(onDelete)
    val deleteLabel = stringResource(R.string.delete)

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { currentOnDelete() },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ListItem(
            headlineContent = {
                Text(if (row.isArchivedCategory) stringResource(R.string.category_archived, row.categoryName) else row.categoryName)
            },
            supportingContent = row.note?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            trailingContent = { Text(money.format(row.amount), style = MaterialTheme.typography.bodyLarge) },
            modifier = Modifier
                .clickable(onClick = onOpen)
                .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { currentOnDelete(); true }) },
        )
    }
}
