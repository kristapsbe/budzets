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
import org.junit.Test
import java.io.File
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
        val refresher = WidgetRefresher(db, settings, scope) { updates.incrementAndGet() }
        refresher.start()
        refresher.start() // idempotent
        awaitUpdates(1) // initial refresh, as when the app starts
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
}
