package lv.kristapsbe.budzets.ui.settings

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.data.backup.BackupCodec
import lv.kristapsbe.budzets.data.backup.BackupDocuments
import lv.kristapsbe.budzets.data.backup.BackupRepository
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate

class SettingsViewModelTest : ViewModelTest() {

    /** Files by URI; can refuse writes or hand back different bytes, as a broken provider might. */
    private class FakeDocuments : BackupDocuments {
        val files = mutableMapOf<Uri, ByteArray>()
        var failWrites = false
        var corruptReadBack = false

        override suspend fun write(uri: Uri, bytes: ByteArray): Boolean {
            if (failWrites) throw IOException("no space")
            files[uri] = bytes
            return !corruptReadBack
        }

        override suspend fun read(uri: Uri, limit: Int): ByteArray {
            val bytes = files[uri] ?: throw IOException("gone")
            return bytes.copyOf(minOf(limit, bytes.size))
        }
    }

    private val documents = FakeDocuments()
    private lateinit var backups: BackupRepository
    private val backupUri = Uri.parse("content://docs/budzets-backup.json")

    @Before
    fun setUpBackups() {
        backups = BackupRepository(db, settings, clock, "1.0", File(tmp.root, "pre-restore-backup.json"))
    }

    /**
     * Collected for the whole test, as a screen would. Without a subscriber between awaits,
     * `WhileSubscribed` stops the state on the virtual clock at once, and restarting it from the
     * awaiting thread occasionally never reached the test scheduler: the state stayed stale.
     */
    private fun TestScope.settingsVm() = SettingsViewModel(settings, expenses, backups, documents, clock, "1.0")
        .also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

    private suspend fun SettingsViewModel.awaitReady(predicate: (SettingsUiState.Ready) -> Boolean = { true }) =
        uiState.awaitFirst { it is SettingsUiState.Ready && predicate(it) } as SettingsUiState.Ready

    private suspend fun SettingsViewModel.awaitMessage(expected: SettingsMessage) {
        message.awaitFirst { it == expected }
        messageShown(expected)
    }

    private suspend fun seed() {
        val food = categories.create("Food")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = eur(300).minor), ym("2026-03"))
        expenses.add(eur(40), food.id, LocalDate.of(2026, 3, 2))
        expenses.add(eur(15), food.id, LocalDate.of(2026, 4, 9))
        surplus.closeMonth(ym("2026-03")).ok()
    }

    @Test
    fun `backing up writes the file and records the time only once it is confirmed`() = runTest {
        seed()
        val vm = settingsVm()
        assertNull(vm.awaitReady().lastBackupAt)
        assertEquals("budzets-backup-2026-04-15.json", vm.backupFileName())

        vm.backUp(backupUri)
        vm.awaitMessage(SettingsMessage.BACKUP_SAVED)
        assertEquals(clock.instant(), vm.awaitReady { it.lastBackupAt != null && it.busy == null }.lastBackupAt)
        assertTrue(BackupCodec.decode(documents.files.getValue(backupUri)) is Outcome.Ok)
    }

    @Test
    fun `a failed or unconfirmed write says so and records nothing`() = runTest {
        val vm = settingsVm()
        documents.failWrites = true
        vm.backUp(backupUri)
        vm.awaitMessage(SettingsMessage.BACKUP_FAILED)

        documents.failWrites = false
        documents.corruptReadBack = true
        vm.backUp(backupUri)
        vm.awaitMessage(SettingsMessage.BACKUP_NOT_CONFIRMED)
        assertNull(settings.lastBackupAt.first())
    }

    @Test
    fun `cancelling a picker does nothing`() = runTest {
        val vm = settingsVm()
        vm.backUp(null)
        vm.openBackup(null)
        val state = vm.awaitReady()
        assertNull(state.busy)
        assertNull(state.pendingRestore)
        assertNull(vm.message.value)
    }

    @Test
    fun `choosing a backup only previews it, confirming replaces the data, and undo puts it back`() = runTest {
        seed()
        documents.files[backupUri] = backups.export()
        val late = categories.create("Late")
        expenses.add(eur(3), late.id, LocalDate.of(2026, 4, 12))

        val vm = settingsVm()
        vm.openBackup(backupUri)
        val preview = vm.awaitReady { it.pendingRestore != null }.pendingRestore!!
        assertEquals(1, preview.categories)
        assertEquals(2, preview.expenses)
        assertEquals(1, preview.closedMonths)
        assertEquals(LocalDate.of(2026, 3, 2), preview.firstExpense)
        assertEquals(LocalDate.of(2026, 4, 9), preview.lastExpense)
        assertEquals(clock.instant(), preview.createdAt)
        assertEquals(2, categories.categories.first().size) // nothing changed yet

        vm.cancelRestore()
        assertNull(vm.awaitReady { it.pendingRestore == null }.pendingRestore)
        vm.openBackup(backupUri)
        vm.awaitReady { it.pendingRestore != null }
        vm.confirmRestore()
        vm.awaitMessage(SettingsMessage.RESTORED)
        assertEquals(listOf("Food"), categories.categories.first().map { it.name })
        assertEquals(clock.instant(), vm.awaitReady { it.lastRestoreAt != null }.lastRestoreAt)

        vm.undoRestore()
        vm.awaitMessage(SettingsMessage.UNDONE)
        assertEquals(listOf("Food", "Late"), categories.categories.first().map { it.name })
        assertNull(vm.awaitReady { it.lastRestoreAt == null }.lastRestoreAt)
    }

    @Test
    fun `unusable files are refused with a reason and change nothing`() = runTest {
        seed()
        val good = backups.export()
        val vm = settingsVm()
        val cases = mapOf(
            "hello".toByteArray() to SettingsMessage.NOT_A_BACKUP,
            good.decodeToString().replace("\"amountMinor\": 4000", "\"amountMinor\": 4001").toByteArray() to SettingsMessage.BACKUP_DAMAGED,
            good.decodeToString().replace("\"schemaVersion\": ${BackupCodec.SCHEMA_VERSION}", "\"schemaVersion\": 9").toByteArray() to SettingsMessage.BACKUP_TOO_NEW,
            ByteArray(BackupCodec.MAX_BYTES + 10) to SettingsMessage.FILE_TOO_LARGE,
        )
        for ((bytes, expected) in cases) {
            documents.files[backupUri] = bytes
            vm.openBackup(backupUri)
            vm.awaitMessage(expected)
        }
        vm.openBackup(Uri.parse("content://docs/missing.json"))
        vm.awaitMessage(SettingsMessage.FILE_UNREADABLE)
        assertNull(vm.awaitReady { it.busy == null }.pendingRestore)
        assertEquals(listOf("Food"), categories.categories.first().map { it.name })
    }

    @Test
    fun `currency - free while empty, then only the same number of decimals`() = runTest {
        val vm = settingsVm()
        assertFalse(vm.awaitReady().currencyLocked)
        vm.setCurrency(CurrencySpec.of("JPY"))
        vm.awaitReady { it.currency.code == "JPY" }
        vm.setCurrency(CurrencySpec.DEFAULT)
        vm.awaitReady { it.currency.code == "EUR" }

        seed()
        vm.awaitReady { it.currencyLocked }
        vm.setCurrency(CurrencySpec.of("JPY"))
        vm.awaitMessage(SettingsMessage.CURRENCY_REFUSED)
        vm.setCurrency(CurrencySpec.of("USD"))
        vm.awaitReady { it.currency.code == "USD" }
    }
}
