package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.BurnDown
import lv.kristapsbe.budzets.domain.forecast.Estimate
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.ui.surplus.signed
import lv.kristapsbe.budzets.ui.theme.chartColors
import lv.kristapsbe.budzets.util.MoneyFormatter
import java.time.YearMonth
import java.util.Locale

private val BurnDownPlotHeight = 180.dp
/** Room either side of the line, so the dots at its ends aren't cut off. */
private val PlotSideInset = 8.dp
private val DayLabelGap = 4.dp
/** A week apart, so the labels never crowd each other at any width. */
private val LabelledDays = listOf(1, 8, 15, 22, 29)
/** The likely range of the rest of the month, a faint wash of the line's colour. */
private const val BAND_ALPHA = 0.12f

/** The row of day numbers under the plot: a line of axis labels and the gap above it. */
@Composable
private fun dayLabelsHeight(): Dp {
    val measurer = rememberTextMeasurer()
    val style = axisLabelStyle()
    return with(LocalDensity.current) { remember(style) { measurer.measure("0", style).size.height }.toDp() } + DayLabelGap
}

/** The burn-down's scale: around zero, from the lowest point left to the most the month could spend. */
internal fun burnDownScale(burnDown: BurnDown, unitMinor: Long): ChartScale {
    val values = listOf(burnDown.start.minor) + burnDown.days.flatMap { listOf(it.available.minor, it.left.minor) } +
        burnDown.projection.orEmpty().flatMap { listOf(it.low.minor, it.high.minor) }
    return ChartScale.around(minOf(0L, values.min()), maxOf(0L, values.max()), unitMinor)
}

/**
 * What is left of the month's budget, day by day (Q50–Q52): a line from what the month started
 * with, down with each day's spending and up on the day a boost was added, to today or the
 * month's end. Below zero it turns red. A dashed pace guide (Q51) runs to zero at the month's end.
 * In the month under way a dotted line and a shaded band carry on to where it will likely end (Q53).
 * The whole chart is one node for TalkBack, described by [description].
 */
@Composable
internal fun BurnDownChart(
    burnDown: BurnDown,
    scale: ChartScale,
    tickLabels: List<String>,
    axisWidth: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors
    val lineColor = MaterialTheme.colorScheme.onSurface
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = axisLabelStyle()
    val days = burnDown.daysInMonth

    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Row {
            YAxisLabels(scale, tickLabels, axisWidth, BurnDownPlotHeight)
            Box(
                Modifier
                    .weight(1f)
                    .height(BurnDownPlotHeight)
                    .drawWithCache {
                        val g = PlotGeometry(this, size, scale)
                        val inset = PlotSideInset.toPx()
                        /** The end of [day]; day 0 is the month's start. */
                        fun x(day: Int): Float = inset + (size.width - 2 * inset) * day / days

                        // The line: each boost a step up at the start of its day, then the day's spending.
                        val line = Path().apply { moveTo(x(0), g.y(burnDown.start.minor)) }
                        class Rise(val x: Float, val from: Float, val to: Float)
                        val rises = mutableListOf<Rise>()
                        // The guide: it rises with each boost and runs to zero at the month's end.
                        val guide = Path().apply { moveTo(x(0), g.y(burnDown.guide(burnDown.start, 0).minor)) }
                        var before = burnDown.start
                        for (day in burnDown.days) {
                            if (day.boostAdded.isPositive) {
                                val from = g.y(before.minor)
                                val to = g.y((before + day.boostAdded).minor)
                                line.lineTo(x(day.day - 1), to)
                                rises += Rise(x(day.day - 1), from, to)
                                guide.lineTo(x(day.day - 1), g.y(burnDown.guide(day.available, day.day - 1).minor))
                            }
                            line.lineTo(x(day.day), g.y(day.left.minor))
                            guide.lineTo(x(day.day), g.y(burnDown.guide(day.available, day.day).minor))
                            before = day.left
                        }
                        guide.lineTo(x(days), g.y(burnDown.guide(burnDown.available, days).minor))
                        val end = Offset(x(burnDown.days.size), g.y(burnDown.left.minor))
                        val zero = g.y(0)

                        // The likely rest of the month: a band from low to high, and a dotted line through it.
                        val projection = burnDown.projection.orEmpty()
                        val from = burnDown.days.size
                        fun points(value: (Estimate) -> Money) = projection.mapIndexed { i, e -> Offset(x(from + i), g.y(value(e).minor)) }
                        val band = polyline(points { it.high } + points { it.low }.asReversed()).apply { close() }
                        val likely = points { it.expected }
                        val dotted = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = forecastDots())

                        val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val guideStroke = Stroke(width = 1.5.dp.toPx(), pathEffect = budgetDashes())
                        val riseWidth = 3.dp.toPx()
                        val endColor = if (burnDown.left.isNegative) colors.negative else lineColor

                        onDrawBehind {
                            drawGrid(g, gridColor, zeroColor)
                            if (projection.size > 1) {
                                drawPath(band, lineColor, alpha = BAND_ALPHA)
                                drawPath(polyline(likely), lineColor, style = dotted)
                            }
                            drawPath(guide, guideColor, style = guideStroke)
                            if (burnDown.days.isNotEmpty()) {
                                // The same line, red below zero: words under the chart say "over" too.
                                clipRect(bottom = zero) { drawPath(line, lineColor, style = lineStroke) }
                                clipRect(top = zero) { drawPath(line, colors.negative, style = lineStroke) }
                            }
                            rises.forEach { rise ->
                                drawLine(colors.positive, Offset(rise.x, rise.from), Offset(rise.x, rise.to), strokeWidth = riseWidth, cap = StrokeCap.Round)
                                drawRingedDot(Offset(rise.x, rise.to), colors.positive, ringColor)
                            }
                            if (projection.size > 1) drawRingedDot(likely.last(), lineColor, ringColor, hollow = true)
                            if (burnDown.days.isNotEmpty()) drawRingedDot(end, endColor, ringColor)
                        }
                    },
            )
        }
        // Day numbers under the middle of their day.
        Row {
            Spacer(Modifier.width(axisWidth))
            Canvas(Modifier.weight(1f).height(dayLabelsHeight())) {
                val inset = PlotSideInset.toPx()
                val dayWidth = (size.width - 2 * inset) / days
                LabelledDays.filter { it <= days }.forEach { day ->
                    val layout = textMeasurer.measure(day.toString(), labelStyle)
                    val center = inset + dayWidth * (day - 0.5f)
                    drawText(layout, topLeft = Offset(center - layout.size.width / 2f, DayLabelGap.toPx()))
                }
            }
        }
    }
}

