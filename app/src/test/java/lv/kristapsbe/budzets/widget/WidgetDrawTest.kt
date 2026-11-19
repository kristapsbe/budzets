package lv.kristapsbe.budzets.widget

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.util.displayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The app's own refreshes draw the widget in the process and hand it to the launcher, with no
 * Glance session: no WorkManager job, so no 45 s wake lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class WidgetDrawTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = (context as BudzetsApplication).container
    private val manager = AppWidgetManager.getInstance(context)

    @Test
    fun `a refresh draws the widget at its size, with no Glance session`() {
        val today = LocalDate.now(container.clock)
        offMain {
            val food = container.categories.create("Food")
            container.expenses.add(eur(12, 34), food.id, today)
        }
        val id = 7
        shadowOf(manager).addBoundWidget(id, AppWidgetProviderInfo().apply { provider = ComponentName(context, BudzetsWidgetReceiver::class.java) })
        manager.updateAppWidgetOptions(id, sized(LARGE.width.value, LARGE.height.value))

        offMain { WidgetRefresher.refreshAll(context, container.clock) }

        // Drawn before refreshAll returned: a session would have drawn it later, from its job.
        val shown = texts(shadowOf(manager).getViewFor(id))
        assertTrue(shown.toString(), "€12.34 spent" in shown)
        // The month name shows from the medium size up, so the widget's real size reached the draw.
        assertTrue(shown.toString(), YearMonth.from(today).displayName(Locale.US) in shown)
        assertEquals(emptyList<Any>(), context.getSystemService(JobScheduler::class.java).allPendingJobs)
    }

    private fun sized(width: Float, height: Float) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height.toInt())
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /**
     * Runs [block] on another thread while this one, Robolectric's main thread, keeps its looper
     * going: Room and Glance resume on the main thread, and Glance's frame clock waits on it.
     */
    private fun <T> offMain(block: suspend () -> T): T {
        val result = CompletableFuture<T>()
        thread { runCatching { runBlocking { block() } }.fold(result::complete, result::completeExceptionally) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!result.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        return result.get(0, TimeUnit.SECONDS)
    }
}
