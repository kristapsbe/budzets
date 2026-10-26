package lv.kristapsbe.budzets

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
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

    private companion object {
        const val SETTINGS_FILE = "settings"
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
