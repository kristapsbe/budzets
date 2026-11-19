package lv.kristapsbe.budzets.domain.forecast

import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** A forecast amount and its likely range (Q54–Q56); [low] ≤ [expected] ≤ [high]. */
data class Estimate(val expected: Money, val low: Money, val high: Money)

/** What a forecast is based on, by how many finished months there are. */
enum class ForecastBasis {
    /** 3–5 months: a typical month, the median. */
    TYPICAL_MONTH,

    /** 6–11 months: the trend. */
    TREND,

    /** 12 months or more: the trend and the time of year. */
    TREND_AND_SEASON,
}

/** Every number the forecast uses, in one place, so they can be tuned together. */
object ForecastRules {
    /** Months forecast after the month under way (Q54). */
    const val HORIZON = 12

    /** Finished months needed before anything is forecast. */
    const val MIN_MONTHS = 3

    /** Months a series needs before it gets a trend; below this it is flat at its median. */
    const val TREND_FROM = 6

    /** Months a series needs before it gets a yearly pattern. */
    const val SEASON_FROM = 12

    /** The trend, its residuals and the within-month profile look at no more than this many months. */
    const val WINDOW = 24

    /** Each month further ahead keeps this much of the trend's slope, so a forecast flattens out. */
    const val DAMPING = 0.9

    /** A calendar month seen n times keeps n / (n + this) of its pattern. */
    const val SEASON_SHRINK = 1.0

    /** z for an 80% band: 8 months in 10 should land inside it. */
    const val BAND_Z = 1.2816

    /** Turns a median absolute deviation into a standard deviation. */
    const val MAD_TO_SIGMA = 1.4826

    /** The band widens by this fraction for each month further ahead. */
    const val BAND_GROWTH = 0.05
}

/**
 * The month under way projected to its end: spending so far, then the usual share of a month
 * still to come.
 *
 * @property path spending by the end of each day from [today] to the month's last day; the first
 *   is what is spent so far, the last the month's projected total.
 */
data class MonthEnd(val month: YearMonth, val today: Int, val spentSoFar: Money, val path: List<Estimate>) {
    val total: Estimate get() = path.last()

    /** What is still to be spent, as a range. */
    val rest: Estimate get() = Estimate(total.expected - spentSoFar, total.low - spentSoFar, total.high - spentSoFar)
}

/**
 * Spending forecasts per category for the month under way and the [ForecastRules.HORIZON] months
 * after it (Q54–Q56). Any group of categories (one category, a chart band, all of them) can be
 * asked for with [estimate].
 */
class SpendingForecast internal constructor(
    val basis: ForecastBasis,
    /** Finished months from the first one with spending. */
    val finishedMonths: Int,
    val currentMonth: YearMonth,
    private val models: Map<CategoryId, SeriesModel>,
    private val categories: Map<CategoryId, CategoryRef>,
    /** The month under way to its end; null unless today is in it. */
    val monthEnd: MonthEnd?,
) {
    /** The months after [currentMonth] that are forecast. */
    val months: List<YearMonth> = (1..ForecastRules.HORIZON).map { currentMonth.plusMonths(it.toLong()) }

    /** The categories forecast in [month]: those with history that are active then. */
    fun categoriesIn(month: YearMonth): Set<CategoryId> =
        models.keys.filterTo(LinkedHashSet()) { categories[it]?.isActiveIn(month) == true }

    /**
     * What [ids] together will likely spend in [month]. The expected amount is the sum of each
     * category's, rounded one by one, so groups add up to the cent. The range is from the spread of
     * the group's own past errors, so a group is less uncertain than its parts added up.
     */
    fun estimate(month: YearMonth, ids: Collection<CategoryId> = categoriesIn(month)): Estimate =
        estimateOf(models, ids.filter { it in models }, month, currentMonth.minusMonths(1))
}

/**
 * One category's fitted history and its forecast.
 *
 * @property window the months the fit is measured on, oldest first, with [actual] and [fitted].
 * @property season the yearly pattern by month of the year (January first), or null.
 */
internal class SeriesModel(
    val window: List<YearMonth>,
    val actual: DoubleArray,
    val fitted: DoubleArray,
    private val level: Double,
    private val slope: Double,
    private val season: DoubleArray?,
    private val lastMonth: YearMonth,
) {
    private val residuals: Map<YearMonth, Double> = window.indices.associate { window[it] to actual[it] - fitted[it] }

    fun residual(month: YearMonth): Double? = residuals[month]

    fun predict(month: YearMonth): Double {
        val ahead = monthsBetween(lastMonth, month)
        val damped = (1..ahead).sumOf { ForecastRules.DAMPING.pow(it) }
        return max(0.0, level + slope * damped) * (season?.get(month.monthValue - 1) ?: 1.0)
    }
}

