package lv.kristapsbe.budzets.ui.budgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.MonthPager
import lv.kristapsbe.budzets.ui.components.MonthSelector
import lv.kristapsbe.budzets.ui.components.SwipeToDelete
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.YearMonth
import java.util.Locale

/** @param onEditBudget opens the editor for a category (null = overall) from the given month. */
@Composable
fun BudgetsScreen(
    onEditBudget: (CategoryId?, YearMonth) -> Unit,
    viewModel: BudgetsViewModel = viewModel(factory = BudgetsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val month by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val starterNames = stringArrayResource(R.array.starter_categories).toList()

    val texts = mapOf(
        BudgetsMessage.DELETED to stringResource(R.string.category_deleted),
        BudgetsMessage.ACTION_FAILED to stringResource(R.string.action_failed),
    )
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(texts.getValue(it))
            viewModel.messageShown(it)
        }
    }

    BudgetsContent(
        state = state,
        snackbarHostState = snackbar,
        month = month,
        pageState = viewModel::stateFor,
        actions = BudgetsActions(
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::goToCurrentMonth,
            onSelectMonth = viewModel::selectMonth,
            onRetry = viewModel::retry,
            onEditBudget = onEditBudget,
            onAddStarterCategories = { viewModel.addStarterCategories(starterNames) },
            onAddCategory = viewModel::addCategory,
            onRename = viewModel::renameCategory,
            onMove = viewModel::move,
            onReorder = viewModel::reorder,
            onArchive = viewModel::archive,
            onUnarchive = viewModel::unarchive,
            onDelete = viewModel::delete,
        ),
    )
}

data class BudgetsActions(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onCurrentMonth: () -> Unit = {},
    val onSelectMonth: (YearMonth) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onEditBudget: (CategoryId?, YearMonth) -> Unit = { _, _ -> },
    val onAddStarterCategories: () -> Unit = {},
    val onAddCategory: (String) -> Unit = {},
    val onRename: (CategoryId, String) -> Unit = { _, _ -> },
    val onMove: (CategoryId, Boolean) -> Unit = { _, _ -> },
    val onReorder: (List<CategoryId>) -> Unit = {},
    val onArchive: (CategoryId) -> Unit = {},
    val onUnarchive: (CategoryId) -> Unit = {},
    val onDelete: (CategoryId) -> Unit = {},
)

/** Which name dialog is open: [ADD], a category id to rename, or null. */
private const val ADD = "@add"

