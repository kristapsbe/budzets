package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.MonthStack
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

/** The spending chart's scale: zero to the highest month total or budget. */
internal fun spendingScale(months: List<MonthStack>, unitMinor: Long): ChartScale =
    ChartScale.of(months.maxOf { maxOf(it.total.minor, it.budget?.minor ?: 0) }, unitMinor)

/**
 * Each month's spending as a bar stacked by category, the largest on top, with the same category
 * joined from month to month by a ribbon. A dashed line marks the month's budget. Each month is a
 * tab for TalkBack, described in words.
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
    val budgetColor = MaterialTheme.colorScheme.onSurface
    val descriptions = state.months.map { monthDescription(it, state, money, locale) }
    MonthChartFrame(
        months = state.months.map { it.month },
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
        // Ribbons, then bars, then budget lines, so bars sit on top of the ribbons.
        val gap = SegmentGap.toPx()
        val overhang = BudgetOverhang.toPx()

        /** A segment's top and bottom, less half the gap where it meets another segment. */
        fun extent(stack: MonthStack, index: Int): Pair<Float, Float> {
            val segment = stack.segments[index]
            val bottom = g.y(segment.below.minor) - if (index > 0) gap / 2 else 0f
            val top = g.y(segment.top.minor) + if (index < stack.segments.lastIndex) gap / 2 else 0f
            // A sliver still shows, a hairline thick.
            return minOf(top, bottom - g.hairline) to bottom
        }

        val highlighted = state.highlighted
        val colorOf = state.series.associate { it.key to colors[it.slot] }

        class Ribbon(val path: Path, val color: Color, val isHighlighted: Boolean)
        val ribbons = buildList {
            for (i in 0 until state.months.lastIndex) {
                val from = state.months[i]
                val to = state.months[i + 1]
                from.segments.forEachIndexed { a, segment ->
                    val key = seriesKey(segment.series)
                    val b = to.segments.indexOfFirst { seriesKey(it.series) == key }
                    if (b < 0) return@forEachIndexed
                    val (fromTop, fromBottom) = extent(from, a)
                    val (toTop, toBottom) = extent(to, b)
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

        class Segment(val path: Path, val color: Color, val isHighlighted: Boolean)
        val segments = buildList {
            state.months.forEachIndexed { i, stack ->
                val left = g.center(i) - g.bar / 2
                stack.segments.forEachIndexed { index, segment ->
                    val (top, bottom) = extent(stack, index)
                    val rect = Rect(left, top, left + g.bar, bottom)
                    // Only the top of the bar is rounded; it stands square on the baseline.
                    val radius = if (index == stack.segments.lastIndex) CornerRadius(g.corner) else CornerRadius.Zero
                    val path = Path().apply { addRoundRect(RoundRect(rect, topLeft = radius, topRight = radius, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero)) }
                    val key = seriesKey(segment.series)
                    add(Segment(path, colorOf[key] ?: colors.other, key == highlighted))
                }
            }
        }
        val dashes = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))

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
                drawPath(segment.path, segment.color, alpha = if (highlighted == null || segment.isHighlighted) 1f else BAR_FADED_ALPHA)
            }
            state.months.forEachIndexed { i, stack ->
                val budget = stack.budget ?: return@forEachIndexed
                val y = g.y(budget.minor)
                drawLine(
                    budgetColor,
                    Offset(g.center(i) - g.bar / 2 - overhang, y),
                    Offset(g.center(i) + g.bar / 2 + overhang, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashes,
                )
            }
        }
    }
}

/** "November 2026: €412.00 of €600.00 spent", or with "no budget set". */
@Composable
internal fun monthDescription(stack: MonthStack, state: SpendingHistoryUiState.Ready, money: MoneyFormatter, locale: Locale): String {
    val name = monthTitle(stack.month, state.currentMonth, locale)
    val spent = stack.budget
        ?.let { stringResource(R.string.spent_of_budget, money.format(stack.total), money.format(it)) }
        ?: (stringResource(R.string.spent_amount, money.format(stack.total)) + " · " + stringResource(R.string.status_no_budget))
    return "$name: $spent"
}

/** "November 2026", or "November 2026 (so far)" for the month still under way. */
@Composable
internal fun monthTitle(month: YearMonth, currentMonth: YearMonth, locale: Locale): String {
    val name = month.displayName(locale)
    return if (month == currentMonth) stringResource(R.string.history_so_far, name) else name
}
