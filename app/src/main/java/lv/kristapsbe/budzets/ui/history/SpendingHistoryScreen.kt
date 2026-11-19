package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.BurnDown
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.forecast.ForecastBasis
import lv.kristapsbe.budzets.domain.forecast.ForecastRules
import lv.kristapsbe.budzets.domain.forecast.ForecastStack
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.categoryName
import lv.kristapsbe.budzets.ui.surplus.signed
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.first

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
            is SpendingHistoryUiState.Ready -> ReadyContent(state, actions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ReadyContent(state: SpendingHistoryUiState.Ready, actions: SpendingHistoryActions, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }
    val unit = unitMinor(state.currency.fractionDigits)
    val spendingScale = remember(state.months, state.forecast, unit) { spendingScale(state.months, unit, state.forecast) }
    val surplusScale = remember(state.surplus, state.forecast, unit) { surplusScale(state.surplus, unit, state.forecast) }
    val burnDownScale = remember(state.burnDown, unit) { state.burnDown?.let { burnDownScale(it, unit) } }
    val spendingTicks = spendingScale.ticks.map { money.formatWhole(Money(it)) }
    val surplusTicks = surplusScale.ticks.map { money.formatWhole(Money(it)) }
    val burnDownTicks = burnDownScale?.ticks.orEmpty().map { money.formatWhole(Money(it)) }
    // Shared, so the month charts' months line up and scroll together, and every plot starts at the same x.
    val axisWidth = axisWidth(spendingTicks + surplusTicks + burnDownTicks)
    val scroll = rememberScrollState()
    OpenOnCurrentMonth(scroll, ahead = state.forecast?.months?.size ?: 0)
    val hasSpending = state.months.any { it.segments.isNotEmpty() }
    val inset = Modifier.padding(horizontal = 16.dp)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The selected month day by day (Q50); it follows the month tapped in the charts below.
        state.selectedMonth?.let { month ->
            BurnDownSection(state, month, burnDownScale, burnDownTicks, axisWidth, money, locale, inset)
        }
        Column(inset, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChartHeading(stringResource(R.string.history_spending_heading), Modifier)
            // What the hatched months are based on, or when they will start (Q54).
            if (hasSpending) {
                Text(
                    state.forecast?.let { basisText(it.basis) }
                        ?: pluralStringResource(R.plurals.forecast_not_yet, ForecastRules.MIN_MONTHS, ForecastRules.MIN_MONTHS, state.finishedMonths),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasSpending) {
            SpendingChart(state, spendingScale, spendingTicks, axisWidth, scroll, money, locale, actions.onSelectMonth, Modifier.fillMaxWidth().then(inset))
            Legend(state, actions.onToggleHighlight, inset)
        } else {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = inset,
            )
        }
        // Always shown (Q45): there is at least the month under way.
        ChartHeading(stringResource(R.string.history_surplus_heading), inset)
        SurplusChart(state, surplusScale, surplusTicks, axisWidth, scroll, money, locale, actions.onSelectMonth, Modifier.fillMaxWidth().then(inset))
        SurplusLegend(state, inset)
        state.selected?.let { MonthBreakdown(state, it, money, locale, actions.onToggleHighlight, inset) }
        state.selectedForecast?.let { ForecastBreakdown(state, it, money, locale, actions.onToggleHighlight, inset) }
    }
}

/** How many forecast months show to the right of the month under way when the screen opens. */
private const val FORECAST_MONTHS_IN_VIEW = 3

/**
 * Scrolls the month charts once, when the screen first opens, so the month under way shows with
 * the next few forecast months beside it. Without a forecast that is the far end, as before. A
 * restored screen keeps where it was.
 */
@Composable
private fun OpenOnCurrentMonth(scroll: ScrollState, ahead: Int) {
    val slot = with(LocalDensity.current) { SlotWidth.roundToPx() }
    var placed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (placed) return@LaunchedEffect
        val max = snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        scroll.scrollTo((max - (ahead - FORECAST_MONTHS_IN_VIEW).coerceAtLeast(0) * slot).coerceAtLeast(0))
        placed = true
    }
}

