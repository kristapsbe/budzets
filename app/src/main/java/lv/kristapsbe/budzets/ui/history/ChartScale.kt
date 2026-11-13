package lv.kristapsbe.budzets.ui.history

/**
 * A chart's y scale: from [bottom] (zero or below) to [top] (zero or above) in steps of [step], all
 * in minor units and all whole currency units, so every tick reads as a round amount ("€200").
 */
data class ChartScale(val top: Long, val step: Long, val bottom: Long = 0) {
    val ticks: List<Long> get() = (bottom / step..top / step).map { it * step }

    /** [minor]'s height as a fraction of the scale, 0 at [bottom] and 1 at [top]. */
    fun fraction(minor: Long): Float = ((minor - bottom).toDouble() / (top - bottom)).toFloat()

    companion object {
        /** From zero up to at least [maxMinor]; see [around]. */
        fun of(maxMinor: Long, unitMinor: Long, maxSteps: Int = 4): ChartScale = around(0, maxMinor, unitMinor, maxSteps)

        /**
         * The smallest span around zero that reaches [minMinor] below and [maxMinor] above in two to
         * [maxSteps] round steps (1, 2, 2.5 or 5 times a power of ten, in whole units). Of equal
         * spans, the one with more steps, so the axis keeps a few ticks. 2.5 is used only from 25 up,
         * so ticks stay whole.
         *
         * @param unitMinor minor units in one whole currency unit (100 for EUR, 1 for JPY).
         */
        fun around(minMinor: Long, maxMinor: Long, unitMinor: Long, maxSteps: Int = 4): ChartScale {
            require(unitMinor > 0 && maxSteps > 0)
            fun units(minor: Long) = (minor.coerceAtLeast(0) + unitMinor - 1) / unitMinor
            val below = units(-minMinor)
            // Nothing below zero: at least one unit above it, so an empty chart still has a scale.
            val above = if (below == 0L) units(maxMinor.coerceAtLeast(1)) else units(maxMinor)
            val largest = maxOf(above, below)
            val (stepUnits, stepsBelow, stepsAbove) = generateSequence(1L) { it * 10 }
                .takeWhile { it <= largest * 10 }
                .flatMap { power -> listOfNotNull(power, 2 * power, if (power >= 10) 25 * power / 10 else null, 5 * power) }
                .map { step -> Triple(step, (below + step - 1) / step, (above + step - 1) / step) }
                .filter { (_, down, up) -> down + up <= maxSteps }
                // At least two steps where there can be, so the axis has a tick between its ends.
                .minWith(
                    compareBy<Triple<Long, Long, Long>>(
                        { (_, down, up) -> down + up < 2 },
                        { (step, down, up) -> step * (down + up) },
                        { (step, _, _) -> step },
                    ),
                )
            return ChartScale(stepsAbove * stepUnits * unitMinor, stepUnits * unitMinor, -stepsBelow * stepUnits * unitMinor)
        }
    }
}
