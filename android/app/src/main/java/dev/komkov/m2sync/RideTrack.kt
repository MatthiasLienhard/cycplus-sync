package dev.komkov.m2sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Точка трека, приведённая к тому, что нужно рисовать: плоские метры, метры от
 * старта и скорость в км/ч. В .fit всё это лежит в других единицах и не в каждой
 * записи, поэтому пересчитываем один раз при открытии заезда.
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    /** Метры на восток и на север от старта маршрута. */
    val x: Double,
    val y: Double,
    val altitude: Double,
    /** Метры вдоль маршрута от старта. */
    val distance: Double,
    val speedKmh: Double,
    val heartRate: Int?,
    val cadence: Int?,
    /** Секунд от начала заезда. */
    val elapsed: Long,
    val power: Int? = null,
)

/** Что за величину показываем на графике и чем красим трек. */
enum class TrackMetric { ELEVATION, SPEED, HEART_RATE, CADENCE, POWER }

/**
 * Разобранный заезд, готовый к отрисовке.
 *
 * Держится отдельно от [RideSummary]: список на главном экране обходится
 * несколькими числами, а карте и полёту нужны все точки целиком.
 */
class RideTrack(
    val summary: RideSummary,
    val points: List<TrackPoint>,
    val bounds: GeoBounds?,
    val totalDistance: Double,
    val duration: Duration,
    val start: Instant,
    val ascent: Int?,
    val calories: Int?,
) {
    val hasRoute: Boolean get() = points.size >= 2 && bounds != null

    val maxSpeed: Double = points.maxOfOrNull { it.speedKmh } ?: 0.0
    val avgSpeed: Double =
        run {
            val moving = points.filter { it.speedKmh > 1.0 }
            if (moving.isEmpty()) 0.0 else moving.sumOf { it.speedKmh } / moving.size
        }
    val maxHeartRate: Int? = points.mapNotNull { it.heartRate }.filter { it > 0 }.maxOrNull()
    val minAltitude: Double = points.minOfOrNull { it.altitude } ?: 0.0
    val maxAltitude: Double = points.maxOfOrNull { it.altitude } ?: 0.0

    fun has(metric: TrackMetric): Boolean =
        when (metric) {
            TrackMetric.ELEVATION -> maxAltitude - minAltitude > 1.0
            TrackMetric.SPEED -> maxSpeed > 0.5
            TrackMetric.HEART_RATE -> points.any { (it.heartRate ?: 0) > 0 }
            TrackMetric.CADENCE -> points.any { (it.cadence ?: 0) > 0 }
            TrackMetric.POWER -> points.any { (it.power ?: 0) >= 0 }
        }

    fun valueAt(
        index: Int,
        metric: TrackMetric,
    ): Double? {
        val p = points.getOrNull(index) ?: return null
        return when (metric) {
            TrackMetric.ELEVATION -> p.altitude
            TrackMetric.SPEED -> p.speedKmh
            TrackMetric.HEART_RATE -> p.heartRate?.takeIf { it > 0 }?.toDouble()
            TrackMetric.CADENCE -> p.cadence?.takeIf { it > 0 }?.toDouble()
            TrackMetric.POWER -> p.power?.takeIf { it >= 0 }?.toDouble()
        }
    }

    /** Индекс точки, ближайшей к отметке [meters] вдоль маршрута. */
    fun indexAtDistance(meters: Double): Int {
        if (points.isEmpty()) return 0
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].distance < meters) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Курс в точке [index] в радианах, отсчитанный от оси «на восток». */
    fun headingAt(index: Int): Double {
        if (points.size < 2) return 0.0
        // Усредняем по окну: посекундный GPS дёргает курс, и камера в полёте
        // от этого трясётся сильнее, чем едет.
        val a = points[(index - HEADING_WINDOW).coerceIn(0, points.lastIndex)]
        val b = points[(index + HEADING_WINDOW).coerceIn(0, points.lastIndex)]
        if (a === b) return 0.0
        return atan2(b.y - a.y, b.x - a.x)
    }

    companion object {
        private const val HEADING_WINDOW = 4

        /** Точки ближе этого друг к другу считаем стоянкой и выбрасываем. */
        private const val MIN_STEP_METERS = 0.3

        /** Последний открытый заезд: возврат из полёта не должен парсить .fit заново. */
        @Volatile
        private var cached: RideTrack? = null

        suspend fun load(
            ctx: Context,
            summary: RideSummary,
        ): RideTrack? {
            cached?.takeIf { it.summary.file == summary.file }?.let { return it }
            return withContext(Dispatchers.Default) {
                runCatching { build(summary, FitParser.parse(File(SyncService.fitDir(ctx), summary.file))) }
                    .onFailure { LogBus.e(R.string.log_parse_failed, it, summary.file) }
                    .getOrNull()
                    ?.also { cached = it }
            }
        }

        fun build(
            summary: RideSummary,
            ride: FitParser.Ride,
        ): RideTrack {
            val raw = ride.points
            val origin = raw.firstOrNull { it.lat != null && it.lon != null }
            val points = ArrayList<TrackPoint>(raw.size)

            if (origin?.lat != null && origin.lon != null) {
                val originLat = origin.lat
                val originLon = origin.lon
                var distance = 0.0
                var prev: TrackPoint? = null
                for (p in raw) {
                    val lat = p.lat ?: continue
                    val lon = p.lon ?: continue
                    val x = Geo.toLocalX(lon, originLon, originLat)
                    val y = Geo.toLocalY(lat, originLat)
                    val step = prev?.let { hypot(x - it.x, y - it.y) } ?: 0.0
                    if (prev != null && step < MIN_STEP_METERS) continue
                    // Дистанцию предпочитаем ту, что посчитал велокомпьютер: у
                    // него колесо, а у нас дрожащий GPS.
                    distance = p.distance ?: (distance + step)
                    points +=
                        TrackPoint(
                            lat = lat,
                            lon = lon,
                            x = x,
                            y = y,
                            altitude = p.altitude ?: prev?.altitude ?: 0.0,
                            distance = distance,
                            speedKmh = (p.speed ?: 0.0) * 3.6,
                            heartRate = p.heartRate,
                            cadence = p.cadence,
                                            power = p.power,
                            elapsed = Duration.between(ride.start, p.time).seconds.coerceAtLeast(0),
                        ).also { prev = it }
                }
            }

            val smoothed = smoothAltitude(points)
            return RideTrack(
                summary = summary,
                points = smoothed,
                bounds = GeoBounds.of(smoothed),
                totalDistance = ride.totalDistance ?: smoothed.lastOrNull()?.distance ?: 0.0,
                duration = Duration.between(ride.start, ride.end),
                start = ride.start,
                ascent = ride.totalAscent,
                calories = summary.kcal ?: ride.totalCalories,
            )
        }

        /**
         * Барометрическая высота шумит на пару метров от точки к точке. Полёт
         * это показывает как дрожание земли, поэтому сглаживаем скользящим
         * средним — на профиле высот разница почти незаметна.
         */
        private fun smoothAltitude(points: List<TrackPoint>): List<TrackPoint> {
            if (points.size < 8) return points
            val window = 7
            val half = window / 2
            return points.mapIndexed { i, p ->
                var sum = 0.0
                var n = 0
                for (j in (i - half)..(i + half)) {
                    points.getOrNull(j)?.let {
                        sum += it.altitude
                        n++
                    }
                }
                if (n == 0 || abs(sum / n - p.altitude) < 0.01) p else p.copy(altitude = sum / n)
            }
        }
    }
}
