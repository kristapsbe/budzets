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
import kotlinx.coroutines.flow.update
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
 * Runs only while the process is alive, which is whenever the app writes anything. Each update
 * outside a live session runs a Glance session for about 45 s, so none is started without a reason.
 */
@OptIn(FlowPreview::class)
class WidgetRefresher(
    private val db: BudzetsDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val startupRefreshDelayMillis: Long = STARTUP_REFRESH_DELAY_MILLIS,
    private val needsStartupRefresh: () -> Boolean = { true },
    private val update: suspend () -> Unit,
) {
    private var job: Job? = null

    /**
     * Idempotent. If [needsStartupRefresh] says the widget may have missed something, which means
     * the midnight alarm was lost to a force-stop, it is refreshed once, after
     * [startupRefreshDelayMillis] so the session doesn't compete with the app's first frames.
     * A normal app start draws nothing, and writes refresh it as soon as usual.
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            merge(
                db.invalidationTracker.createFlow(*WATCHED_TABLES, emitInitialState = false).map { },
                settings.currency.drop(1).map { },
                flow {
                    delay(startupRefreshDelayMillis)
                    if (needsStartupRefresh()) emit(Unit)
                },
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

        /** Arms the alarm first, so an update that fails can't lose it. */
        suspend fun refreshAll(context: Context, clock: Clock) {
            if (!hasWidgets(context)) return
            WidgetSchedule.schedule(context, clock)
            BudzetsWidget().updateAll(context)
        }
    }
}

/**
 * One non-waking alarm just after local midnight, so "days left" and the month roll over without
 * polling. This is the widget's only timer: nothing here wakes the device. If it is asleep the
 * alarm fires when it wakes, which is when the widget can be seen anyway. Rescheduled on every
 * update. Android's update after a reboot restores it, and so does the next app start after a
 * force-stop.
 *
 * It sets a window because a plain `set` allows delivery up to 75% of the wait late (an hour at
 * most on API 37, where it fired on time when tested; older versions weren't measured). The
 * window is the shortest Android allows without an exact alarm, plus a margin.
 */
object WidgetSchedule {

    const val WINDOW_MILLIS = 15 * 60 * 1_000L

    fun nextRefreshAt(now: ZonedDateTime): Instant =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1).toInstant()

    fun schedule(context: Context, clock: Clock) {
        val at = nextRefreshAt(ZonedDateTime.now(clock))
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, at.toEpochMilli(), WINDOW_MILLIS, pendingIntent(context))
    }

    /** False once a force-stop or reboot has cleared the alarm, and it may have been missed. */
    fun isArmed(context: Context): Boolean = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    ) != null

    /** Cancels the PendingIntent too: on its own that outlives the alarm, and [isArmed] looks for it. */
    fun cancel(context: Context) {
        val intent = pendingIntent(context)
        context.getSystemService(AlarmManager::class.java).cancel(intent)
        intent.cancel()
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Redraws the widget for the midnight alarm, and for a change of language or time zone. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) BudzetsWidget.languageChanges.update { it + 1 }
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
