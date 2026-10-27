package lv.kristapsbe.budzets.util

import android.text.format.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The Material date picker speaks in UTC-midnight milliseconds. Converting with plain day
 * arithmetic (never the device zone) keeps the picked calendar date exact, so an expense can't
 * slip into the neighbouring day or month.
 */
fun LocalDate.toPickerMillis(): Long = toEpochDay() * MILLIS_PER_DAY

fun pickerMillisToDate(utcMillis: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(utcMillis, MILLIS_PER_DAY))

/** "Tuesday, October 27" in the locale's own order; the year is added only when it isn't [currentYear]. */
fun LocalDate.dayName(locale: Locale, currentYear: Int, short: Boolean = false): String {
    val skeleton = (if (short) "EEEMMMd" else "EEEEMMMMd") + if (year != currentYear) "y" else ""
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(this)
}
