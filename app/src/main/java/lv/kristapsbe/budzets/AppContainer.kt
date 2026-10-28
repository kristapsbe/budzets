package lv.kristapsbe.budzets

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lv.kristapsbe.budzets.data.backup.BackupDocuments
import lv.kristapsbe.budzets.data.backup.BackupRepository
import lv.kristapsbe.budzets.data.backup.SafBackupDocuments
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.widget.WidgetRefresher
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** App-wide dependencies, created once by [BudzetsApplication]. Everything is lazy. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val clock: Clock = SystemZoneClock()
    private val newId: () -> String = { UUID.randomUUID().toString() }

    val database: BudzetsDatabase by lazy { BudzetsDatabase.build(appContext) }

    val categories by lazy { CategoryRepository(database, clock, newId) }
    val expenses by lazy { ExpenseRepository(database, clock, newId) }
    val budgets by lazy { BudgetRepository(database, clock, newId) }
    val surplus by lazy { SurplusRepository(database, budgets, clock, newId) }
    val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
            hasMonetaryData = { database.expenseDao().hasMonetaryData() },
        )
    }

    val appVersion: String by lazy {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    }

    val backups by lazy {
        BackupRepository(database, settings, clock, appVersion, File(appContext.noBackupFilesDir, SAFETY_COPY_FILE))
    }
    val backupDocuments: BackupDocuments by lazy { SafBackupDocuments(appContext.contentResolver) }

    /** Work that outlives a screen, such as widget refreshes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val widgetRefresher by lazy {
        WidgetRefresher(database, settings, appScope) { WidgetRefresher.refreshAll(appContext, clock) }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
        const val SAFETY_COPY_FILE = "pre-restore-backup.json"
    }
}

/**
 * System clock that follows the device's *current* time zone. `Clock.systemDefaultZone()`
 * fixes the zone at creation, so after travel "today" and "this month" would be wrong.
 */
private class SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}
