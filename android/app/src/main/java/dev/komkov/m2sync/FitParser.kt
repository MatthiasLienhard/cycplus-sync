package dev.komkov.m2sync

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesgListener
import java.io.File
import java.io.FileInputStream
import java.time.Instant

/**
 * Parses a .fit file from the bike computer. M2 writes one session per file with 1 Hz points:
 * coordinates, speed, altitude, cadence, heart rate, and power when available.
 */
object FitParser {
    private const val SEMICIRCLES_TO_DEGREES = 180.0 / 2147483648.0

    data class Point(
        val time: Instant,
        val lat: Double?,
        val lon: Double?,
        val altitude: Double?,
        val speed: Double?, // м/с
        val heartRate: Int?,
        val cadence: Int?,
        val distance: Double?, // meters from the start
        val power: Int? = null, // watts
    )

    data class Ride(
        val fileName: String,
        val start: Instant,
        val end: Instant,
        val sport: String?,
        val totalDistance: Double?, // м
        val totalTimerTime: Double?, // с в движении
        val totalAscent: Int?,
        val totalCalories: Int?,
        val avgHeartRate: Int?,
        val points: List<Point>,
        /** Intervals where recording was active; gaps between them are bike computer pauses. */
        val activeSpans: List<Pair<Instant, Instant>>,
    ) {
        val hasRoute: Boolean get() = points.any { it.lat != null && it.lon != null }

        val movingSeconds: Long
            get() =
                activeSpans.sumOf {
                    java.time.Duration
                        .between(it.first, it.second)
                        .seconds
                }
    }

    /** A pause is a recording gap longer than this many seconds. */
    private const val PAUSE_GAP_SECONDS = 5L

    private fun activeSpans(points: List<Point>): List<Pair<Instant, Instant>> {
        if (points.isEmpty()) return emptyList()
        val spans = ArrayList<Pair<Instant, Instant>>()
        var spanStart = points.first().time
        var prev = points.first().time
        for (p in points.drop(1)) {
            if (java.time.Duration
                    .between(prev, p.time)
                    .seconds > PAUSE_GAP_SECONDS
            ) {
                spans += spanStart to prev.plusSeconds(1)
                spanStart = p.time
            }
            prev = p.time
        }
        spans += spanStart to prev.plusSeconds(1)
        return spans
    }

    fun parse(file: File): Ride {
        val points = ArrayList<Point>(8192)
        var start: Instant? = null
        var totalDistance: Double? = null
        var totalTimer: Double? = null
        var ascent: Int? = null
        var calories: Int? = null
        var avgHr: Int? = null
        var sport: String? = null
        var elapsed: Double? = null

        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)

        broadcaster.addListener(
            SessionMesgListener { mesg ->
                mesg.startTime?.let { start = Instant.ofEpochMilli(it.date.time) }
                mesg.totalDistance?.let { totalDistance = it.toDouble() }
                mesg.totalTimerTime?.let { totalTimer = it.toDouble() }
                mesg.totalElapsedTime?.let { elapsed = it.toDouble() }
                mesg.totalAscent?.let { ascent = it.toInt() }
                mesg.totalCalories?.let { calories = it.toInt() }
                mesg.avgHeartRate?.let { avgHr = it.toInt() }
                sport = mesg.sport?.name
            },
        )

        broadcaster.addListener(
            RecordMesgListener { mesg ->
                val ts = mesg.timestamp ?: return@RecordMesgListener
                points.add(
                    Point(
                        time = Instant.ofEpochMilli(ts.date.time),
                        lat = mesg.positionLat?.let { it * SEMICIRCLES_TO_DEGREES },
                        lon = mesg.positionLong?.let { it * SEMICIRCLES_TO_DEGREES },
                        altitude = (mesg.enhancedAltitude ?: mesg.altitude)?.toDouble(),
                        speed = (mesg.enhancedSpeed ?: mesg.speed)?.toDouble(),
                        heartRate = mesg.heartRate?.toInt(),
                        cadence = mesg.cadence?.toInt(),
                        distance = mesg.distance?.toDouble(),
                        power = mesg.power?.toInt(),
                    ),
                )
            },
        )

        FileInputStream(file).use { broadcaster.run(it) }

        if (points.isEmpty()) throw M2Error("no track points in ${file.name}")
        points.sortBy { it.time }

        val startTime = start ?: points.first().time
        // Use the last point for the end, but never before start plus moving time.
        val endTime =
            maxOf(points.last().time, startTime.plusSeconds((elapsed ?: 0.0).toLong()))
                .let { if (it.isAfter(points.last().time.plusSeconds(3600))) points.last().time else it }

        return Ride(
            fileName = file.name,
            start = startTime,
            end = if (endTime.isAfter(startTime)) endTime else startTime.plusSeconds(1),
            sport = sport,
            totalDistance = totalDistance ?: points.lastOrNull()?.distance,
            totalTimerTime = totalTimer,
            totalAscent = ascent,
            totalCalories = calories,
            avgHeartRate = avgHr,
            points = points,
            activeSpans = activeSpans(points),
        )
    }
}
