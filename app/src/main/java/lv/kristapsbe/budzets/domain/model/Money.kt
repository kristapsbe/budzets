package lv.kristapsbe.budzets.domain.model

/**
 * An amount in integer minor units of the configured currency (e.g. cents for EUR).
 * Arithmetic is overflow-checked: an overflow throws instead of silently wrapping.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(minor, other.minor))
    operator fun minus(other: Money) = Money(Math.subtractExact(minor, other.minor))
    operator fun unaryMinus() = Money(Math.negateExact(minor))
    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    val isPositive get() = minor > 0
    val isNegative get() = minor < 0
    val isZero get() = minor == 0L

    companion object {
        val ZERO = Money(0)
    }
}

fun Iterable<Money>.sumMoney(): Money = fold(Money.ZERO) { acc, m -> acc + m }