object SpendingForecaster {

    /** Finished months (before [currentMonth]) from the first one with spending; 0 with none. */
    fun finishedMonths(spending: Map<YearMonth, Map<CategoryId, Money>>, currentMonth: YearMonth): Int {
        val first = spending.filter { (month, byCategory) -> month < currentMonth && byCategory.values.any { it.isPositive } }.keys.minOrNull()
            ?: return 0
        return monthsBetween(first, currentMonth.minusMonths(1)) + 1
    }

    /**
     * Forecasts from [spending] (per month and category; months from [currentMonth] on are
     * ignored), or null with fewer than [ForecastRules.MIN_MONTHS] finished months. [daily] gives
     * how a month usually unfolds and what the month under way has spent so far; [categories] says
     * which categories are active in which month.
     */
    fun forecast(
        spending: Map<YearMonth, Map<CategoryId, Money>>,
        daily: Map<LocalDate, Money>,
        categories: List<CategoryRef>,
        currentMonth: YearMonth,
        today: LocalDate,
    ): SpendingForecast? {
        val finished = finishedMonths(spending, currentMonth)
        if (finished < ForecastRules.MIN_MONTHS) return null
        val last = currentMonth.minusMonths(1)
        val first = last.minusMonths(finished - 1L)

        val ids = spending.filterKeys { it < currentMonth }.values.flatMap { byCategory -> byCategory.filterValues { it.isPositive }.keys }.toSet()
        val models = ids.associateWith { id ->
            val start = generateSequence(first) { it.plusMonths(1) }.first { spending[it]?.get(id)?.isPositive == true }
            val months = generateSequence(start) { it.plusMonths(1) }.takeWhile { it <= last }.toList()
            fit(months, DoubleArray(months.size) { (spending[months[it]]?.get(id) ?: Money.ZERO).minor.toDouble() })
        }
        val basis = when {
            finished < ForecastRules.TREND_FROM -> ForecastBasis.TYPICAL_MONTH
            finished < ForecastRules.SEASON_FROM -> ForecastBasis.TREND
            else -> ForecastBasis.TREND_AND_SEASON
        }
        val refs = categories.associateBy { it.id }
        val monthEnd = if (YearMonth.from(today) != currentMonth) null else {
            val active = models.keys.filter { refs[it]?.isActiveIn(currentMonth) == true }
            val whole = estimateOf(models, active, currentMonth, last)
            monthEnd(whole, daily, currentMonth, today, maxOf(first, last.minusMonths(ForecastRules.WINDOW - 1L)), last)
        }
        return SpendingForecast(basis, finished, currentMonth, models, refs, monthEnd)
    }

    /** A series' model by its length: flat at the mean, flat at the median, a trend, or a trend with a yearly pattern. */
    internal fun fit(months: List<YearMonth>, y: DoubleArray): SeriesModel {
        val n = y.size
        val last = months.last()
        if (n < ForecastRules.TREND_FROM) {
            val level = if (n < ForecastRules.MIN_MONTHS) y.average() else median(y.toList())
            return SeriesModel(months, y, DoubleArray(n) { level }, level, 0.0, null, last)
        }
        val season = if (n >= ForecastRules.SEASON_FROM) yearlyPattern(months, y) else null
        fun s(i: Int) = season?.get(months[i].monthValue - 1) ?: 1.0
        val from = max(0, n - ForecastRules.WINDOW)
        val positions = (from until n).toList()
        val z = positions.map { i -> if (s(i) > 0) y[i] / s(i) else y[i] }
        val slopes = buildList {
            for (a in z.indices) for (b in a + 1 until z.size) add((z[b] - z[a]) / (b - a))
        }
        val slope = median(slopes)
        val intercept = median(z.indices.map { z[it] - slope * it })
        val window = positions.map { months[it] }
        val fitted = DoubleArray(positions.size) { k -> max(0.0, intercept + slope * k) * s(positions[k]) }
        val level = intercept + slope * (positions.size - 1)
        return SeriesModel(window, DoubleArray(positions.size) { y[positions[it]] }, fitted, level, slope, season, last)
    }

