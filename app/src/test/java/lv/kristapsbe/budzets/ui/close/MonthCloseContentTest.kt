package lv.kristapsbe.budzets.ui.close

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.surplus.PocketLabel
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonthCloseContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val food = CategoryId("food")
    private val fun_ = CategoryId("fun")

    private fun lines(pendingFood: Money = Money.ZERO) = listOf(
        CloseLine(PocketLabel(SurplusPocket.Category(food), "Food", false), CategorySummary(food, eur(500), eur(50), eur(450)), eur(100), pendingFood),
        CloseLine(PocketLabel(SurplusPocket.Category(fun_), "Fun", false), CategorySummary(fun_, null, Money.ZERO, eur(30)), eur(-30), Money.ZERO),
        CloseLine(PocketLabel(SurplusPocket.Unallocated, null, false), null, eur(500), Money.ZERO),
    )

    private fun ready(status: MonthStatus, current: Boolean = false, pendingFood: Money = Money.ZERO) = MonthCloseUiState.Ready(
        month = ym("2026-03"),
        currency = CurrencySpec.DEFAULT,
        isCurrentMonth = current,
        canClose = true,
        status = status,
        closedAt = if (status == MonthStatus.OPEN) null else Instant.parse("2026-04-02T10:00:00Z"),
        revision = if (status == MonthStatus.OPEN) null else 1,
        wasReopened = false,
        hasBudget = true,
        lines = lines(pendingFood),
        netEffect = eur(570),
        pendingNet = pendingFood,
        isWorking = false,
    )

    private fun show(state: MonthCloseUiState, actions: MonthCloseActions = MonthCloseActions()) {
        compose.setContent { BudzetsTheme { MonthCloseContent(state, actions) } }
    }

    @Test
    fun `open month explains each line, including that unbudgeted spending becomes a debit`() {
        var closed = 0
        show(ready(MonthStatus.OPEN), MonthCloseActions(onCloseMonth = { closed++ }))
        compose.onNodeWithText("Close March 2026").assertIsDisplayed() // title
        compose.onNodeWithText("Budget €500.00 + €50.00 boost · spent €450.00").assertIsDisplayed()
        compose.onNodeWithText("+€100.00 saved").assertIsDisplayed()
        compose.onNodeWithText("No budget, so its €30.00 spending is recorded as a debit").assertIsDisplayed()
        compose.onNodeWithText("−€30.00 debit").assertIsDisplayed()

        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Unallocated"))
        compose.onNodeWithText("Overall budget not assigned to any category").assertIsDisplayed()
        list.performScrollToNode(hasText("+€570.00 saved"))
        list.performScrollToNode(hasText("Close month"))
        compose.onNodeWithText("Close month").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `changed month shows only the difference and offers closing again`() {
        var closed = 0
        show(ready(MonthStatus.CHANGED, pendingFood = eur(-20)), MonthCloseActions(onCloseMonth = { closed++ }))
        compose.onNodeWithText("Expenses changed since this month was closed. Closing it again records only the differences shown.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("−€20.00 debit")[0].assertIsDisplayed() // the line; the net change says the same
        compose.onNodeWithText("Total for this month after closing: €100.00").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Net change"))
        list.performScrollToNode(hasText("Close again"))
        compose.onNodeWithText("Close again").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `closed month can be reopened after confirming`() {
        var reopened = 0
        show(ready(MonthStatus.CLOSED), MonthCloseActions(onReopen = { reopened++ }))
        compose.onNodeWithText("Closed on Apr 2, 2026. Its surplus is recorded below.").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Reopen month"))
        compose.onNodeWithText("Reopen month").performClick()
        compose.onNodeWithText("Reopen March 2026?").assertIsDisplayed()
        assertEquals(0, reopened)
        compose.onNode(hasText("Reopen month") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, reopened)
    }

    @Test
    fun `closing the current month early is explained`() {
        show(ready(MonthStatus.OPEN, current = true))
        compose.onNodeWithText("March 2026 isn’t over yet. You can close it now; expenses added later are recorded by closing it again.")
            .assertIsDisplayed()
    }
}
