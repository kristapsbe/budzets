package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.categoryName
import lv.kristapsbe.budzets.ui.surplus.signed
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import java.time.YearMonth
import java.util.Locale

@Composable
fun SpendingHistoryScreen(
    onBack: () -> Unit,
    viewModel: SpendingHistoryViewModel = viewModel(factory = SpendingHistoryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SpendingHistoryContent(
        state = state,
        actions = SpendingHistoryActions(
            onBack = onBack,
            onSelectMonth = viewModel::selectMonth,
            onToggleHighlight = viewModel::toggleHighlight,
            onRetry = viewModel::retry,
        ),
    )
}

data class SpendingHistoryActions(
    val onBack: () -> Unit = {},
    val onSelectMonth: (YearMonth) -> Unit = {},
    /** Takes a [ChartSeries.key]. */
    val onToggleHighlight: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingHistoryContent(state: SpendingHistoryUiState, actions: SpendingHistoryActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spending_by_month)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            SpendingHistoryUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
            is SpendingHistoryUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_loading_history), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
            }
            is SpendingHistoryUiState.Ready -> if (state.months.isEmpty()) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            } else {
                ReadyContent(state, actions, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun ReadyContent(state: SpendingHistoryUiState.Ready, actions: SpendingHistoryActions, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }
    val unit = unitMinor(state.currency.fractionDigits)
    val spendingScale = remember(state.months, unit) { spendingScale(state.months, unit) }
    val surplusScale = remember(state.surplus, unit) { surplusScale(state.surplus, unit) }
    val spendingTicks = spendingScale.ticks.map { money.formatWhole(Money(it)) }
    val surplusTicks = surplusScale.ticks.map { money.formatWhole(Money(it)) }
    // Shared, so the two charts' months line up and scroll together.
    val axisWidth = axisWidth(spendingTicks + surplusTicks)
    // Starts at the far end: the value is clamped to the end once the width is known.
    val scroll = rememberScrollState(Int.MAX_VALUE)
    val hasSurplus = state.surplus.any { it.runningTotal != null }
    val inset = Modifier.padding(horizontal = 16.dp)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChartHeading(stringResource(R.string.history_spending_heading), inset)
        SpendingChart(state, spendingScale, spendingTicks, axisWidth, scroll, money, locale, actions.onSelectMonth, Modifier.fillMaxWidth().then(inset))
        Legend(state, actions.onToggleHighlight, inset)
        ChartHeading(stringResource(R.string.history_surplus_heading), inset)
        if (hasSurplus) {
            SurplusChart(state, surplusScale, surplusTicks, axisWidth, scroll, money, locale, actions.onSelectMonth, Modifier.fillMaxWidth().then(inset))
            SurplusLegend(state, inset)
        } else {
            Text(
                stringResource(R.string.history_surplus_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = inset,
            )
        }
        state.selected?.let { MonthBreakdown(state, it, money, locale, actions.onToggleHighlight, inset) }
    }
}

@Composable
private fun ChartHeading(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

/** What the surplus chart's marks mean; the words never take the marks' colours. */
@Composable
private fun SurplusLegend(state: SpendingHistoryUiState.Ready, modifier: Modifier) {
    val colors = chartColors
    val lineColor = MaterialTheme.colorScheme.onSurface
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendKey(stringResource(R.string.history_legend_added)) { Swatch(colors.positive) }
        LegendKey(stringResource(R.string.history_legend_taken)) { Swatch(colors.negative) }
        if (state.surplus.any { it.result != null && !it.isClosed }) {
            LegendKey(stringResource(R.string.history_legend_not_closed)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(colors.positive.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                        .border(1.dp, colors.positive, RoundedCornerShape(3.dp)),
                )
            }
        }
        LegendKey(stringResource(R.string.history_legend_total)) {
            Canvas(Modifier.width(20.dp).height(12.dp)) {
                val y = size.height / 2
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(size.width / 2, y))
            }
        }
    }
}

@Composable
private fun LegendKey(label: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every band's colour and name, and the budget line's key. A band's chip highlights it across the months. */
@Composable
private fun Legend(state: SpendingHistoryUiState.Ready, onToggle: (String) -> Unit, modifier: Modifier) {
    val colors = chartColors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.series.forEach { series ->
            val selected = series.key == state.highlighted
            FilterChip(
                selected = selected,
                onClick = { onToggle(series.key) },
                label = { Text(seriesName(series)) },
                leadingIcon = { Swatch(colors[series.slot]) },
                // Marked with a tick as well as the chip's colour.
                trailingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
        // Only when some month has a line to explain. As tall as a chip with its touch target, so
        // the key lines up with the chips beside it.
        if (state.months.any { it.budget != null }) Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lineColor = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.width(20.dp).height(2.dp)) {
                    drawLine(
                        lineColor,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.history_budget_key),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The selected month in words: totals, then each category from the top of the bar down. */
@Composable
private fun MonthBreakdown(
    state: SpendingHistoryUiState.Ready,
    stack: MonthStack,
    money: MoneyFormatter,
    locale: Locale,
    onToggle: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = chartColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            monthTitle(stack.month, state.currentMonth, locale),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val budget = stack.budget
        if (budget == null) {
            Text(
                stringResource(R.string.spent_amount, money.format(stack.total)) + " · " + stringResource(R.string.status_no_budget),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(stringResource(R.string.spent_of_budget, money.format(stack.total), money.format(budget)), style = MaterialTheme.typography.bodyLarge)
            val left = budget - stack.total
            if (left.isNegative) {
                // In words as well as red, so colour isn't the only signal.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.over_by_amount, money.format(-left)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(stringResource(R.string.remaining_amount, money.format(left)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.selectedSurplus?.let { surplus ->
            val taken = surplus.result?.isNegative == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Taking from the surplus is said in words as well as red.
                if (taken) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    surplusSentence(surplus, state.currentMonth, money),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (taken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            // With boosts or one-offs in it, the result isn't what the close screen shows, so it's split.
            val closing = surplus.closing
            if (closing != null && (!surplus.boosts.isZero || !surplus.oneOffs.isZero)) {
                SurplusPart(stringResource(R.string.history_surplus_part_closing, signed(closing, money)))
                if (!surplus.boosts.isZero) SurplusPart(stringResource(R.string.history_surplus_part_boosts, signed(surplus.boosts, money)))
                if (!surplus.oneOffs.isZero) SurplusPart(stringResource(R.string.history_surplus_part_one_offs, signed(surplus.oneOffs, money)))
            }
            surplus.runningTotal?.let {
                Text(
                    stringResource(R.string.history_surplus_after, money.format(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        if (stack.segments.isEmpty()) {
            Text(
                stringResource(R.string.history_month_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        stack.segments.asReversed().forEach { segment ->
            val series = state.seriesFor(segment.series) ?: return@forEach
            val selected = series.key == state.highlighted
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.Checkbox) { onToggle(series.key) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(colors[series.slot])
                Spacer(Modifier.width(12.dp))
                Text(
                    seriesName(series),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(money.format(segment.amount), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SurplusPart(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp),
    )
}

@Composable
private fun seriesName(series: ChartSeries): String =
    if (series.id == null) stringResource(R.string.history_other_categories)
    else categoryName(series.name, series.isArchived, series.isDeleted)

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
}
