package lv.kristapsbe.budzets.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val riga = ZoneId.of("Europe/Riga")
    private val noon = Instant.parse("2026-04-15T09:00:00Z")

    private fun ready(
        lastBackupAt: Instant? = null,
        lastRestoreAt: Instant? = null,
        busy: Busy? = null,
        pending: RestorePreview? = null,
        locked: Boolean = false,
        oneOffNote: String? = null,
    ) = SettingsUiState.Ready(CurrencySpec.DEFAULT, locked, lastBackupAt, lastRestoreAt, busy, pending, "1.0", oneOffNote)

    private fun show(state: SettingsUiState, actions: SettingsActions = SettingsActions()) =
        compose.setContent { BudzetsTheme { SettingsContent(state, actions, zone = riga) } }

    @Test
    fun `backup card - status and actions`() {
        var backedUp = false
        var restoring = false
        show(ready(), SettingsActions(onBackUp = { backedUp = true }, onRestore = { restoring = true }))
        compose.onNodeWithText("No backup made on this phone yet.").assertIsDisplayed()
        compose.onNodeWithText("Back up now").performClick()
        compose.onNodeWithText("Restore").performClick()
        assertTrue(backedUp && restoring)
        compose.onNodeWithText("Undo restore").assertDoesNotExist()
    }

    @Test
    fun `while busy the buttons wait and say why`() {
        show(ready(lastBackupAt = noon, busy = Busy.RESTORING))
        compose.onNodeWithText("Last backup: Apr 15, 2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restoring…").assertIsDisplayed()
        compose.onNodeWithText("Back up now").assertIsNotEnabled()
        compose.onNodeWithText("Restore").assertIsNotEnabled()
    }

    @Test
    fun `restore confirmation says what the backup holds and that data is replaced`() {
        var confirmed = false
        var cancelled = false
        val preview = RestorePreview(noon, CurrencySpec.of("USD"), 6, 1, 3, LocalDate.of(2025, 11, 2), LocalDate.of(2026, 4, 14))
        show(ready(pending = preview), SettingsActions(onConfirmRestore = { confirmed = true }, onCancelRestore = { cancelled = true }))

        compose.onNodeWithText("Replace all data?").assertIsDisplayed()
        compose.onNodeWithText("Backup from Apr 15, 2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("6 categories · 1 expense · 3 closed months").assertIsDisplayed()
        compose.onNodeWithText("Expenses from Nov 2, 2025 to Apr 14, 2026").assertIsDisplayed()
        compose.onNodeWithText("Currency: US Dollar (USD)").assertIsDisplayed()
        compose.onNodeWithText("replaced by this backup, not merged", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Replace data").performClick()
        assertTrue(cancelled && confirmed)
    }

    @Test
    fun `expenses all on one day are named once`() {
        val day = LocalDate.of(2026, 10, 28)
        show(ready(pending = RestorePreview(noon, CurrencySpec.DEFAULT, 6, 1, 0, day, day)))
        compose.onNodeWithText("Expenses on Oct 28, 2026").assertIsDisplayed()
    }

    @Test
    fun `undo is offered after a restore and asks first`() {
        var undone = 0
        show(ready(lastRestoreAt = noon), SettingsActions(onUndoRestore = { undone++ }))
        compose.onNodeWithText("Data restored from a backup on Apr 15, 2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Undo restore").performClick()
        assertEquals(0, undone)
        compose.onNodeWithText("Undo restore?").assertIsDisplayed()
        compose.onNodeWithText("Anything added or changed since then is lost.", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasText("Undo restore")).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onAllNodes(hasText("Undo restore"))[1].performClick()
        assertEquals(1, undone)
    }

    @Test
    fun `currency picker - only the same decimals once amounts exist`() {
        var picked: CurrencySpec? = null
        show(ready(locked = true), SettingsActions(onCurrency = { picked = it }))
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("Euro (EUR)"))
        compose.onNodeWithText("only currencies with 2 decimals can be chosen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Euro (EUR)").performClick()

        compose.onNodeWithText("Choose currency").assertIsDisplayed()
        compose.onNodeWithText("Search").performTextInput("yen")
        compose.onNodeWithText("Japanese Yen (JPY)").assertIsNotEnabled()
        compose.onNodeWithText("0 decimals").assertIsDisplayed()
        compose.onNodeWithText("Search").performTextReplacement("usd")
        compose.onNodeWithText("US Dollar (USD)").assertIsEnabled().performClick()
        assertEquals(CurrencySpec.of("USD"), picked)
    }

    @Test
    fun `the one-off note is edited in a dialog, and saving it empty clears it`() {
        val saved = mutableListOf<String>()
        show(ready(oneOffNote = "Gifts go to Fun"), SettingsActions(onOneOffNote = { saved += it }))
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("Note on the one-off boost form"))
        compose.onNodeWithText("Gifts go to Fun").assertIsDisplayed().performClick()

        compose.onNodeWithText("One-off boost note").assertIsDisplayed()
        compose.onNodeWithText("15 / 300").assertIsDisplayed()
        compose.onNodeWithTag(ONE_OFF_NOTE_FIELD_TAG).performTextReplacement("Bonus goes to Savings")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Bonus goes to Savings"), saved)

        compose.onNodeWithText("Bonus goes to Savings").assertDoesNotExist() // the state is the caller's
        compose.onNodeWithText("Gifts go to Fun").performClick()
        compose.onNodeWithTag(ONE_OFF_NOTE_FIELD_TAG).performTextReplacement("")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Bonus goes to Savings", ""), saved)
    }

    @Test
    fun `without a note it says so, and a note too long can't be saved`() {
        show(ready())
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("Note on the one-off boost form"))
        compose.onNodeWithText("None. Add text to show under the amount", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ONE_OFF_NOTE_FIELD_TAG).performTextInput("x".repeat(301))
        compose.onNodeWithText("301 / 300").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `explains budgets, privacy and versions`() {
        show(ready())
        val list = compose.onNode(hasScrollToIndexAction() and keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("the highest monthly amount is used", substring = true))
        list.performScrollToNode(hasText("Your data stays on this phone", substring = true))
        list.performScrollToNode(hasText("Budzets 1.0"))
        list.performScrollToNode(hasText("Data version 3 · backup format 3"))
        compose.onNodeWithText("Data version 3 · backup format 3").assertIsDisplayed()
    }
}