/**
 * @param state the selected month, for what surrounds the pages.
 * @param month the selected month, known before its state is.
 * @param pageState each page's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsContent(
    state: BudgetsUiState,
    actions: BudgetsActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    month: YearMonth = (state as? BudgetsUiState.Ready)?.month ?: YearMonth.now(),
    pageState: (YearMonth) -> StateFlow<BudgetsUiState> = { MutableStateFlow(state) },
) {
    var nameDialogFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? BudgetsUiState.Ready

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_budgets)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        MonthPager(month, actions.onSelectMonth, pageState, BudgetsUiState.Loading, Modifier.fillMaxSize()) { page ->
            when (page) {
                BudgetsUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
                is BudgetsUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
                }
                is BudgetsUiState.Ready -> ReadyList(
                    page,
                    actions,
                    padding,
                    onAdd = { nameDialogFor = ADD },
                    onRename = { nameDialogFor = it.value },
                    onDelete = { deleteFor = it.value },
                )
            }
        }
    }

    nameDialogFor?.let { target ->
        val current = ready?.categories?.firstOrNull { it.id.value == target }?.name
        CategoryNameDialog(
            title = stringResource(if (target == ADD) R.string.add_category else R.string.rename_category),
            initial = current.orEmpty(),
            onConfirm = { name ->
                if (target == ADD) actions.onAddCategory(name) else actions.onRename(CategoryId(target), name)
                nameDialogFor = null
            },
            onDismiss = { nameDialogFor = null },
        )
    }

    deleteFor?.let { id ->
        val name = ready?.categories?.firstOrNull { it.id.value == id }?.name
            ?: ready?.archived?.firstOrNull { it.id.value == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.delete_category_title, name)) },
            text = { Text(stringResource(R.string.delete_category_body)) },
            confirmButton = {
                TextButton(onClick = { actions.onDelete(CategoryId(id)); deleteFor = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ReadyList(
    state: BudgetsUiState.Ready,
    actions: BudgetsActions,
    padding: PaddingValues,
    onAdd: () -> Unit,
    onRename: (CategoryId) -> Unit,
    onDelete: (CategoryId) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }
    val listState = rememberLazyListState()

    // Rows follow the drag here and are saved once, on drop. Each list from the database replaces it.
    var order by remember(state.categories) { mutableStateOf(state.categories) }
    val reorderState = rememberReorderableLazyListState(
        listState,
        scrollThresholdPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) { from, to ->
        // By key: positions in the list count everything above the categories too.
        order = order.toMutableList().apply {
            val target = indexOfFirst { it.id.value == to.key }
            add(target, removeAt(indexOfFirst { it.id.value == from.key }))
        }
    }
    // Read through a State: a gesture keeps the callback it started with.
    val saveOrder by rememberUpdatedState {
        val ids = order.filter { it.canMove }.map { it.id }
        if (ids != state.categories.filter { it.canMove }.map { it.id }) actions.onReorder(ids)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        if (state.isClosed) {
            item(key = "closed") {
                Banner(
                    Icons.Filled.Lock,
                    stringResource(R.string.budgets_month_closed, state.month.displayName(locale)),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "overall") {
            OverallCard(state, money, locale, onClick = if (state.isClosed) null else { { actions.onEditBudget(null, state.month) } })
        }

        if (state.exceedsOverall) {
            item(key = "exceeds") {
                Banner(
                    Icons.Filled.Warning,
                    stringResource(R.string.categories_exceed_overall, money.format(state.categoryTotal), money.format(state.overallBase ?: Money.ZERO)),
                    warning = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else if (state.unallocated.isPositive && !state.isClosed) {
            item(key = "unallocated") {
                Banner(
                    Icons.Filled.Info,
                    stringResource(R.string.budget_unallocated, money.format(state.unallocated)),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "categories-header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.categories),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (state.hasCategories && !state.isClosed) {
                    TextButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_category))
                    }
                }
            }
        }

        if (!state.hasCategories && !state.isClosed) {
            item(key = "empty") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.empty_no_categories_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.empty_no_categories_body), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = actions.onAddStarterCategories) { Text(stringResource(R.string.add_starter_categories)) }
                            TextButton(onClick = onAdd) { Text(stringResource(R.string.add_category)) }
                        }
                    }
                }
            }
        }

        items(order, key = { it.id.value }) { row ->
            val editable = !state.isClosed && !row.isDeleted
            val movable = editable && row.canMove
            ReorderableItem(reorderState, key = row.id.value, enabled = movable) { isDragging ->
                SwipeToDelete(onDelete = { onDelete(row.id) }, enabled = editable, confirmsDelete = true) {
                    CategoryRowItem(
                        row,
                        money,
                        locale,
                        isDragging,
                        // The long-press sits inside the swipe and the pager, so it claims a held
                        // finger and leaves a moving one to them. It's inside the click so it sees
                        // the finger lift first: letting go without moving mustn't open the editor.
                        modifier = (if (editable) Modifier.clickable { actions.onEditBudget(row.id, state.month) } else Modifier)
                            .longPressDraggableHandle(enabled = movable, onDragStopped = { saveOrder() }),
                        menu = if (!editable) emptyList() else buildList {
                            add(MenuEntry(R.string.rename) { onRename(row.id) })
                            if (row.canMoveUp) add(MenuEntry(R.string.move_up) { actions.onMove(row.id, true) })
                            if (row.canMoveDown) add(MenuEntry(R.string.move_down) { actions.onMove(row.id, false) })
                            if (row.archivedFrom == null) add(MenuEntry(R.string.archive) { actions.onArchive(row.id) })
                            else add(MenuEntry(R.string.unarchive) { actions.onUnarchive(row.id) })
                            add(MenuEntry(R.string.delete) { onDelete(row.id) })
                        },
                    )
                }
            }
        }

        if (state.archived.isNotEmpty()) {
            item(key = "archived-header") {
                Text(
                    stringResource(R.string.archived_categories),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp).semantics { heading() },
                )
            }
            items(state.archived, key = { "archived-${it.id.value}" }) { row ->
                SwipeToDelete(onDelete = { onDelete(row.id) }, confirmsDelete = true) {
                    ListItem(
                        headlineContent = { Text(row.name) },
                        supportingContent = { Text(stringResource(R.string.archived_from, row.archivedFrom.displayName(locale))) },
                        trailingContent = {
                            OverflowMenu(
                                row.name,
                                listOf(
                                    MenuEntry(R.string.unarchive) { actions.onUnarchive(row.id) },
                                    MenuEntry(R.string.delete) { onDelete(row.id) },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverallCard(state: BudgetsUiState.Ready, money: MoneyFormatter, locale: Locale, onClick: (() -> Unit)?) {
    val overall = state.overall
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.overall_budget), style = MaterialTheme.typography.titleMedium)
            val plan = overall.plan
            if (plan != null) {
                Text(
                    stringResource(R.string.budget_per_month, money.format(plan.amount)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // A monthly target alone would only repeat the line above.
                overall.target?.takeUnless { it.dailyMinor == null && it.weeklyMinor == null }?.let {
                    Text(targetSummary(it, money), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    if (state.overallDerived) {
                        stringResource(R.string.overall_not_set_derived, money.format(state.overallBase ?: Money.ZERO))
                    } else {
                        stringResource(R.string.overall_not_set)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            VersionNotes(overall, locale)
            if (plan != null && state.categories.isNotEmpty()) {
                Text(
                    stringResource(R.string.category_budgets_of_overall, money.format(state.categoryTotal), money.format(plan.amount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier) { content() } else Card(modifier) { content() }
}

/**
 * @param isDragging held long enough to drag. The row wiggles once and keeps a border until it's
 *   let go; the border is also the cue when system animations are off.
 * @param modifier for the `ListItem`, which merges its own semantics: a click outside it would give
 *   TalkBack a button with no name beside a name that can't be pressed.
 */
