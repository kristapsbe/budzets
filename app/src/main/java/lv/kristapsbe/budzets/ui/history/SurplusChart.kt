package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.forecast.SurplusProjection
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import java.time.YearMonth
import java.util.Locale

private val SurplusPlotHeight = 180.dp
/** A month not closed yet: a light fill inside an outline of the full colour. */
private const val NOT_CLOSED_ALPHA = 0.35f

/** The surplus chart's scale: around zero, reaching every month's result and running total, forecast ones and their ranges included. */
internal fun surplusScale(months: List<MonthSurplus>, unitMinor: Long, forecast: ChartForecast? = null): ChartScale {
    val values = months.flatMap { listOfNotNull(it.result?.minor, it.runningTotal.minor) } +
        forecast?.surplus.orEmpty().flatMap { listOfNotNull(it.result?.low?.minor, it.result?.high?.minor, it.runningTotal.minor) } +
        listOfNotNull(forecast?.monthEnd?.result?.low?.minor, forecast?.monthEnd?.result?.high?.minor, forecast?.monthEnd?.runningTotal?.minor)
    return ChartScale.around(minOf(0L, values.minOrNull() ?: 0L), maxOf(0L, values.maxOrNull() ?: 0L), unitMinor)
}

/**
 * Each month's result for the saved surplus as a bar above zero (added) or below it (taken),
 * lighter while the month isn't closed, and the running total as a line (Q39–Q42). The month under
 * way is shown as closing it now would leave it (Q45). The months line up with the spending
 * chart's: same slots, same [scroll].
 *
 * With a forecast, a tick on the month under way marks where it will likely end (Q55), the coming
 * months are hatched with a whisker for their likely range, and the total runs on from the tick as
 * a dotted line.
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
    val forecast = state.forecast
    val descriptions = state.surplus.map { surplusDescription(it, state, money, locale) } +
        forecast?.surplus.orEmpty().map { projectionDescription(it, state.currentMonth, money, locale) }
    MonthChartFrame(
        months = state.chartMonths,
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
        class Bar(val path: Path, val positive: Boolean, val closed: Boolean)
        val bars = state.surplus.mapIndexedNotNull { i, month ->
            val result = month.result?.takeUnless { it.isZero } ?: return@mapIndexedNotNull null
            Bar(g.barFromZero(i, result.minor), result.isPositive, month.isClosed)
        }
        val points = state.surplus.mapIndexed { i, month -> Offset(g.center(i), g.y(month.runningTotal.minor)) }
        val line = polyline(points)
        val outline = Stroke(width = g.hairline)
        val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // The forecast: hatched bars after the real months, the tick on the month under way, and a
        // dotted total from the tick on.
        class Projected(val path: Path, val positive: Boolean, val x: Float, val low: Float, val high: Float)
        val offset = state.surplus.size
        val projected = forecast?.surplus.orEmpty().mapIndexedNotNull { j, month ->
            val result = month.result?.takeUnless { it.expected.isZero } ?: return@mapIndexedNotNull null
            val i = offset + j
            Projected(g.barFromZero(i, result.expected.minor), result.expected.isPositive, g.center(i), g.y(result.low.minor), g.y(result.high.minor))
        }
        val currentIndex = state.surplus.indexOfFirst { it.month == state.currentMonth }
        val monthEnd = forecast?.monthEnd?.takeIf { currentIndex >= 0 }
        val dottedStart = when {
            monthEnd != null -> Offset(g.center(currentIndex), g.y(monthEnd.runningTotal.minor))
            currentIndex >= 0 -> points.getOrNull(currentIndex)
            else -> null
        }
        val dottedPoints = forecast?.surplus.orEmpty().mapIndexed { j, month -> Offset(g.center(offset + j), g.y(month.runningTotal.minor)) }
        val dotted = polyline(listOfNotNull(dottedStart) + dottedPoints)
        val dottedStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = forecastDots())
        val tickHalf = g.bar / 2 + 4.dp.toPx()
        val tick = 3.dp.toPx()

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
            projected.forEach { bar ->
                drawHatched(bar.path, if (bar.positive) colors.positive else colors.negative)
                drawWhisker(bar.x, bar.low, bar.high, lineColor)
            }
            monthEnd?.let { end ->
                val x = g.center(currentIndex)
                val y = g.y(end.result.expected.minor)
                drawWhisker(x, g.y(end.result.low.minor), g.y(end.result.high.minor), lineColor)
                // In a ring of the surface, like the dots.
                drawLine(ringColor, Offset(x - tickHalf, y), Offset(x + tickHalf, y), strokeWidth = tick + 4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(lineColor, Offset(x - tickHalf, y), Offset(x + tickHalf, y), strokeWidth = tick, cap = StrokeCap.Round)
            }
            if (dottedPoints.isNotEmpty()) drawPath(dotted, lineColor, style = dottedStroke)
            if (points.size > 1) drawPath(line, lineColor, style = lineStroke)
            points.forEach { drawRingedDot(it, lineColor, ringColor) }
            (listOfNotNull(dottedStart.takeIf { monthEnd != null }) + dottedPoints).forEach { drawRingedDot(it, lineColor, ringColor, hollow = true) }
        }
    }
}

/** The month's result in words: added, taken, would add or take, or nothing. */
@Composable
internal fun surplusSentence(month: MonthSurplus, money: MoneyFormatter): String {
    val result = month.result
    return when {
        result == null -> stringResource(R.string.history_surplus_nothing)
        !month.isClosed && result.isNegative -> stringResource(R.string.history_surplus_would_take, money.format(-result))
        !month.isClosed -> stringResource(R.string.history_surplus_would_add, money.format(result))
        result.isZero -> stringResource(R.string.history_surplus_zero)
        result.isNegative -> stringResource(R.string.history_surplus_taken, money.format(-result))
        else -> stringResource(R.string.history_surplus_added, money.format(result))
    }
}