/**
 * In place of the chart for a month without a budget: [text] where the plot would be, as tall as
 * the plot and its day labels, so the charts below don't move when such a month is selected.
 */
@Composable
internal fun NoBurnDown(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(BurnDownPlotHeight + dayLabelsHeight()), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Started with €1,000.00", the debt named when some came out of the month. */
@Composable
internal fun burnDownStart(burnDown: BurnDown, money: MoneyFormatter): String =
    if (burnDown.debt.isPositive) stringResource(R.string.burn_down_started_after_debt, money.format(burnDown.start), money.format(burnDown.debt))
    else stringResource(R.string.burn_down_started, money.format(burnDown.start))

/** "Boosts +€50.00", or null when no boost was added during the month. */
@Composable
internal fun burnDownBoosts(burnDown: BurnDown, money: MoneyFormatter): String? =
    if (burnDown.boostsAdded.isPositive) stringResource(R.string.burn_down_boosts, signed(burnDown.boostsAdded, money)) else null

/** "€310.00 left" or "Over by €40.00", after the last day shown. */
@Composable
internal fun burnDownLeft(burnDown: BurnDown, money: MoneyFormatter): String =
    if (burnDown.left.isNegative) stringResource(R.string.over_by_amount, money.format(-burnDown.left))
    else stringResource(R.string.remaining_amount, money.format(burnDown.left))

/** What the pace guide says for today; null once the whole month is shown, when it is zero. */
@Composable
internal fun burnDownGuide(burnDown: BurnDown, money: MoneyFormatter): String? =
    if (burnDown.isComplete || burnDown.days.isEmpty()) null
    else stringResource(R.string.burn_down_guide, money.format(burnDown.guide(burnDown.available, burnDown.days.size)))

/** "Likely €120.00 left at the month's end", or over; null without a forecast. */
@Composable
internal fun burnDownLikely(burnDown: BurnDown, money: MoneyFormatter): String? = burnDown.likelyLeft?.let {
    if (it.expected.isNegative) stringResource(R.string.burn_down_likely_over, money.format(-it.expected))
    else stringResource(R.string.burn_down_likely_left, money.format(it.expected))
}

/**
 * "Likely range: -€40.00 to €280.00 left", or "Likely range: over by €20.00 to €90.00" when all of
 * it is over; null without a forecast, or when the range has no spread.
 */
@Composable
internal fun burnDownLikelyRange(burnDown: BurnDown, money: MoneyFormatter): String? = burnDown.likelyLeft?.takeIf { it.low != it.high }?.let {
    if (it.high.isNegative) stringResource(R.string.burn_down_likely_range_over, money.format(-it.high), money.format(-it.low))
    else stringResource(R.string.burn_down_likely_range, money.format(it.low), money.format(it.high))
}

/** "November 2026 (so far): Started with €1,000.00 · Boosts +€50.00. €310.00 left. At an even pace, …" */
@Composable
internal fun burnDownDescription(burnDown: BurnDown, currentMonth: YearMonth, money: MoneyFormatter, locale: Locale): String {
    val start = listOfNotNull(burnDownStart(burnDown, money), burnDownBoosts(burnDown, money)).joinToString(" · ")
    val parts = listOfNotNull(
        start, burnDownLeft(burnDown, money), burnDownGuide(burnDown, money),
        burnDownLikely(burnDown, money), burnDownLikelyRange(burnDown, money),
    )
    return monthTitle(burnDown.month, currentMonth, locale) + ": " + parts.joinToString(". ")
}