    /**
     * Each calendar month's usual size against its year: the month ÷ the mean of the most centred
     * 12 months that fit, averaged per calendar month, shrunk towards 1 by how often it was seen,
     * and scaled to average 1. Needs at least 12 months.
     */
    internal fun yearlyPattern(months: List<YearMonth>, y: DoubleArray): DoubleArray {
        val n = y.size
        val ratios = Array(12) { mutableListOf<Double>() }
        for (i in 0 until n) {
            val start = (i - 6).coerceIn(0, n - 12)
            val mean = (start until start + 12).sumOf { y[it] } / 12
            ratios[months[i].monthValue - 1] += if (mean > 0) y[i] / mean else 1.0
        }
        val shrunk = DoubleArray(12) { k ->
            val seen = ratios[k].size
            if (seen == 0) 1.0 else 1 + seen / (seen + ForecastRules.SEASON_SHRINK) * (ratios[k].average() - 1)
        }
        val mean = shrunk.average()
        return DoubleArray(12) { if (mean > 0) shrunk[it] / mean else 1.0 }
    }

    /**
     * The month under way from today to its end: what is spent so far plus [whole] × the usual
     * share of a month still to come, by the median of how [from]..[to] unfolded. With fewer than
     * [ForecastRules.MIN_MONTHS] of those that spent anything, spending is taken to run evenly.
     */
    private fun monthEnd(whole: Estimate, daily: Map<LocalDate, Money>, month: YearMonth, today: LocalDate, from: YearMonth, to: YearMonth): MonthEnd {
        val byMonth = daily.entries.groupBy({ YearMonth.from(it.key) }, { it.key.dayOfMonth to it.value.minor.toDouble() })
        val curves = generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.mapNotNull { m ->
            val days = DoubleArray(m.lengthOfMonth() + 1)
            byMonth[m].orEmpty().forEach { (day, amount) -> days[day] += amount }
            val total = days.sum()
            if (total > 0) m.lengthOfMonth() to days.also { for (d in it.indices) it[d] /= total } else null
        }.toList()
        fun share(fraction: Double): Double {
            if (curves.size < ForecastRules.MIN_MONTHS) return fraction
            return median(curves.map { (length, days) ->
                val position = fraction * length
                val full = floor(position).toInt().coerceIn(0, length)
                (1..full).sumOf { days[it] } + if (full < length) (position - full) * days[full + 1] else 0.0
            })
        }
        val length = month.lengthOfMonth()
        val day = today.dayOfMonth
        val spent = Money(byMonth[month].orEmpty().sumOf { it.second }.roundToLong())
        val sharedByToday = share(day.toDouble() / length)
        val path = (day..length).map { d ->
            val toCome = (share(d.toDouble() / length) - sharedByToday).coerceAtLeast(0.0)
            fun part(amount: Money) = Money((amount.minor * toCome).roundToLong())
            Estimate(spent + part(whole.expected), spent + part(whole.low), spent + part(whole.high))
        }
        return MonthEnd(month, day, spent, path)
    }
}

/** [ids]' estimate for [month], [lastFinished] being the last month the models were fitted on. */
internal fun estimateOf(models: Map<CategoryId, SeriesModel>, ids: Collection<CategoryId>, month: YearMonth, lastFinished: YearMonth): Estimate {
    val members = ids.mapNotNull { models[it] }
    val expected = Money(members.sumOf { it.predict(month).roundToLong() })
    if (members.isEmpty()) return Estimate(Money.ZERO, Money.ZERO, Money.ZERO)
    // The group's own residuals: in each month any member was fitted on, the members' errors added up.
    val months = members.flatMap { it.window }.toSortedSet().toList().takeLast(ForecastRules.WINDOW)
    val residuals = months.map { m -> members.sumOf { it.residual(m) ?: 0.0 } }
    val center = median(residuals)
    val sigma = ForecastRules.MAD_TO_SIGMA * median(residuals.map { abs(it - center) })
    val ahead = monthsBetween(lastFinished, month)
    val half = (ForecastRules.BAND_Z * sigma * (1 + ForecastRules.BAND_GROWTH * (ahead - 1))).roundToLong()
    return Estimate(expected, Money(max(0L, expected.minor - half)), Money(expected.minor + half))
}

internal fun monthsBetween(from: YearMonth, to: YearMonth): Int = ChronoUnit.MONTHS.between(from, to).toInt()

internal fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
