package lv.kristapsbe.budzets

import android.app.Application
import lv.kristapsbe.budzets.widget.WidgetRefresher

class BudzetsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Only worth watching for changes when a widget is showing them.
        if (WidgetRefresher.hasWidgets(this)) container.widgetRefresher.start()
    }
}
