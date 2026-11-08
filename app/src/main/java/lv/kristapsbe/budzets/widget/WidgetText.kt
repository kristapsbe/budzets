package lv.kristapsbe.budzets.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * Whether widget text fits on one line. The launcher draws the widget, so text can't be measured
 * there. It is measured here instead, with the system font and the device's font scale, against
 * a little less than the width, in case a launcher's font runs wider.
 */
internal object WidgetText {

    /** Share of the width the text may take. */
    private const val MARGIN = 0.94f

    fun fits(context: Context, text: String, fontSize: TextUnit, width: Dp, bold: Boolean = false): Boolean {
        val metrics = context.resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize.value, metrics)
        }
        val available = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width.value, metrics) * MARGIN
        return paint.measureText(text) <= available
    }

    /** The first of [sizes], largest first, at which [text] fits; the last one if none does. */
    fun largestFitting(context: Context, text: String, width: Dp, sizes: List<TextUnit>, bold: Boolean = false): TextUnit =
        sizes.firstOrNull { fits(context, text, it, width, bold) } ?: sizes.last()
}
