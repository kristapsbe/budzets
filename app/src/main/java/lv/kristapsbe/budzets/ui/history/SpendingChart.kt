package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.MonthStack
import lv.kristapsbe.budzets.domain.forecast.Estimate
import lv.kristapsbe.budzets.domain.forecast.ForecastStack
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName
import java.time.YearMonth
import java.util.Locale

private val SpendingPlotHeight = 220.dp
/** Surface showing between the segments of a bar. */
private val SegmentGap = 2.dp
private val BudgetOverhang = 6.dp

private const val RIBBON_ALPHA = 0.3f
private const val RIBBON_HIGHLIGHT_ALPHA = 0.55f
private const val RIBBON_FADED_ALPHA = 0.08f
private const val BAR_FADED_ALPHA = 0.3f

/** The spending chart's scale: zero to the highest month total, budget or likely high of a forecast. */
internal fun spendingScale(months: List<MonthStack>, unitMinor: Long, forecast: ChartForecast? = null): ChartScale {
    val real = months.maxOf { maxOf(it.total.minor, it.budget?.minor ?: 0) }
    val ahead = forecast?.stacks.orEmpty().maxOfOrNull { maxOf(it.total.high.minor, it.budget?.minor ?: 0) } ?: 0
    return ChartScale.of(maxOf(real, ahead, forecast?.currentEnd?.high?.minor ?: 0), unitMinor)
}

/**
 * Each month's spending as a bar stacked by category, the largest on top, with the same category
 * joined from month to month by a ribbon. A dashed line marks the month's budget. Each month is a
 * tab for TalkBack, described in words.
 *
 * The forecast months follow (Q54–Q56): hatched bars stacked by band the same way, with a whisker
 * for the likely range of the total, or of the highlighted band. The month under way gets a hatched
 * extension up to where it will likely end.
 */