@Composable
private fun CategoryRowItem(
    row: CategoryBudgetRow,
    money: MoneyFormatter,
    locale: Locale,
    isDragging: Boolean,
    modifier: Modifier,
    menu: List<MenuEntry>,
) {
    val plan = row.budget.plan
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(isDragging) {
        if (isDragging) WIGGLE_DEGREES.forEach { wiggle.animateTo(it, tween(WIGGLE_STEP_MILLIS)) } else wiggle.snapTo(0f)
    }
    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
    Surface(
        // Read in the layer, so the wiggle doesn't recompose the row.
        modifier = Modifier
            .graphicsLayer { rotationZ = wiggle.value }
            .then(if (isDragging) Modifier.testTag(CATEGORY_DRAGGING_TAG) else Modifier),
        shape = if (isDragging) MaterialTheme.shapes.medium else RectangleShape,
        border = if (isDragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shadowElevation = elevation,
    ) {
        ListItem(
            headlineContent = { Text(if (row.isDeleted) stringResource(R.string.category_deleted_label, row.name) else row.name) },
            supportingContent = {
                Column {
                    Text(row.budget.target?.let { targetSummary(it, money) } ?: stringResource(R.string.no_budget))
                    VersionNotes(row.budget, locale)
                    row.archivedFrom?.takeUnless { row.isDeleted }?.let { Text(stringResource(R.string.archived_from, it.displayName(locale))) }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // With no amount set the category counts as zero (Q44), so that's what it shows.
                    Text(money.format(plan?.amount ?: Money.ZERO), style = MaterialTheme.typography.bodyLarge)
                    if (menu.isNotEmpty()) OverflowMenu(row.name, menu)
                }
            },
            modifier = modifier,
        )
    }
}

/** One decaying shake. Slight: about 3 dp at the ends of a phone-wide row. */
private val WIGGLE_DEGREES = listOf(1f, -1f, 0.6f, -0.3f, 0f)
private const val WIGGLE_STEP_MILLIS = 60

/** "Since March 2026" / "Changes in May 2027", when the version in force isn't the whole story. */
@Composable
private fun VersionNotes(line: BudgetLine, locale: Locale) {
    val notes = listOfNotNull(
        line.since?.let { stringResource(R.string.budget_since, it.displayName(locale)) },
        line.nextChange?.let { stringResource(R.string.budget_changes_in, it.displayName(locale)) },
    )
    if (notes.isNotEmpty()) {
        Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class MenuEntry(val label: Int, val onClick: () -> Unit)

@Composable
private fun OverflowMenu(name: String, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.category_actions, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(stringResource(entry.label)) }, onClick = { open = false; entry.onClick() })
            }
        }
    }
}

/** "€10.00/day · €300.00/month" for the targets that are set. */
@Composable
fun targetSummary(target: BudgetTarget, money: MoneyFormatter): String = listOfNotNull(
    target.dailyMinor?.let { stringResource(R.string.budget_per_day, money.format(Money(it))) },
    target.weeklyMinor?.let { stringResource(R.string.budget_per_week, money.format(Money(it))) },
    target.monthlyMinor?.let { stringResource(R.string.budget_per_month, money.format(Money(it))) },
).joinToString(" · ")

@Composable
private fun CategoryNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Whole name selected on open, so typing replaces it (a plain String field would put the cursor at 0).
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val trimmed = field.text.trim()
    val tooLong = trimmed.length > CategoryRepository.MAX_NAME_LENGTH
    val valid = trimmed.isNotEmpty() && !tooLong
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text(stringResource(R.string.category_name)) },
                singleLine = true,
                isError = tooLong,
                supportingText = if (tooLong) {
                    { Text(pluralStringResource(R.plurals.category_name_too_long, CategoryRepository.MAX_NAME_LENGTH, CategoryRepository.MAX_NAME_LENGTH)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(trimmed) }),
                modifier = Modifier.focusRequester(focus).testTag(CATEGORY_NAME_FIELD_TAG),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

const val CATEGORY_NAME_FIELD_TAG = "category_name"
const val CATEGORY_DRAGGING_TAG = "category_dragging"
