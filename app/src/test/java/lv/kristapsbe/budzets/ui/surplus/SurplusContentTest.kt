package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.PaceCalculator
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.PocketBreakdown
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.overview.CategoryRow
import lv.kristapsbe.budzets.ui.overview.CloseStatus
import lv.kristapsbe.budzets.ui.overview.OverviewActions
import lv.kristapsbe.budzets.ui.overview.OverviewContent
import lv.kristapsbe.budzets.ui.overview.OverviewUiState
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/** Surplus tab, boost editor, and the overview's closing reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SurplusContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val food = PocketLabel(SurplusPocket.Category(CategoryId("food")), "Food", false)
    private val fun_ = PocketLabel(SurplusPocket.Category(CategoryId("fun")), "Fun", false)
    private val unallocated = PocketLabel(SurplusPocket.Unallocated, null, false)

    private fun ready(filter: SurplusPocket? = null) = SurplusUiState.Ready(
        currency = CurrencySpec.DEFAULT,
        total = eur(545),
        pockets = listOf(
            PocketRow(food, PocketBreakdown(eur(100), eur(50), eur(-25))),
            PocketRow(fun_, PocketBreakdown(eur(-30))),
            PocketRow(unallocated, PocketBreakdown(eur(500), eur(-50))),
        ),
        boosts = listOf(BoostRow(BoostId("b1"), ym("2026-05"), fun_, food, eur(25), canRelease = true)),
        history = listOf(
            HistoryRow(LedgerEntryId("h1"), HistoryKind.BOOST_RESERVED, LocalDate.of(2026, 4, 15), food, eur(-25), ym("2026-05"), fun_, null),
            HistoryRow(LedgerEntryId("h2"), HistoryKind.MOVED_IN, LocalDate.of(2026, 4, 15), food, eur(50), null, unallocated, "top up"),
            HistoryRow(LedgerEntryId("h3"), HistoryKind.CLOSE_DEBIT, LocalDate.of(2026, 4, 15), fun_, eur(-30), ym("2026-03"), null, null),
        ),
        filter = filter,
        hasAvailable = true,
    )

    @Test
    fun `surplus tab shows balances, how they arose, boosts and history`() {
        var released: BoostId? = null
        var moveFrom: SurplusPocket? = SurplusPocket.Unallocated
        compose.setContent { BudzetsTheme { SurplusContent(ready(), SurplusActions(onRelease = { released = it }, onMove = { moveFrom = it })) } }

        compose.onNodeWithText("€545.00").assertIsDisplayed()
        compose.onNodeWithText("Closed months +€100.00 · Moved +€50.00 · Boosts −€25.00").assertIsDisplayed()
        compose.onNodeWithText("Move surplus").performClick()
        assertEquals(null, moveFrom)

        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("−€30.00 debit"))
        compose.onNodeWithText("Overspent. Later surplus or a transfer in repays it.").assertIsDisplayed()

        list.performScrollToNode(hasText("+€25.00 for Fun, May 2026"))
        compose.onNodeWithText("From Food").assertIsDisplayed()
        compose.onNodeWithText("Release").performClick()
        assertEquals(BoostId("b1"), released)

        list.performScrollToNode(hasText("Boost for Fun, May 2026"))
        list.performScrollToNode(hasText("Moved from Unallocated"))
        compose.onNodeWithText("Wed, Apr 15 · Food · top up", substring = true).assertIsDisplayed()
        list.performScrollToNode(hasText("Overspent in March 2026"))
    }

    @Test
    fun `boost editor previews the new budget and refuses a closed month`() {
        val choice = PocketChoice(food, eur(100))
        val editing = BoostState.Editing(
            currency = CurrencySpec.DEFAULT,
            form = BoostForm(CategoryId("food"), food.pocket, "40", ""),
            month = ym("2026-04"),
            isCurrentMonth = true,
            monthClosed = true,
            destinations = listOf(choice),
            sources = listOf(choice),
            currentBudget = eur(300),
            amount = eur(40),
            amountError = null,
            exceedsAvailable = null,
            missingDestination = false,
            missingSource = false,
        )
        var all = 0
        compose.setContent { BudzetsTheme { BoostContent(editing, BoostActions(onAll = { all++ })) } }
        compose.onNodeWithText("April 2026 is closed, so its budget can’t be boosted.").assertIsDisplayed()
        compose.onNode(hasText("Boost") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("All (€100.00)").performScrollTo().performClick()
        assertEquals(1, all)
        compose.onNodeWithText("Budget for Food in April 2026: €300.00 + €40.00 = €340.00").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `move editor reports what's typed`() {
        var typed = ""
        val editing = MoveSurplusState.Editing(
            currency = CurrencySpec.DEFAULT,
            form = MoveForm(food.pocket),
            sources = listOf(PocketChoice(food, eur(100))),
            destinations = listOf(PocketChoice(fun_, eur(-30)), PocketChoice(unallocated, eur(450))),
            amountError = null,
            exceedsAvailable = eur(100),
            missingFrom = false,
            missingTo = true,
        )
        compose.setContent { BudzetsTheme { MoveSurplusContent(editing, MoveSurplusActions(onAmountChange = { typed = it })) } }
        compose.onNodeWithText("Food · €100.00").assertIsDisplayed()
        compose.onNodeWithText("Choose one").assertIsDisplayed()
        compose.onNodeWithText("Only €100.00 is available").assertIsDisplayed()
        compose.onNodeWithTag(SURPLUS_AMOUNT_TAG).performTextInput("7")
        assertEquals("7", typed)
    }

    @Test
    fun `overview reminds about an unclosed past month and opens its review`() {
        var reviewed: YearMonth? = null
        val summary = MonthSummary(ym("2026-04"), eur(100), false, emptyMap())
        val state = OverviewUiState.Ready(
            month = ym("2026-04"),
            isCurrentMonth = true,
            currency = CurrencySpec.DEFAULT,
            summary = summary,
            pace = PaceCalculator.pace(summary, LocalDate.of(2026, 4, 15)),
            rows = emptyList<CategoryRow>(),
            closeStatus = CloseStatus.OPEN,
            totalSurplus = eur(0),
            hasCategories = true,
            unclosedPastMonths = listOf(ym("2026-03"), ym("2026-02")),
            canClose = true,
        )
        compose.setContent { BudzetsTheme { OverviewContent(state, OverviewActions(onReviewClose = { reviewed = it })) } }
        compose.onNodeWithText("March 2026 and 1 earlier month aren’t closed yet.").assertIsDisplayed()
        compose.onNodeWithText("Review & close").performClick()
        assertEquals(ym("2026-03"), reviewed)
    }

    @Test
    fun `surplus tab offers a one-off boost even with nothing saved yet`() {
        var opened = 0
        val empty = ready().copy(pockets = emptyList(), boosts = emptyList(), history = emptyList(), hasAvailable = false)
        compose.setContent { BudzetsTheme { SurplusContent(empty, SurplusActions(onOneOff = { opened++ })) } }
        compose.onNodeWithText("Add a one-off boost").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `one-off boosts are listed with a way to remove them, and in history and the breakdown`() {
        var removed: LedgerEntryId? = null
        val state = ready().copy(
            pockets = listOf(PocketRow(unallocated, PocketBreakdown(oneOff = eur(200)))),
            oneOffs = listOf(OneOffRow(LedgerEntryId("o1"), ym("2026-04"), eur(200), "Gift")),
            history = listOf(
                HistoryRow(LedgerEntryId("h1"), HistoryKind.ONE_OFF_REMOVED, LocalDate.of(2026, 4, 15), unallocated, eur(-5), ym("2026-03"), null, null),
                HistoryRow(LedgerEntryId("h2"), HistoryKind.ONE_OFF_ADDED, LocalDate.of(2026, 4, 15), unallocated, eur(200), ym("2026-04"), null, "Gift"),
            ),
        )
        compose.setContent { BudzetsTheme { SurplusContent(state, SurplusActions(onRemoveOneOff = { removed = it })) } }

        compose.onNodeWithText("One-off boosts +€200.00").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("+€200.00 for April 2026"))
        compose.onNodeWithText("Remove").performClick()
        assertEquals(LedgerEntryId("o1"), removed)
        list.performScrollToNode(hasText("One-off boost, April 2026"))
        list.performScrollToNode(hasText("One-off boost removed, March 2026"))
    }

    @Test
    fun `a deleted category's pocket is marked in history`() {
        val clothes = PocketLabel(SurplusPocket.Category(CategoryId("clothes")), "Clothes", isArchived = true, isDeleted = true)
        val state = ready().copy(
            history = listOf(HistoryRow(LedgerEntryId("h1"), HistoryKind.CLOSE_CREDIT, LocalDate.of(2026, 4, 15), clothes, eur(5), ym("2026-03"), null, null)),
        )
        compose.setContent { BudzetsTheme { SurplusContent(state, SurplusActions()) } }
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("Unspent in March 2026"))
        compose.onNodeWithText("Clothes (deleted)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `one-off boost editor explains itself, and refuses a closed month`() {
        var typed = ""
        val editing = OneOffBoostState.Editing(
            currency = CurrencySpec.DEFAULT,
            form = OneOffForm(),
            month = ym("2026-04"),
            isCurrentMonth = true,
            monthClosed = true,
            amountError = null,
        )
        compose.setContent { BudzetsTheme { OneOffBoostContent(editing, OneOffBoostActions(onAmountChange = { typed = it })) } }
        compose.onNodeWithText("It goes into Unallocated surplus first.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("April 2026 is closed, so it can’t take a one-off boost.").assertIsDisplayed()
        compose.onNode(hasText("Add") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag(SURPLUS_AMOUNT_TAG).performScrollTo().performTextInput("7")
        assertEquals("7", typed)
    }
}
