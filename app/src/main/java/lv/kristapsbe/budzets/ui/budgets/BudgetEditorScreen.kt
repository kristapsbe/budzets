package lv.kristapsbe.budzets.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.TargetKind
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.amountErrorText
import lv.kristapsbe.budzets.ui.components.rememberMoneyFormatter
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName

@Composable
fun BudgetEditorScreen(
    onClose: () -> Unit,
    viewModel: BudgetEditorViewModel = viewModel(factory = BudgetEditorViewModel.Factory),
) {
    val state = viewModel.state
    if (state == BudgetEditorState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    BudgetEditorContent(
        state = state,
        actions = BudgetEditorActions(
            onDailyChange = viewModel::onDailyChange,
            onWeeklyChange = viewModel::onWeeklyChange,
            onMonthlyChange = viewModel::onMonthlyChange,
            onClear = viewModel::clear,
            onSave = viewModel::save,
            onClose = onClose,
        ),
    )
}

data class BudgetEditorActions(
    val onDailyChange: (String) -> Unit = {},
    val onWeeklyChange: (String) -> Unit = {},
    val onMonthlyChange: (String) -> Unit = {},
    val onClear: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetEditorContent(state: BudgetEditorState, actions: BudgetEditorActions) {
    val editing = state as? BudgetEditorState.Editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(editing?.categoryName?.let { stringResource(R.string.category_budget_title, it) } ?: stringResource(R.string.overall_budget))
                },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    if (editing != null) {
                        TextButton(onClick = actions.onSave, enabled = editing.isChanged && !editing.isSaving) { Text(stringResource(R.string.save)) }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            BudgetEditorState.Loading, BudgetEditorState.Done -> LoadingIndicator(Modifier.padding(padding))
            BudgetEditorState.NotFound -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.category_not_found), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = actions.onClose) { Text(stringResource(R.string.close)) }
            }
            is BudgetEditorState.Editing -> EditorForm(state, actions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun EditorForm(state: BudgetEditorState.Editing, actions: BudgetEditorActions, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val money = rememberMoneyFormatter(state.currency)
    val month = state.month.displayName(locale)

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.nextChange?.let { stringResource(R.string.applies_from_until, month, it.displayName(locale)) }
                ?: stringResource(R.string.applies_from, month),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.targets_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        TargetField(R.string.target_daily, state.form.daily, state.errors.daily, money, state.currency.fractionDigits, ImeAction.Next, actions.onDailyChange, actions.onSave, DAILY_FIELD_TAG)
        TargetField(R.string.target_weekly, state.form.weekly, state.errors.weekly, money, state.currency.fractionDigits, ImeAction.Next, actions.onWeeklyChange, actions.onSave, WEEKLY_FIELD_TAG)
        TargetField(R.string.target_monthly, state.form.monthly, state.errors.monthly, money, state.currency.fractionDigits, ImeAction.Done, actions.onMonthlyChange, actions.onSave, MONTHLY_FIELD_TAG)
        if (state.form != TargetForm()) {
            TextButton(onClick = actions.onClear) { Text(stringResource(R.string.clear_targets)) }
        }

        PreviewCard(state, money, month)

        if (state.exceedsOverall) {
            Banner(
                Icons.Filled.Warning,
                stringResource(R.string.categories_would_exceed_overall, money.format(state.categoryTotal), money.format(state.overallBase ?: Money.ZERO)),
                warning = true,
            )
        }
        if (state.closedMonthsInRange.isNotEmpty()) {
            Banner(Icons.Filled.Lock, stringResource(R.string.closed_months_keep_budget, state.closedMonthsInRange.joinToString { it.displayName(locale) }))
        }
        if (state.saveFailed) {
            Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TargetField(
    label: Int,
    value: String,
    error: AmountError?,
    money: MoneyFormatter,
    fractionDigits: Int,
    imeAction: ImeAction,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    tag: String,
) {
    val (prefix, suffix) = money.inputAffixes
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(R.string.target_not_set)) },
        prefix = prefix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        suffix = suffix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(amountErrorText(it, fractionDigits)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Shows each equivalent and which one sets the plan, so the number is never a mystery. */
@Composable
private fun PreviewCard(state: BudgetEditorState.Editing, money: MoneyFormatter, month: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val plan = state.preview
            val draft = state.draft
            when {
                plan != null && draft != null -> {
                    val days = state.month.lengthOfMonth()
                    draft.dailyMinor?.let {
                        EquivalentLine(
                            pluralStringResource(R.plurals.equivalent_daily, days, money.format(Money(it)), days, money.format(plan.dailyEquivalent!!)),
                            wins = TargetKind.DAILY in plan.determinedBy,
                        )
                    }
                    draft.weeklyMinor?.let {
                        EquivalentLine(
                            pluralStringResource(R.plurals.equivalent_weekly, days, money.format(Money(it)), days, money.format(plan.weeklyEquivalent!!)),
                            wins = TargetKind.WEEKLY in plan.determinedBy,
                        )
                    }
                    draft.monthlyMinor?.let {
                        EquivalentLine(stringResource(R.string.equivalent_monthly, money.format(Money(it))), wins = TargetKind.MONTHLY in plan.determinedBy)
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.monthly_plan_for, month, money.format(plan.amount)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val setCount = listOfNotNull(plan.dailyEquivalent, plan.weeklyEquivalent, plan.monthlyEquivalent).size
                    if (setCount > 1) {
                        Text(stringResource(R.string.highest_rule), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.categoryName != null && !state.overallDerived && state.overallBase != null) {
                        Text(
                            stringResource(R.string.category_budgets_of_overall, money.format(state.categoryTotal), money.format(state.overallBase)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.clearsBudget -> Text(
                    stringResource(if (state.categoryName == null) R.string.no_overall_budget_from else R.string.no_budget_from, month),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> Text(stringResource(R.string.preview_fix_errors), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One equivalent, with a check mark when it is the one that sets the plan. */
@Composable
private fun EquivalentLine(text: String, wins: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (wins) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (wins) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sets_the_plan), modifier = Modifier.size(18.dp))
        }
    }
}

const val DAILY_FIELD_TAG = "budget_daily"
const val WEEKLY_FIELD_TAG = "budget_weekly"
const val MONTHLY_FIELD_TAG = "budget_monthly"
