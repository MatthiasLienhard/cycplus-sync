package dev.komkov.m2sync

import kotlin.math.roundToInt

/** Best average recorded power for logarithmically spaced durations. */
data class PowerBest(
    val seconds: Int,
    val watts: Int,
)

object PowerCurve {
    val durationsSeconds = intArrayOf(1, 5, 15, 30, 60, 300, 600, 1200)

    fun calculate(points: List<TrackPoint>): List<PowerBest> {
        val samples =
            points
                .mapNotNull { point -> point.power?.takeIf { it >= 0 }?.let { point.elapsed to it.toDouble() } }
                .sortedBy { it.first }
        if (samples.isEmpty()) return emptyList()

        return durationsSeconds.toList().mapNotNull { duration ->
            val window = duration.toLong()
            val rideSpan = samples.last().first - samples.first().first
            if (rideSpan < window - 1) {
                return@mapNotNull PowerBest(duration, samples.map { it.second }.average().roundToInt())
            }
            var best: Double? = null
            var end = 0
            var sum = 0.0
            for (start in samples.indices) {
                if (end < start) {
                    end = start
                    sum = 0.0
                }
                while (end < samples.size && samples[end].first - samples[start].first < window) {
                    sum += samples[end].second
                    end++
                }
                val count = end - start
                val covered = if (count > 1) samples[end - 1].first - samples[start].first else 0
                if (count > 0 && covered >= window - 1) {
                    best = maxOf(best ?: Double.NEGATIVE_INFINITY, sum / count)
                }
                sum -= samples[start].second
            }
            best?.let { PowerBest(duration, it.roundToInt()) }
        }
    }
}