/**
 * "September 2026: took €125.00 from the surplus. Surplus after this month: €310.00". The month
 * under way adds where it will likely end, when there is a forecast.
 */
@Composable
internal fun surplusDescription(month: MonthSurplus, state: SpendingHistoryUiState.Ready, money: MoneyFormatter, locale: Locale): String {
    val title = monthTitle(month.month, state.currentMonth, locale)
    val sentence = surplusSentence(month, money)
    val after = stringResource(R.string.history_surplus_after, money.format(month.runningTotal))
    val end = state.forecast?.monthEnd?.takeIf { month.month == state.currentMonth }?.let { ". " + monthEndSentence(it.result.expected, money) }
    return "$title: $sentence. $after" + end.orEmpty()
}

/** "At the month's end it would likely add €120.00", or take. */
@Composable
internal fun monthEndSentence(result: Money, money: MoneyFormatter): String =
    if (result.isNegative) stringResource(R.string.forecast_month_end_take, money.format(-result))
    else stringResource(R.string.forecast_month_end_add, money.format(result))

/** A forecast month's likely result in words, or the no-budget sentence. */
@Composable
internal fun projectionSentence(month: SurplusProjection, money: MoneyFormatter): String {
    val result = month.result?.expected
    return when {
        result == null -> stringResource(R.string.history_surplus_nothing)
        result.isNegative -> stringResource(R.string.forecast_surplus_would_take, money.format(-result))
        else -> stringResource(R.string.forecast_surplus_would_add, money.format(result))
    }
}

/** "December 2026 (forecast): Would likely add €50.00 to the surplus. Likely surplus after this month: €400.00" */
@Composable
internal fun projectionDescription(month: SurplusProjection, currentMonth: YearMonth, money: MoneyFormatter, locale: Locale): String =
    monthTitle(month.month, currentMonth, locale) + ": " + projectionSentence(month, money) + ". " +
        stringResource(R.string.forecast_surplus_after, money.format(month.runningTotal))

/** Minor units in one whole unit of a currency with [fractionDigits] (100 for EUR). */
internal fun unitMinor(fractionDigits: Int): Long = (1..fractionDigits).fold(1L) { acc, _ -> acc * 10 }