@Composable
internal fun SpendingChart(
    state: SpendingHistoryUiState.Ready,
    scale: ChartScale,
    tickLabels: List<String>,
    axisWidth: Dp,
    scroll: ScrollState,
    money: MoneyFormatter,
    locale: Locale,
    onSelectMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors
    // The budget lines and the whiskers.
    val budgetColor = MaterialTheme.colorScheme.onSurface
    val forecast = state.forecast
    val descriptions = state.months.map { monthDescription(it, state, money, locale) } +
        forecast?.stacks.orEmpty().map { forecastDescription(it, state.currentMonth, money, locale) }
    MonthChartFrame(
        months = state.chartMonths,
        selectedMonth = state.selectedMonth,
        scale = scale,
        tickLabels = tickLabels,
        axisWidth = axisWidth,
        scroll = scroll,
        plotHeight = SpendingPlotHeight,
        descriptions = descriptions,
        onSelectMonth = onSelectMonth,
        locale = locale,
        modifier = modifier,
    ) { g ->
        // Ribbons, then bars, then budget lines and whiskers, so bars sit on top of the ribbons.
        val gap = SegmentGap.toPx()
        val overhang = BudgetOverhang.toPx()
        val highlighted = state.highlighted
        val colorOf = state.series.associate { it.key to colors[it.slot] }
        val offset = state.months.size

        /** A segment's top and bottom, less half the gap where it meets another. A sliver still shows, a hairline thick. */
        fun extent(below: Money, top: Money, index: Int, count: Int): Pair<Float, Float> {
            val bottom = g.y(below.minor) - if (index > 0) gap / 2 else 0f
            val upper = g.y(top.minor) + if (index < count - 1) gap / 2 else 0f
            return minOf(upper, bottom - g.hairline) to bottom
        }

        class Ribbon(val path: Path, val color: Color, val isHighlighted: Boolean)
        val ribbons = buildList {
            for (i in 0 until state.months.lastIndex) {
                val from = state.months[i].segments
                val to = state.months[i + 1].segments
                from.forEachIndexed { a, segment ->
                    val key = seriesKey(segment.series)
                    val b = to.indexOfFirst { seriesKey(it.series) == key }
                    if (b < 0) return@forEachIndexed
                    val (fromTop, fromBottom) = extent(segment.below, segment.top, a, from.size)
                    val (toTop, toBottom) = extent(to[b].below, to[b].top, b, to.size)
                    val x0 = g.center(i) + g.bar / 2
                    val x1 = g.center(i + 1) - g.bar / 2
                    val mid = (x0 + x1) / 2
                    val path = Path().apply {
                        moveTo(x0, fromTop)
                        cubicTo(mid, fromTop, mid, toTop, x1, toTop)
                        lineTo(x1, toBottom)
                        cubicTo(mid, toBottom, mid, fromBottom, x0, fromBottom)
                        close()
                    }
                    add(Ribbon(path, colorOf[key] ?: colors.other, key == highlighted))
                }
            }
        }.sortedBy { it.isHighlighted } // the highlighted band is drawn last, over the others

        // Real months' segments, then the forecast months' (hatched). Only a bar's top is rounded.
        class Segment(val path: Path, val color: Color, val isHighlighted: Boolean, val isForecast: Boolean)
        val segments = buildList {
            fun stack(i: Int, parts: List<Triple<CategoryId?, Money, Money>>, isForecast: Boolean) =
                parts.forEachIndexed { index, (series, below, top) ->
                    val (y0, y1) = extent(below, top, index, parts.size)
                    val key = seriesKey(series)
                    add(Segment(g.barPath(i, y0, y1, roundTop = index == parts.lastIndex), colorOf[key] ?: colors.other, key == highlighted, isForecast))
                }
            state.months.forEachIndexed { i, month -> stack(i, month.segments.map { Triple(it.series, it.below, it.top) }, isForecast = false) }
            forecast?.stacks?.forEachIndexed { j, month -> stack(offset + j, month.segments.map { Triple(it.series, it.below, it.top) }, isForecast = true) }
        }

        // A forecast month's whisker is the highlighted band's range, or with none highlighted the whole month's.
        class Whisker(val x: Float, val low: Float, val high: Float)
        val whiskers = forecast?.stacks.orEmpty().mapIndexedNotNull { j, stack ->
            val band = highlighted?.let { key -> stack.segments.firstOrNull { seriesKey(it.series) == key } }
            val x = g.center(offset + j)
            when {
                band != null -> Whisker(x, g.y((band.below + band.estimate.low).minor), g.y((band.below + band.estimate.high).minor))
                highlighted == null && stack.segments.isNotEmpty() -> Whisker(x, g.y(stack.total.low.minor), g.y(stack.total.high.minor))
                else -> null
            }
        }.toMutableList()

        // The month under way: what it will likely add before it ends, on top of what is spent.
        val currentIndex = state.months.indexOfFirst { it.month == state.currentMonth }
        val end = forecast?.currentEnd
        val extension = if (end != null && currentIndex >= 0) {
            if (highlighted == null) whiskers += Whisker(g.center(currentIndex), g.y(end.low.minor), g.y(end.high.minor))
            val spent = state.months[currentIndex].total
            val bottom = g.y(spent.minor) - if (spent.isPositive) gap / 2 else 0f
            if (end.expected > spent) g.barPath(currentIndex, minOf(g.y(end.expected.minor), bottom - g.hairline), bottom, roundTop = true) else null
        } else null

        val budgets = state.months.mapIndexedNotNull { i, month -> month.budget?.let { i to it } } +
            forecast?.stacks.orEmpty().mapIndexedNotNull { j, stack -> stack.budget?.let { offset + j to it } }
        val dashes = budgetDashes()

        onDrawBehind {
            ribbons.forEach { ribbon ->
                val alpha = when {
                    highlighted == null -> RIBBON_ALPHA
                    ribbon.isHighlighted -> RIBBON_HIGHLIGHT_ALPHA
                    else -> RIBBON_FADED_ALPHA
                }
                drawPath(ribbon.path, ribbon.color, alpha = alpha)
            }
            segments.forEach { segment ->
                val alpha = if (highlighted == null || segment.isHighlighted) 1f else BAR_FADED_ALPHA
                if (segment.isForecast) drawHatched(segment.path, segment.color, alpha) else drawPath(segment.path, segment.color, alpha = alpha)
            }
            extension?.let { drawHatched(it, colors.other, alpha = if (highlighted == null) 1f else BAR_FADED_ALPHA) }
            budgets.forEach { (i, budget) ->
                val y = g.y(budget.minor)
                drawLine(
                    budgetColor,
                    Offset(g.center(i) - g.bar / 2 - overhang, y),
                    Offset(g.center(i) + g.bar / 2 + overhang, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashes,
                )
            }
            whiskers.forEach { drawWhisker(it.x, it.low, it.high, budgetColor) }
        }
    }
}

/**
 * "November 2026: €412.00 of €600.00 spent", or with "no budget set". The month under way adds
 * where it will likely end, when there is a forecast.
 */
@Composable
internal fun monthDescription(stack: MonthStack, state: SpendingHistoryUiState.Ready, money: MoneyFormatter, locale: Locale): String {
    val name = monthTitle(stack.month, state.currentMonth, locale)
    val spent = stack.budget
        ?.let { stringResource(R.string.spent_of_budget, money.format(stack.total), money.format(it)) }
        ?: (stringResource(R.string.spent_amount, money.format(stack.total)) + " · " + stringResource(R.string.status_no_budget))
    val end = state.forecast?.currentEnd?.takeIf { stack.month == state.currentMonth }
        ?.let { ". " + stringResource(R.string.forecast_month_end_spent, money.format(it.expected)) }
    return "$name: $spent" + end.orEmpty()
}

/** "December 2026 (forecast): Likely €850.00 of €900.00 spent. Likely range €700.00 to €1,000.00" */
@Composable
internal fun forecastDescription(stack: ForecastStack, currentMonth: YearMonth, money: MoneyFormatter, locale: Locale): String =
    monthTitle(stack.month, currentMonth, locale) + ": " + forecastSpent(stack, money) + forecastRange(stack.total, money)?.let { ". $it" }.orEmpty()

/** "Likely €850.00 of €900.00 spent", or "Likely €850.00 spent · No budget set". */
@Composable
internal fun forecastSpent(stack: ForecastStack, money: MoneyFormatter): String =
    stack.budget?.let { stringResource(R.string.forecast_spent_of_budget, money.format(stack.total.expected), money.format(it)) }
        ?: (stringResource(R.string.forecast_spent, money.format(stack.total.expected)) + " · " + stringResource(R.string.status_no_budget))

/** "Likely range €700.00 to €1,000.00"; null when there is no spread to tell (a flat rent, say). */
@Composable
internal fun forecastRange(estimate: Estimate, money: MoneyFormatter): String? =
    if (estimate.low == estimate.high) null
    else stringResource(R.string.forecast_range, money.format(estimate.low), money.format(estimate.high))

/** "November 2026", "November 2026 (so far)" for the month under way, "December 2026 (forecast)" after it. */
@Composable
internal fun monthTitle(month: YearMonth, currentMonth: YearMonth, locale: Locale): String {
    val name = month.displayName(locale)
    return when {
        month == currentMonth -> stringResource(R.string.history_so_far, name)
        month > currentMonth -> stringResource(R.string.forecast_month, name)
        else -> name
    }
}