@Composable
private fun basisText(basis: ForecastBasis): String = stringResource(
    when (basis) {
        ForecastBasis.TYPICAL_MONTH -> R.string.forecast_basis_typical
        ForecastBasis.TREND -> R.string.forecast_basis_trend
        ForecastBasis.TREND_AND_SEASON -> R.string.forecast_basis_season
    },
)

/**
 * The burn-down's heading, chart, key and figures, or "No budget set" for a month without one.
 * It never shrinks once shown, so selecting a month in the charts below doesn't pull them up
 * under the finger.
 */
@Composable
private fun BurnDownSection(
    state: SpendingHistoryUiState.Ready,
    month: YearMonth,
    scale: ChartScale?,
    tickLabels: List<String>,
    axisWidth: Dp,
    money: MoneyFormatter,
    locale: Locale,
    modifier: Modifier,
) {
    val burnDown = state.burnDown?.takeIf { it.month == month }
    var tallest by remember { mutableIntStateOf(0) }
    val minHeight = with(LocalDensity.current) { tallest.toDp() }
    Column(
        Modifier.heightIn(min = minHeight).onSizeChanged { if (it.height > tallest) tallest = it.height },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChartHeading(stringResource(R.string.burn_down_heading), Modifier)
            Text(
                monthTitle(month, state.currentMonth, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (burnDown == null || scale == null) {
            val why = if (month > state.currentMonth) R.string.burn_down_not_started else R.string.status_no_budget
            NoBurnDown(stringResource(why), Modifier.fillMaxWidth().then(modifier))
            return@Column
        }
        BurnDownChart(
            burnDown,
            scale,
            tickLabels,
            axisWidth,
            burnDownDescription(burnDown, state.currentMonth, money, locale),
            Modifier.fillMaxWidth().then(modifier),
        )
        BurnDownLegend(burnDown, modifier)
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(burnDownStart(burnDown, money), style = MaterialTheme.typography.bodyMedium)
            burnDownBoosts(burnDown, money)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val left = burnDownLeft(burnDown, money)
            if (burnDown.left.isNegative) {
                // In words as well as red, so colour isn't the only signal.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(left, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(left, style = MaterialTheme.typography.bodyLarge)
            }
            burnDownGuide(burnDown, money)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            burnDownLikely(burnDown, money)?.let {
                val over = burnDown.likelyLeft?.expected?.isNegative == true
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
            burnDownLikelyRange(burnDown, money)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What the burn-down's marks mean; the boost key only when the month has a boost to explain. */
@Composable
private fun BurnDownLegend(burnDown: BurnDown, modifier: Modifier) {
    val colors = chartColors
    val lineColor = MaterialTheme.colorScheme.onSurface
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendKey(stringResource(R.string.burn_down_legend_left)) {
            Canvas(Modifier.width(20.dp).height(12.dp)) {
                val y = size.height / 2
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(size.width, y))
            }
        }
        LegendKey(stringResource(R.string.burn_down_legend_guide)) {
            Canvas(Modifier.width(20.dp).height(12.dp)) {
                val y = size.height / 2
                drawLine(
                    guideColor,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }
        }
        if (burnDown.boostsAdded.isPositive) {
            LegendKey(stringResource(R.string.burn_down_legend_boost)) {
                Canvas(Modifier.width(12.dp).height(16.dp)) {
                    val x = size.width / 2
                    val dot = 4.dp.toPx()
                    drawLine(colors.positive, Offset(x, size.height), Offset(x, dot), strokeWidth = 3.dp.toPx())
                    drawCircle(colors.positive, radius = dot, center = Offset(x, dot))
                }
            }
        }
        if (burnDown.projection != null) {
            LegendKey(stringResource(R.string.forecast_legend)) {
                Canvas(Modifier.width(20.dp).height(12.dp)) {
                    val y = size.height / 2
                    drawRect(lineColor, Offset(0f, 0f), size, alpha = 0.12f)
                    drawLine(lineColor, Offset(2.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = forecastDots())
                }
            }
        }
        if (burnDown.days.any { it.left.isNegative }) {
            LegendKey(stringResource(R.string.status_over)) {
                Canvas(Modifier.width(20.dp).height(12.dp)) {
                    val y = size.height / 2
                    drawLine(colors.negative, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                }
            }
        }
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
        val forecast = state.forecast ?: return@FlowRow
        LegendKey(stringResource(R.string.forecast_legend)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HatchedSwatch(colors.positive)
                Spacer(Modifier.width(4.dp))
                Canvas(Modifier.width(20.dp).height(12.dp)) {
                    val y = size.height / 2
                    drawLine(lineColor, Offset(2.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = forecastDots())
                }
            }
        }
        LegendKey(stringResource(R.string.forecast_legend_range)) { WhiskerKey() }
        if (forecast.monthEnd != null) {
            LegendKey(stringResource(R.string.forecast_legend_month_end)) {
                Canvas(Modifier.width(20.dp).height(12.dp)) {
                    val y = size.height / 2
                    drawLine(lineColor, Offset(2.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** A forecast's swatch: hatched like the forecast marks. */
@Composable
private fun HatchedSwatch(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(3.dp.toPx()))) }
        drawHatched(path, color)
    }
}

/** The likely range's key: a whisker. */
@Composable
private fun WhiskerKey() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.width(12.dp).height(16.dp)) { drawWhisker(size.width / 2, size.height - 1.dp.toPx(), 1.dp.toPx(), color) }
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
        // As tall as a chip, like the budget key.
        if (state.forecast != null) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                LegendKey(stringResource(R.string.forecast_legend)) { HatchedSwatch(colors.other) }
            }
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                LegendKey(stringResource(R.string.forecast_legend_range)) { WhiskerKey() }
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
        // Where the month under way will likely end (Q54).
        state.forecast?.currentEnd?.takeIf { stack.month == state.currentMonth }?.let { end ->
            Text(stringResource(R.string.forecast_month_end_spent, money.format(end.expected)), style = MaterialTheme.typography.bodyMedium)
            forecastRange(end, money)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    surplusSentence(surplus, money),
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
            Text(
                stringResource(R.string.history_surplus_after, money.format(surplus.runningTotal)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Q55: the bar stays "if closed now"; this is where the month will likely end.
            state.forecast?.monthEnd?.takeIf { surplus.month == state.currentMonth }?.let { end ->
                Text(monthEndSentence(end.result.expected, money), style = MaterialTheme.typography.bodyMedium)
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

/** A forecast month in words: its likely total and range, what it would do to the surplus, then each band, largest first. */
@Composable
private fun ForecastBreakdown(
    state: SpendingHistoryUiState.Ready,
    stack: ForecastStack,
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
        Text(forecastSpent(stack, money), style = MaterialTheme.typography.bodyLarge)
        forecastRange(stack.total, money)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.selectedProjection?.let { projection ->
            val taking = projection.result?.expected?.isNegative == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Taking from the surplus is said in words as well as red.
                if (taking) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    projectionSentence(projection, money),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (taking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                stringResource(R.string.forecast_surplus_after, money.format(projection.runningTotal)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.forecast?.let {
            Text(basisText(it.basis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
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
                HatchedSwatch(colors[series.slot])
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(money.format(segment.estimate.expected), style = MaterialTheme.typography.bodyLarge)
                    // Left out when there is no spread, as for a rent that never changes.
                    if (segment.estimate.low != segment.estimate.high) {
                        Text(
                            stringResource(R.string.forecast_range_short, money.format(segment.estimate.low), money.format(segment.estimate.high)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
