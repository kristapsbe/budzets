package lv.kristapsbe.budzets.ui.close

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.ErrorState
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.rememberMoneyFormatter
import lv.kristapsbe.budzets.ui.surplus.text
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun MonthCloseScreen(
    onClose: () -> Unit,
    viewModel: MonthCloseViewModel = viewModel(factory = MonthCloseViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val reopenedText = stringResource(R.string.month_reopened)
    val failedText = stringResource(R.string.action_failed)
    var refused by rememberSaveable { mutableStateOf<String?>(null) }
    val refusedText = (event as? CloseEvent.ReopenRefused)?.let { reopenRefusedText(it, state) }
    LaunchedEffect(event) {
        when (val e = event) {
            CloseEvent.Closed -> onClose()
            CloseEvent.Reopened -> snackbar.showSnackbar(reopenedText)
            CloseEvent.Failed -> snackbar.showSnackbar(failedText)
            is CloseEvent.ReopenRefused -> refused = refusedText
            null -> Unit
        }
        event?.let(viewModel::eventHandled)
    }

    MonthCloseContent(
        state = state,
        actions = MonthCloseActions(onCloseMonth = viewModel::close, onReopen = viewModel::reopen, onBack = onClose),
        snackbarHostState = snackbar,
    )

    refused?.let { text ->
        AlertDialog(
            onDismissRequest = { refused = null },
            title = { Text(stringResource(R.string.reopen_refused_title)) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { refused = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun reopenRefusedText(event: CloseEvent.ReopenRefused, state: MonthCloseUiState): String {
    val currency = (state as? MonthCloseUiState.Ready)?.currency ?: return ""
    val money = rememberMoneyFormatter(currency)
    val pockets = event.pockets.map { (label, balance) -> stringResource(R.string.pocket_would_be, label.text(), money.format(-balance)) }
    return stringResource(R.string.reopen_refused_body, pockets.joinToString("\n"))
}

data class MonthCloseActions(
    val onCloseMonth: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onBack: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthCloseContent(
    state: MonthCloseUiState,
    actions: MonthCloseActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val locale = LocalConfiguration.current.locales[0]
    val ready = state as? MonthCloseUiState.Ready
    var confirmReopen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.let { stringResource(R.string.close_month_title, it.month.displayName(locale)) } ?: "") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            MonthCloseUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
            is MonthCloseUiState.Error -> ErrorState(stringResource(R.string.error_loading), Modifier.padding(padding))
            is MonthCloseUiState.Ready -> ReviewList(state, actions, padding, onReopen = { confirmReopen = true })
        }
    }

    if (confirmReopen && ready != null) {
        AlertDialog(
            onDismissRequest = { confirmReopen = false },
            title = { Text(stringResource(R.string.reopen_confirm_title, ready.month.displayName(locale))) },
            text = { Text(stringResource(R.string.reopen_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReopen = false; actions.onReopen() }) { Text(stringResource(R.string.reopen_month)) }
            },
            dismissButton = { TextButton(onClick = { confirmReopen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ReviewList(state: MonthCloseUiState.Ready, actions: MonthCloseActions, padding: PaddingValues, onReopen: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val money = rememberMoneyFormatter(state.currency)
    val month = state.month.displayName(locale)
    val showPending = state.status == MonthStatus.CHANGED

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "status") {
            when (state.status) {
                MonthStatus.CLOSED -> Banner(
                    Icons.Filled.Lock,
                    stringResource(R.string.month_closed_on, state.closedAt?.let { formatDate(it, locale) }.orEmpty()),
                )
                MonthStatus.CHANGED -> Banner(Icons.Filled.Warning, stringResource(R.string.month_changed_review), warning = true)
                MonthStatus.OPEN -> Text(stringResource(R.string.close_explanation), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.status == MonthStatus.OPEN) {
            if (state.isCurrentMonth) {
                item(key = "early") { Banner(Icons.Filled.Info, stringResource(R.string.close_early_note, month)) }
            }
            if (!state.hasBudget) {
                item(key = "no-budget") { Banner(Icons.Filled.Warning, stringResource(R.string.close_no_budget_warning), warning = true) }
            }
            if (state.wasReopened) {
                item(key = "reopened") { Text(stringResource(R.string.month_was_reopened), style = MaterialTheme.typography.bodySmall) }
            }
        }

        items(state.lines, key = { it.label.pocket.toString() }) { line -> LineItem(line, money, showPending) }

        item(key = "total") {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(if (showPending) R.string.close_net_change else R.string.close_net),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                SignedAmount(if (showPending) state.pendingNet else state.netEffect, money, MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.surplus_not_bank_balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item(key = "actions") {
            when {
                !state.canClose -> Text(stringResource(R.string.close_future_month), style = MaterialTheme.typography.bodyMedium)
                state.status == MonthStatus.CLOSED -> OutlinedButton(onClick = onReopen, enabled = !state.isWorking) {
                    Text(stringResource(R.string.reopen_month))
                }
                else -> Button(onClick = actions.onCloseMonth, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.status == MonthStatus.CHANGED) R.string.close_again else R.string.close_month_button))
                }
            }
        }
    }
}

@Composable
private fun LineItem(line: CloseLine, money: MoneyFormatter, showPending: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.label.text(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SignedAmount(if (showPending) line.pending else line.effect, money)
        }
        val s = line.summary
        // A category with no amount set counts as zero (Q44), so it reads like any other.
        val detail = when {
            s == null -> stringResource(R.string.close_line_unallocated)
            s.boost.isPositive -> stringResource(R.string.close_line_budget_boost, money.format(s.base ?: Money.ZERO), money.format(s.boost), money.format(s.spent))
            else -> stringResource(R.string.close_line_budget, money.format(s.base ?: Money.ZERO), money.format(s.spent))
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (line.label.isDeleted) {
            Text(stringResource(R.string.close_line_deleted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Q43: what closing does to the pocket's debt. The words carry it; colour only marks new debt.
        when (val change = line.debtChange) {
            is DebtChange.PaysOff -> Text(stringResource(R.string.close_line_pays_off, money.format(change.debt)), style = MaterialTheme.typography.bodySmall)
            is DebtChange.PaysOffPart -> Text(
                stringResource(R.string.close_line_pays_off_part, money.format(change.paid), money.format(change.stillOwed)),
                style = MaterialTheme.typography.bodySmall,
            )
            is DebtChange.Leaves -> Text(
                stringResource(R.string.close_line_leaves_debt, money.format(change.debt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
        if (showPending && !line.pending.isZero) {
            Text(stringResource(R.string.close_line_total_after, money.format(line.effect)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * "+€10.00 saved" / "−€5.00 overspent" / "€0.00": the sign is spelled out, colour only reinforces
 * it. Overspending is debt only if it takes the pocket below zero, which the line under it says.
 */
@Composable
private fun SignedAmount(amount: Money, money: MoneyFormatter, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val text = when {
        amount.isPositive -> stringResource(R.string.amount_saved, money.format(amount))
        amount.isNegative -> stringResource(R.string.amount_overspent, money.format(-amount))
        else -> money.format(amount)
    }
    Text(
        text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = if (amount.isNegative) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

private fun formatDate(instant: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant.atZone(ZoneId.systemDefault()))
