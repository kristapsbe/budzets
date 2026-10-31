package lv.kristapsbe.budzets.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import lv.kristapsbe.budzets.data.backup.BackupCodec
import lv.kristapsbe.budzets.data.backup.BackupRepository
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class WidgetRefresherTest : ViewModelTest() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val updates = AtomicInteger()

    @After
    fun stop() = scope.cancel()

    private suspend fun awaitUpdates(n: Int) = awaitValue({ updates.get() }) { it == n }

    /** Real time: the debounce runs on a real dispatcher. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(WidgetRefresher.DEBOUNCE_MILLIS + 400) }

    @Test
    fun `every kind of write refreshes the widget, bursts only once`() = runTest {
        val refresher = WidgetRefresher(db, settings, scope, startupRefreshDelayMillis = 0) { updates.incrementAndGet() }
        refresher.start()
        refresher.start() // idempotent
        awaitUpdates(1) // start-up refresh
        settle()
        assertEquals(1, updates.get())

        // A burst of writes is one refresh.
        val food = categories.create("Food")
        expenses.add(eur(5), food.id, LocalDate.of(2026, 4, 2))
        expenses.add(eur(6), food.id, LocalDate.of(2026, 4, 3))
        awaitUpdates(2)
        settle()
        assertEquals(2, updates.get())

        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 100), ym("2026-03"))
        awaitUpdates(3)
        surplus.closeMonth(ym("2026-03")).ok()
        awaitUpdates(4)
        settings.setCurrency(CurrencySpec.of("USD"))
        awaitUpdates(5)

        // A restore replaces every table in one transaction: one more refresh, no hook needed.
        val backups = BackupRepository(db, settings, clock, "1.0", File(tmp.root, "pre-restore-backup.json"))
        val backup = (BackupCodec.decode(backups.export()) as Outcome.Ok).value
        backups.restore(backup)
        awaitUpdates(6)
        settle()
        assertEquals(6, updates.get())
    }

    @Test
    fun `settings other than the currency don't refresh the widget`() = runTest {
        WidgetRefresher(db, settings, scope, startupRefreshDelayMillis = 0) { updates.incrementAndGet() }.start()
        awaitUpdates(1) // start-up refresh
        settle()

        settings.recordBackupSuccess(Instant.parse("2026-04-02T10:00:00Z"))
        settings.recordRestore(Instant.parse("2026-04-02T11:00:00Z"))
        settle()
        assertEquals(1, updates.get())

        settings.setCurrency(CurrencySpec.of("USD"))
        awaitUpdates(2)
    }

    @Test
    fun `a normal start draws nothing, but a write still refreshes`() = runTest {
        WidgetRefresher(db, settings, scope, 200L, needsStartupRefresh = { false }) { updates.incrementAndGet() }.start()
        settle()
        assertEquals(0, updates.get())

        categories.create("Food")
        awaitUpdates(1)
    }

    @Test
    fun `the start-up refresh waits, a write doesn't`() = runTest {
        val startupDelay = 2_000L
        WidgetRefresher(db, settings, scope, startupDelay, needsStartupRefresh = { true }) { updates.incrementAndGet() }.start()
        withContext(Dispatchers.Default) { delay(300) } // let it subscribe
        val written = System.nanoTime()
        categories.create("Food")
        awaitUpdates(1)
        val afterWrite = (System.nanoTime() - written) / 1_000_000
        assertTrue("write refreshed after ${afterWrite}ms", afterWrite < startupDelay)
        awaitUpdates(2) // then the delayed start-up refresh
        settle()
        assertEquals(2, updates.get())
    }
}
