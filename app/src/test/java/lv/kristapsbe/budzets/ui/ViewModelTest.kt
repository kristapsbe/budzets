package lv.kristapsbe.budzets.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import lv.kristapsbe.budzets.data.DbTest
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/** [DbTest] plus a Main dispatcher for `viewModelScope` and a throwaway settings store. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest : DbTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    protected lateinit var settings: SettingsRepository

    @Before
    fun setUpViewModelTest() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(tmp.root, "s.preferences_pb") },
            hasMonetaryData = { db.expenseDao().hasMonetaryData() },
        )
    }

    private val viewModels = mutableListOf<ViewModel>()

    /**
     * Ends this ViewModel's scope when the test ends, before Main is reset and the database is
     * closed, and waits for it. A scope still running, or only cancelled, can resume onto Main
     * from a Room or Default thread while Main is being reset ("Dispatchers.Main is used
     * concurrently with setting it"): a `flowOn(Dispatchers.Default)` producer finishing on its own
     * thread resumes its collector there.
     */
    protected fun <T : ViewModel> T.clearedAfterTest(): T = also { viewModels += it }

    @After
    fun tearDownViewModelTest() {
        runBlocking { withTimeout(5_000) { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } } }
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    /** Waits in real time: Room and DataStore emit from their own threads. */
    protected suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    /**
     * Polls a Compose-state value, which has no Flow to suspend on outside a composition.
     * Bind the result: as the last expression of a `runTest` block, T is inferred as Unit and
     * [read] silently returns Unit.
     */
    protected suspend fun <T> awaitValue(read: () -> T, predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                var value = read()
                while (!predicate(value)) {
                    delay(10)
                    value = read()
                }
                value
            }
        }
}
