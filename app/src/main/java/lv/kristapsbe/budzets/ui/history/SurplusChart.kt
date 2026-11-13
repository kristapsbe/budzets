package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import java.time.YearMonth
import java.util.Locale

private val SurplusPlotHeight = 180.dp
/** A month not closed yet: a light fill inside an outline of the full colour. */
private const val NOT_CLOSED_ALPHA = 0.35f

/** The surplus chart's scale: around zero, reaching every month's result and running total. */
internal fun surplusScale(months: List<MonthSurplus>, unitMinor: Long): ChartScale {
    val values = months.flatMap { listOfNotNull(it.result?.minor, it.runningTotal?.minor) }
    return ChartScale.around(minOf(0L, values.minOrNull() ?: 0L), maxOf(0L, values.maxOrNull() ?: 0L), unitMinor)
}

/**
 * Each finished month's result for the saved surplus as a bar above zero (added) or below it
 * (taken), lighter while the month isn't closed, and the running total as a line (Q39–Q42). The
 * months line up with the spending chart's: same slots, same [scroll].
 */
@Composable
internal fun SurplusChart(
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
    val lineColor = MaterialTheme.colorScheme.onSurface
    val ringColor = MaterialTheme.colorScheme.surface
    val descriptions = state.surplus.map { surplusDescription(it, state.currentMonth, money, locale) }
    MonthChartFrame(
        months = state.surplus.map { it.month },
        selectedMonth = state.selectedMonth,
        scale = scale,
        tickLabels = tickLabels,
        axisWidth = axisWidth,
        scroll = scroll,
        plotHeight = SurplusPlotHeight,
        descriptions = descriptions,
        onSelectMonth = onSelectMonth,
        locale = locale,
        modifier = modifier,
    ) { g ->
        val zero = g.y(0)
        class Bar(val path: Path, val positive: Boolean, val closed: Boolean)
        val bars = state.surplus.mapIndexedNotNull { i, month ->
            val result = month.result?.takeUnless { it.isZero } ?: return@mapIndexedNotNull null
            val left = g.center(i) - g.bar / 2
            val end = g.y(result.minor)
            // Rounded at the end away from zero, square where it meets the zero line.
            val path = if (result.isPositive) {
                Path().apply { addRoundRect(RoundRect(Rect(left, minOf(end, zero - g.hairline), left + g.bar, zero), topLeft = CornerRadius(g.corner), topRight = CornerRadius(g.corner))) }
            } else {
                Path().apply { addRoundRect(RoundRect(Rect(left, zero, left + g.bar, maxOf(end, zero + g.hairline)), bottomLeft = CornerRadius(g.corner), bottomRight = CornerRadius(g.corner))) }
            }
            Bar(path, result.isPositive, month.isClosed)
        }
        val points = state.surplus.mapIndexedNotNull { i, month -> month.runningTotal?.let { Offset(g.center(i), g.y(it.minor)) } }
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val outline = Stroke(width = g.hairline)
        val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dot = 4.dp.toPx()
        val ring = 2.dp.toPx()

        onDrawBehind {
            bars.forEach { bar ->
                val color = if (bar.positive) colors.positive else colors.negative
                if (bar.closed) {
                    drawPath(bar.path, color)
                } else {
                    drawPath(bar.path, color, alpha = NOT_CLOSED_ALPHA)
                    drawPath(bar.path, color, style = outline)
                }
            }
            if (points.size > 1) drawPath(line, lineColor, style = lineStroke)
            points.forEach { p ->
                // A ring of the surface keeps each dot clear of the bar and line behind it.
                drawCircle(ringColor, radius = dot + ring, center = p)
                drawCircle(lineColor, radius = dot, center = p)
            }
        }
    }
}

/** The month's result in words: added, taken, would add or take, nothing, or not yet. */
@Composable
internal fun surplusSentence(month: MonthSurplus, currentMonth: YearMonth, money: MoneyFormatter): String {
    val result = month.result
    return when {
        month.runningTotal == null && month.month >= currentMonth -> stringResource(R.string.history_surplus_pending)
        result == null -> stringResource(R.string.history_surplus_nothing)
        !month.isClosed && result.isNegative -> stringResource(R.string.history_surplus_would_take, money.format(-result))
        !month.isClosed -> stringResource(R.string.history_surplus_would_add, money.format(result))
        result.isZero -> stringResource(R.string.history_surplus_zero)
        result.isNegative -> stringResource(R.string.history_surplus_taken, money.format(-result))
        else -> stringResource(R.string.history_surplus_added, money.format(result))
    }
}

/** "September 2026: took €125.00 from the surplus. Surplus after this month: €310.00" */
@Composable
internal fun surplusDescription(month: MonthSurplus, currentMonth: YearMonth, money: MoneyFormatter, locale: Locale): String {
    val title = monthTitle(month.month, currentMonth, locale)
    val sentence = surplusSentence(month, currentMonth, money)
    val after = month.runningTotal?.let { ". " + stringResource(R.string.history_surplus_after, money.format(it)) }.orEmpty()
    return "$title: $sentence$after"
}

/** Minor units in one whole unit of a currency with [fractionDigits] (100 for EUR). */
internal fun unitMinor(fractionDigits: Int): Long = (1..fractionDigits).fold(1L) { acc, _ -> acc * 10 }
