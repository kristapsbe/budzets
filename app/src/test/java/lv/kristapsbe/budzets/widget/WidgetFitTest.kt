package lv.kristapsbe.budzets.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Text fitted to the widget's width, measured with real fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFitTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A 2×2 widget about as wide as on the emulator's launcher. */
    private val twoByTwo = DpSize(180.dp, 210.dp)
    private val wide = DpSize(340.dp, 210.dp)

    private fun month(status: PaceStatus = PaceStatus.ON_TRACK, remaining: Long = 686) =
        WidgetState.Month(
            ym("2026-11"), CurrencySpec.DEFAULT, eur(900 - remaining), eur(900), status, 22, null, eur(0),
            DailyAllowance(eur(31, 30), eur(4)),
        )

    @Test
    fun `measures against the width and the font size`() {
        assertTrue(WidgetText.fits(context, "On track", 13.sp, 100.dp))
        assertFalse(WidgetText.fits(context, "Uzmanīgi ar tēriņiem · Atlikušas 22 dienas", 13.sp, 100.dp))
        assertTrue(WidgetText.fits(context, "€686.00 left", 16.sp, 100.dp, bold = true))
        assertFalse(WidgetText.fits(context, "€686.00 left", 22.sp, 100.dp, bold = true))
    }

    @Test
    fun `the largest size that fits, or the smallest`() {
        val sizes = listOf(22.sp, 20.sp, 18.sp, 16.sp)
        assertEquals(22.sp, WidgetText.largestFitting(context, "€686.00 left", 156.dp, sizes, bold = true))
        val latvian = WidgetText.largestFitting(context, "Atlikums: 686,00 €", 156.dp, sizes, bold = true)
        assertTrue("$latvian", latvian < 22.sp)
        assertEquals(16.sp, WidgetText.largestFitting(context, "Atlikums: 1 234 567,00 €", 156.dp, sizes, bold = true))
    }

    @Test
    @Config(qualifiers = "lv-rLV")
    fun `Latvian status at 2×2 puts days left on a line of their own`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(twoByTwo)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("Atbilst plānam\nAtlikušas 22 dienas")).assertExists()
    }

    @Test
    @Config(qualifiers = "lv-rLV")
    fun `Latvian over budget at 2×2 keeps the whole amount and spent of budget`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(twoByTwo)
        setContext(context)
        val over = WidgetState.Month(ym("2026-11"), CurrencySpec.DEFAULT, eur(964), eur(900), PaceStatus.OVER, 22, null, eur(0), null)
        provideComposable { WidgetContent(over) }
        // Wraps rather than ending in "…"; the bar and status follow.
        onNode(hasTextEqualTo("Pārsniegts par 64,00\u00A0€")).assertExists()
        onNode(hasTextEqualTo("Iztērēts 964,00\u00A0€ no 900,00\u00A0€")).assertExists()
        onNode(hasTextEqualTo("Budžets pārsniegts\nAtlikušas 22 dienas")).assertExists()
    }

    @Test
    @Config(qualifiers = "lv-rLV")
    fun `Latvian status stays on one line where it fits`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(wide)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("Atbilst plānam · Atlikušas 22 dienas")).assertExists()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `English at 2×2 keeps one line where it fits`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(twoByTwo)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("On track · 22 days left")).assertExists()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `a longer English status splits too`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(twoByTwo)
        setContext(context)
        provideComposable { WidgetContent(month(status = PaceStatus.WATCH)) }
        onNode(hasTextEqualTo("Watch spending\n22 days left")).assertExists()
    }
}
