package lv.kristapsbe.budzets.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ym
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class WidgetContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun month(spent: Long = 190, budget: Long = 620, status: PaceStatus = PaceStatus.ON_TRACK, topOver: TopOver? = null) =
        WidgetState.Month(ym("2026-10"), CurrencySpec.DEFAULT, eur(spent), eur(budget), status, 4, topOver, eur(410))

    @Test
    fun `small size shows only what's left`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(SMALL)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("€430.00 left")).assertExists()
        onNode(hasTextEqualTo("October 2026")).assertDoesNotExist()
        onNode(hasContentDescription("Add expense")).assertDoesNotExist()
        onNode(hasText("On track")).assertDoesNotExist()
    }

    @Test
    fun `medium size adds month, spent of budget, status and the quick-add button`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("October 2026")).assertExists()
        onNode(hasTextEqualTo("€190.00 of €620.00 spent")).assertExists()
        onNode(hasTextEqualTo("On track · 4 days left")).assertExists()
        // The label sits on the icon, the action on the button around it.
        onNode(hasContentDescription("Add expense")).assertExists()
        onNode(hasStartActivityClickAction(Intent(context, QuickAddActivity::class.java))).assertExists()
        onNode(hasText("Saved surplus")).assertDoesNotExist()
    }

    @Test
    fun `large size adds the most over-budget category and saved surplus`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(LARGE)
        setContext(context)
        provideComposable { WidgetContent(month(spent = 640, status = PaceStatus.OVER, topOver = TopOver("Groceries", eur(12, 40)))) }
        onNode(hasTextEqualTo("Over by €20.00")).assertExists()
        onNode(hasTextEqualTo("Over budget · 4 days left")).assertExists()
        onNode(hasTextEqualTo("Groceries over by €12.40")).assertExists()
        onNode(hasTextEqualTo("Saved surplus €410.00")).assertExists()
    }

    @Test
    fun `no budget shows spending rather than a misleading remainder`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month(spent = 12, budget = 0, status = PaceStatus.NO_BUDGET)) }
        onNode(hasTextEqualTo("€12.00 spent")).assertExists()
        onNode(hasTextEqualTo("No budget set · 4 days left")).assertExists()
    }

    @Test
    fun `before setup it says what to do`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(WidgetState.NotSetUp) }
        onNode(hasTextEqualTo("Open Budzets to set up categories and a budget.")).assertExists()
    }
}
