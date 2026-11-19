package lv.kristapsbe.budzets.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/** A month's slot: the bar plus the space either side (where the spending chart's ribbons run). */
internal val SlotWidth = 56.dp
internal val BarWidth = 24.dp
internal val BarCorner = 4.dp
/** Above the top tick, so its label isn't cut off. */
private val PlotTopInset = 8.dp
private val AxisLabelGap = 8.dp

/** Where things go in a plot: one slot per month across, amounts on [scale] up the side. */
internal class PlotGeometry(density: Density, size: Size, val scale: ChartScale) : Density by density {
    val slot = SlotWidth.toPx()
    val bar = BarWidth.toPx()
    val corner = BarCorner.toPx()
    val hairline = 1.dp.toPx()
    private val plotTop = PlotTopInset.toPx()
    private val plotHeight = size.height - plotTop

    fun y(minor: Long): Float = plotTop + plotHeight * (1f - scale.fraction(minor))
    fun center(index: Int): Float = slot * index + slot / 2
}

@Composable
internal fun axisLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontFeatureSettings = "tnum",
)

/**
 * How wide the y-axis labels are, the widest of [labels] plus a gap. Charts stacked on one screen
 * share it, so their plots start at the same x and their months line up.
 */
@Composable
internal fun axisWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = axisLabelStyle()
    return with(LocalDensity.current) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() } + AxisLabelGap
}

/** The y-axis labels at [scale]'s ticks, right-aligned against the plot, which stay put while it scrolls. */
@Composable
internal fun YAxisLabels(scale: ChartScale, tickLabels: List<String>, axisWidth: Dp, plotHeight: Dp) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = axisLabelStyle()
    Canvas(Modifier.width(axisWidth).height(plotHeight).clearAndSetSemantics {}) {
        val geometry = PlotGeometry(this, size, scale)
        scale.ticks.forEachIndexed { i, tick ->
            val layout = textMeasurer.measure(tickLabels[i], axisStyle)
            val y = geometry.y(tick)
            drawText(layout, topLeft = Offset(size.width - AxisLabelGap.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
    }
}

/** Hairlines at the scale's ticks across the whole width; zero a step stronger, as the line the marks stand on. */
internal fun DrawScope.drawGrid(geometry: PlotGeometry, gridColor: Color, zeroColor: Color) {
    geometry.scale.ticks.forEach { tick ->
        val y = geometry.y(tick)
        drawLine(if (tick == 0L) zeroColor else gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = geometry.hairline)
    }
}

private val HatchSpacing = 6.dp
private val HatchStroke = 1.dp
private val WhiskerStroke = 1.5.dp
private val WhiskerCap = 8.dp
private const val HATCH_FILL_ALPHA = 0.25f

/**
 * A forecast mark (Q54–Q56): [color] faint, crossed by fine diagonal lines in full. The lines are
 * on one grid across the whole plot, so neighbouring marks' hatching lines up.
 */
internal fun DrawScope.drawHatched(path: Path, color: Color, alpha: Float = 1f) {
    drawPath(path, color, alpha = HATCH_FILL_ALPHA * alpha)
    val bounds = path.getBounds()
    val step = HatchSpacing.toPx()
    val stroke = HatchStroke.toPx()
    clipPath(path) {
        // The lines x + y = k·step, running up and to the right.
        var k = floor((bounds.left + bounds.top) / step)
        val last = (bounds.right + bounds.bottom) / step
        while (k <= last) {
            val c = k * step
            drawLine(color, Offset(c - bounds.bottom, bounds.bottom), Offset(c - bounds.top, bounds.top), strokeWidth = stroke, alpha = alpha)
            k += 1
        }
    }
}

/** A likely range: a line from [low] to [high] (both y, in px) at [x], capped at each end. */
internal fun DrawScope.drawWhisker(x: Float, low: Float, high: Float, color: Color, alpha: Float = 1f) {
    val cap = WhiskerCap.toPx() / 2
    val width = WhiskerStroke.toPx()
    drawLine(color, Offset(x, low), Offset(x, high), strokeWidth = width, alpha = alpha)
    drawLine(color, Offset(x - cap, low), Offset(x + cap, low), strokeWidth = width, alpha = alpha)
    drawLine(color, Offset(x - cap, high), Offset(x + cap, high), strokeWidth = width, alpha = alpha)
}

/** Dots, for a forecast line: on this screen dashes mean "budget". Draw with a round cap. */
internal fun Density.forecastDots(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 4.dp.toPx()))

/**
 * What every month chart has: y-axis labels that stay put, a plot that scrolls sideways with
 * [scroll], a highlighted column for the selected month, gridlines, a tab per month for TalkBack
 * (named by [descriptions]) and month labels underneath. [drawPlot] draws the data over the grid.
 */
@Composable
internal fun MonthChartFrame(
    months: List<YearMonth>,
    selectedMonth: YearMonth?,
    scale: ChartScale,
    tickLabels: List<String>,
    axisWidth: Dp,
    scroll: ScrollState,
    plotHeight: Dp,
    descriptions: List<String>,
    onSelectMonth: (YearMonth) -> Unit,
    locale: Locale,
    modifier: Modifier = Modifier,
    drawPlot: CacheDrawScope.(PlotGeometry) -> DrawResult,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val selectedColor = MaterialTheme.colorScheme.surfaceVariant
    val shortMonth = remember(locale) { DateTimeFormatter.ofPattern("LLL", locale) }
    val selectedIndex = months.indexOf(selectedMonth)

    Row(modifier) {
        YAxisLabels(scale, tickLabels, axisWidth, plotHeight)
        BoxWithConstraints(Modifier.weight(1f)) {
            val contentWidth = max(SlotWidth * months.size, maxWidth)
            Column(Modifier.horizontalScroll(scroll)) {
                Box(
                    Modifier
                        .width(contentWidth)
                        .height(plotHeight)
                        .drawBehind {
                            val geometry = PlotGeometry(this, size, scale)
                            if (selectedIndex >= 0) {
                                drawRoundRect(selectedColor, Offset(geometry.slot * selectedIndex, 0f), Size(geometry.slot, size.height), CornerRadius(8.dp.toPx()))
                            }
                            drawGrid(geometry, gridColor, zeroColor)
                        }
                        .drawWithCache { drawPlot(PlotGeometry(this, size, scale)) },
                ) {
                    Row {
                        months.forEachIndexed { index, month ->
                            Box(
                                Modifier
                                    .width(SlotWidth)
                                    .fillMaxHeight()
                                    .selectable(selected = index == selectedIndex, role = Role.Tab) { onSelectMonth(month) }
                                    .semantics { contentDescription = descriptions[index] },
                            )
                        }
                    }
                }
                // The tabs above already name each month.
                Row(Modifier.clearAndSetSemantics {}) {
                    months.forEachIndexed { index, month ->
                        MonthLabel(month, showYear = index == 0 || month.month == Month.JANUARY, index == selectedIndex, shortMonth)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthLabel(month: YearMonth, showYear: Boolean, selected: Boolean, format: DateTimeFormatter) {
    val style = MaterialTheme.typography.labelSmall.copy(
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Column(Modifier.width(SlotWidth)) {
        Text(format.format(month), style = style, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.width(SlotWidth))
        Text(if (showYear) month.year.toString() else "", style = style, maxLines = 1, modifier = Modifier.width(SlotWidth))
    }
}
