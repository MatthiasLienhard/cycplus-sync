package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerCurveTest {
    @Test
    fun `best average power uses logarithmic durations`() {
        val points =
            (0..5).map { second ->
                TrackPoint(
                    lat = 0.0,
                    lon = 0.0,
                    x = 0.0,
                    y = 0.0,
                    altitude = 0.0,
                    distance = second.toDouble(),
                    speedKmh = 0.0,
                    heartRate = null,
                    cadence = null,
                    power = (second + 1) * 100,
                    elapsed = second.toLong(),
                )
            }

        val curve = PowerCurve.calculate(points)

        assertEquals(listOf(1, 5, 15, 30, 60, 300, 600, 1200), curve.map { it.seconds })
        assertEquals(600, curve.first().watts)
        assertEquals(400, curve[1].watts)
        assertEquals(350, curve[2].watts)
    }

    @Test
    fun `missing power samples do not create a curve`() {
        val point =
            TrackPoint(
                lat = 0.0,
                lon = 0.0,
                x = 0.0,
                y = 0.0,
                altitude = 0.0,
                distance = 0.0,
                speedKmh = 0.0,
                heartRate = null,
                cadence = null,
                power = null,
                elapsed = 0,
            )

        assertTrue(PowerCurve.calculate(listOf(point)).isEmpty())
    }
}
