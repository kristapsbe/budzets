package lv.kristapsbe.budzets.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colours for chart series. A fixed categorical palette rather than the theme's colours: dynamic
 * colour gives a handful of related tones, and a chart needs hues that stay apart, including for
 * colour-blind readers. The order matters: neighbours were chosen to stay distinguishable. Light
 * and dark are the same eight hues, each stepped for its surface.
 */
class ChartColors(
    private val series: List<Color>,
    val other: Color,
    /** Above zero in a chart of gains and losses: the cool pole of the diverging pair. */
    val positive: Color,
    /** Below zero: the warm pole. */
    val negative: Color,
) {
    /** The colour for palette [slot]; -1 (the folded categories) and anything past the end get [other]. */
    operator fun get(slot: Int): Color = series.getOrNull(slot) ?: other

    companion object {
        val Light = ChartColors(
            listOf(
                Color(0xFF2A78D6), // blue
                Color(0xFFEB6834), // orange
                Color(0xFF1BAF7A), // aqua
                Color(0xFFEDA100), // yellow
                Color(0xFFE87BA4), // magenta
                Color(0xFF008300), // green
                Color(0xFF6250D6), // violet
                Color(0xFFE34948), // red
            ),
            other = Color(0xFF898781),
            positive = Color(0xFF2A78D6),
            negative = Color(0xFFE34948),
        )
        val Dark = ChartColors(
            listOf(
                Color(0xFF3987E5),
                Color(0xFFD95926),
                Color(0xFF199E70),
                Color(0xFFC98500),
                Color(0xFFD55181),
                Color(0xFF008300),
                Color(0xFF9085E9),
                Color(0xFFE66767),
            ),
            other = Color(0xFF898781),
            positive = Color(0xFF3987E5),
            negative = Color(0xFFE66767),
        )
    }
}

/** The palette for the current theme, judged by its surface, so it follows the app's own light/dark choice. */
val chartColors: ChartColors
    @Composable
    @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) ChartColors.Dark else ChartColors.Light
