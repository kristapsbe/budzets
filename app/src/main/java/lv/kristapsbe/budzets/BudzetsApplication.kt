package lv.kristapsbe.budzets

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.work.Configuration
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.widget.WidgetRefresher

/**
 * Also starts WorkManager on demand: only the widget (through Glance) uses it, so its start-up
 * provider is removed in the manifest and it initializes on the first widget update instead of
 * on every app start.
 */
class BudzetsApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Only worth watching for changes when a widget is showing them. Off the main thread: it
        // asks the widget service and builds the database, neither of which the first frame needs.
        container.appScope.launch {
            if (WidgetRefresher.hasWidgets(this@BudzetsApplication)) container.widgetRefresher.start()
        }
    }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()
}

/** The app's [AppContainer], for a ViewModel factory's initializer. */
val CreationExtras.container: AppContainer get() = (this[APPLICATION_KEY] as BudzetsApplication).container
