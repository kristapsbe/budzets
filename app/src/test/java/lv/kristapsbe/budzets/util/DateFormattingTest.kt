package lv.kristapsbe.budzets.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/** Robolectric for `android.text.format.DateFormat`'s localized patterns. */
@RunWith(RobolectricTestRunner::class)
class DateFormattingTest {

    @Test
    fun `picker millis round-trip exactly, whatever the device zone`() {
        val original = TimeZone.getDefault()
        try {
            // Far east and far west of UTC: a zone-based conversion would shift the date by one.
            for (zone in listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "Europe/Riga")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (date in listOf(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 1), LocalDate.of(2024, 2, 29), LocalDate.of(1969, 12, 31))) {
                    assertEquals(date, pickerMillisToDate(date.toPickerMillis()))
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
        // The picker reports UTC midnight of the chosen day.
        assertEquals(Instant.parse("2026-03-31T00:00:00Z").toEpochMilli(), LocalDate.of(2026, 3, 31).toPickerMillis())
        // Any time within that UTC day still means that day.
        assertEquals(LocalDate.of(2026, 3, 31), pickerMillisToDate(Instant.parse("2026-03-31T23:59:59Z").toEpochMilli()))
    }

    @Test
    fun `day names follow the locale, with the year only when it differs`() {
        val date = LocalDate.of(2026, 10, 27)
        assertEquals("Tuesday, October 27", date.dayName(Locale.US, 2026))
        assertEquals("Tue, Oct 27", date.dayName(Locale.US, 2026, short = true))
        assertEquals("Tue, Oct 27, 2026", date.dayName(Locale.US, 2027, short = true))
        assertEquals("otrdiena, 27. oktobris", date.dayName(Locale.forLanguageTag("lv-LV"), 2026))
    }
}
