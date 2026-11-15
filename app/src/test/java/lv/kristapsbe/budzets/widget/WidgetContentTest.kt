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
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
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

    private val tenADay = DailyAllowance(eur(10), eur(4))

    private fun month(
        spent: Long = 190,
        budget: Long = 620,
        status: PaceStatus = PaceStatus.ON_TRACK,
        topOver: TopOver? = null,
        daily: DailyAllowance? = tenADay,
        debt: Long = 0,
    ) = WidgetState.Month(ym("2026-10"), CurrencySpec.DEFAULT, eur(spent), eur(budget), status, 4, topOver, eur(410), daily, eur(debt), carried = eur(debt))

    @Test
    fun `small size shows only what's left`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(SMALL)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("€430.00 left")).assertExists()
        onNode(hasTextEqualTo("October 2026")).assertDoesNotExist()
        onNode(hasContentDescription("Add expense")).assertDoesNotExist()
        onNode(hasText("On track")).assertDoesNotExist()
        onNode(hasText("a day")).assertDoesNotExist()
    }

    @Test
    fun `medium size adds month, daily amount, status and the quick-add button`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("October 2026")).assertExists()
        // Two lines: both on one would be cut off at this width.
        onNode(hasTextEqualTo("€10.00 a day")).assertExists()
        onNode(hasTextEqualTo("€6.00 left today")).assertExists()
        // In place of "spent of"; the bar still describes it.
        onNode(hasTextEqualTo("€190.00 of €620.00 spent")).assertDoesNotExist()
        onNode(hasContentDescription("€190.00 of €620.00 spent")).assertExists()
        onNode(hasTextEqualTo("On track · 4 days left")).assertExists()
        // The label sits on the icon, the action on the button around it.
        onNode(hasContentDescription("Add expense")).assertExists()
        onNode(hasStartActivityClickAction(Intent(context, QuickAddActivity::class.java))).assertExists()
        onNode(hasText("Saved surplus")).assertDoesNotExist()
    }

    @Test
    fun `medium size without a daily amount shows spent of budget`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month(spent = 640, status = PaceStatus.OVER, daily = null)) }
        onNode(hasTextEqualTo("€640.00 of €620.00 spent")).assertExists()
        onNode(hasText("a day")).assertDoesNotExist()
    }

    @Test
    fun `today over its amount is said in words`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month(daily = DailyAllowance(eur(10), eur(13)))) }
        onNode(hasTextEqualTo("€3.00 over today")).assertExists()
    }

    @Test
    @Config(qualifiers = "lv-rLV")
    fun `on a line of its own, what's left today starts with a capital`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(month()) }
        onNode(hasText("Šodien vēl ")).assertExists()
    }

    @Test
    fun `large size shows spent of budget and the daily amount`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(LARGE)
        setContext(context)
        provideComposable { WidgetContent(month()) }
        onNode(hasTextEqualTo("€190.00 of €620.00 spent")).assertExists()
        onNode(hasTextEqualTo("€10.00 a day · €6.00 left today")).assertExists()
        onNode(hasTextEqualTo("Saved surplus €410.00")).assertExists()
    }

    @Test
    fun `large size adds the most over-budget category and saved surplus`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(LARGE)
        setContext(context)
        provideComposable { WidgetContent(month(spent = 640, status = PaceStatus.OVER, topOver = TopOver("Groceries", eur(12, 40)), daily = null)) }
        onNode(hasTextEqualTo("Over by €20.00")).assertExists()
        onNode(hasTextEqualTo("Over budget · 4 days left")).assertExists()
        onNode(hasTextEqualTo("Groceries over by €12.40")).assertExists()
        onNode(hasTextEqualTo("Saved surplus €410.00")).assertExists()
    }

    @Test
    fun `debt comes out of what's left, and takes the saved surplus line`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(LARGE)
        setContext(context)
        provideComposable { WidgetContent(month(debt = 40)) }
        onNode(hasTextEqualTo("€390.00 left")).assertExists()
        onNode(hasTextEqualTo("€190.00 of €580.00 spent")).assertExists()
        onNode(hasTextEqualTo("Debt €40.00")).assertExists()
        onNode(hasText("Saved surplus")).assertDoesNotExist()
    }

    @Test
    fun `no budget shows spending rather than a misleading remainder`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(MEDIUM)
        setContext(context)
        provideComposable { WidgetContent(month(spent = 12, budget = 0, status = PaceStatus.NO_BUDGET, daily = null)) }
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
