package lv.kristapsbe.budzets.ui.budgets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlanRule
import lv.kristapsbe.budzets.domain.calculation.TargetErrors
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetEditorContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val january = ym("2026-01") // 31 days

    private fun editing(
        draft: BudgetTarget? = BudgetTarget(dailyMinor = 1_000, weeklyMinor = 10_000),
        form: TargetForm = TargetForm("10", "100", ""),
        categoryName: String? = "Food",
        next: YearMonth? = null,
        exceeds: Boolean = false,
        closed: List<YearMonth> = emptyList(),
        errors: TargetErrors = TargetErrors(),
        changed: Boolean = true,
    ) = BudgetEditorState.Editing(
        categoryName = categoryName,
        month = january,
        currency = CurrencySpec.DEFAULT,
        form = form,
        draft = draft,
        preview = draft?.let { MonthlyPlanRule.plan(it, january) },
        clearsBudget = draft?.isConfigured == false,
        errors = errors,
        nextChange = next,
        closedMonthsAffected = closed,
        categoryTotal = eur(700),
        overallBase = eur(600),
        overallDerived = false,
        exceedsOverall = exceeds,
        isChanged = changed,
    )

    private fun show(state: BudgetEditorState, actions: BudgetEditorActions = BudgetEditorActions()) {
        compose.setContent { BudzetsTheme { BudgetEditorContent(state, actions) } }
    }

    @Test
    fun `preview spells out each equivalent and marks the one that sets the plan`() {
        show(editing())
        compose.onNodeWithText("Food budget").assertIsDisplayed()
        compose.onNodeWithText("Applies from January 2026 on.").assertIsDisplayed()
        compose.onNodeWithText("€10.00/day × 31 days = €310.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€100.00/week × 31 days ÷ 7 = €442.86").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Sets the plan").assertIsDisplayed() // only one winner
        compose.onNodeWithText("Plan for January 2026: €442.86").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The highest one is used. They’re alternative ways to state one limit, so they aren’t added together.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a single target needs no rule explanation`() {
        show(editing(draft = BudgetTarget(monthlyMinor = 30_000), form = TargetForm(monthly = "300")))
        compose.onNodeWithText("€300.00/month").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Plan for January 2026: €300.00").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("The highest one is used", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `typing and Done reach the actions, and Save follows whether anything changed`() {
        var monthly = ""
        var saves = 0
        show(editing(changed = false), BudgetEditorActions(onMonthlyChange = { monthly = it }, onSave = { saves++ }))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag(MONTHLY_FIELD_TAG).performTextInput("5")
        assertEquals("5", monthly)
        compose.onNodeWithTag(MONTHLY_FIELD_TAG).performImeAction()
        assertEquals(1, saves)
    }

    @Test
    fun `save is enabled for a change`() {
        show(editing())
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `scheduled change, closed months and the overall warning are shown`() {
        show(editing(next = ym("2026-05"), exceeds = true, closed = listOf(january)))
        compose.onNodeWithText("Applies from January 2026 until May 2026, when a later change takes over.").assertIsDisplayed()
        compose.onNodeWithText("Category budgets would add up to €700.00, more than the overall budget of €600.00.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Already closed: January 2026. After saving, close it again to update its surplus.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `clearing explains what saving will do`() {
        var cleared = false
        show(editing(draft = BudgetTarget(), form = TargetForm("1", "", ""), categoryName = null), BudgetEditorActions(onClear = { cleared = true }))
        compose.onNodeWithText("Overall budget").assertIsDisplayed()
        compose.onNodeWithText("No overall budget from January 2026. The overall plan becomes the sum of category budgets.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear all").performScrollTo().performClick()
        assertEquals(true, cleared)
    }

    @Test
    fun `field errors are spelled out`() {
        show(editing(draft = null, errors = TargetErrors(daily = AmountError.TOO_MANY_DECIMALS)))
        compose.onNodeWithText("Use at most 2 decimal places").assertIsDisplayed()
        compose.onNodeWithText("Fix the highlighted amount to see the plan.").performScrollTo().assertIsDisplayed()
    }
}
