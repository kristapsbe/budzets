package lv.kristapsbe.budzets.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Keeps the widget in step with the data. Every write the app makes goes through Room, so
 * watching the tables the widget reads covers expenses, budgets, categories, surplus moves,
 * boosts, month closes and (later) restores, without each screen having to remember to call it.
 * Currency changes come from DataStore. Bursts of writes are debounced into one update.
 *
 * Runs only while the process is alive, which is whenever the app writes anything.
 */
@OptIn(FlowPreview::class)
class WidgetRefresher(
    private val db: BudzetsDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val startupRefreshDelayMillis: Long = STARTUP_REFRESH_DELAY_MILLIS,
    private val update: suspend () -> Unit,
) {
    private var job: Job? = null

    /**
     * Idempotent. The widget is also refreshed once when the app starts (which re-arms a midnight
     * alarm lost to a force-stop), but only after [startupRefreshDelayMillis], so the widget's
     * session doesn't compete with the app's first frames. Writes refresh it as soon as usual.
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            merge(
                db.invalidationTracker.createFlow(*WATCHED_TABLES, emitInitialState = false).map { },
                settings.currency.drop(1).map { },
                flow { delay(startupRefreshDelayMillis); emit(Unit) },
            )
                .debounce(DEBOUNCE_MILLIS)
                .collect { runCatching { update() } }
        }
    }

    companion object {
        val WATCHED_TABLES = arrayOf("expenses", "budget_configs", "categories", "boosts", "surplus_ledger", "month_closures")
        const val DEBOUNCE_MILLIS = 500L
        const val STARTUP_REFRESH_DELAY_MILLIS = 3_000L

        /** Whether any widget is on the home screen; without one there is nothing to refresh. */
        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, BudzetsWidgetReceiver::class.java))
                .isNotEmpty()

        suspend fun refreshAll(context: Context, clock: Clock) {
            if (!hasWidgets(context)) return
            BudzetsWidget().updateAll(context)
            WidgetSchedule.schedule(context, clock)
        }
    }
}

/**
 * One inexact, non-waking alarm just after local midnight, so "days left" and the month roll over
 * without polling. If the device is asleep it fires when it wakes, which is when the widget can
 * be seen anyway. Rescheduled on every update; the widget's daily `updatePeriodMillis` and the
 * update Android sends after a reboot restore it if it is lost.
 */
object WidgetSchedule {

    fun nextRefreshAt(now: ZonedDateTime): Instant =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1).toInstant()

    fun schedule(context: Context, clock: Clock) {
        val at = nextRefreshAt(ZonedDateTime.now(clock))
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at.toEpochMilli(), pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The midnight alarm's target. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BudzetsApplication
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                WidgetRefresher.refreshAll(context, app.container.clock)
            } finally {
                pending.finish()
            }
        }
    }
}

class BudzetsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BudzetsWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val container = (context.applicationContext as BudzetsApplication).container
        WidgetSchedule.schedule(context, container.clock)
        // The first widget may be added while the app is running: start watching for changes.
        container.widgetRefresher.start()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSchedule.cancel(context)
    }
}
