package lv.kristapsbe.budzets.widget

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class WidgetScheduleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val riga = ZoneId.of("Europe/Riga")
    private val clock = Clock.fixed(Instant.parse("2026-04-15T06:00:00Z"), riga)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun `the alarm can't wake the device, and is bounded to just after midnight`() {
        WidgetSchedule.schedule(context, clock)

        val alarm = requireNotNull(alarms.nextScheduledAlarm)
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(ZonedDateTime.of(2026, 4, 16, 0, 1, 0, 0, riga).toInstant().toEpochMilli(), alarm.triggerAtMs)
        assertEquals(WidgetSchedule.WINDOW_MILLIS, alarm.windowLengthMs)
    }

    @Test
    fun `scheduling again replaces the alarm`() {
        WidgetSchedule.schedule(context, clock)
        WidgetSchedule.schedule(context, clock)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `armed follows schedule and cancel`() {
        assertFalse(WidgetSchedule.isArmed(context))
        WidgetSchedule.schedule(context, clock)
        assertTrue(WidgetSchedule.isArmed(context))
        WidgetSchedule.cancel(context)
        assertFalse(WidgetSchedule.isArmed(context))
    }
}
