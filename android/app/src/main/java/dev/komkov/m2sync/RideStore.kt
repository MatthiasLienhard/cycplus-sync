package dev.komkov.m2sync

import android.content.Context
import java.time.Duration

/** Строит список заездов для экрана из локальных .fit файлов. */
object RideStore {
    /** @param weightKg вес из Health Connect; без него калории не посчитать. */
    fun refresh(
        ctx: Context,
        importedNames: Set<String>,
        weightKg: Double? = null,
        profile: Calories.Profile = Calories.Profile.EMPTY,
    ): List<RideSummary> {
        val files =
            SyncService
                .fitDir(ctx)
                .listFiles { f -> f.name.endsWith(".fit") }
                ?.sortedByDescending { it.name }
                ?: emptyList()

        val cached = AppState.rides.value.associateBy { it.file }
        // Разбор .fit дорогой, поэтому держим кэш — но калории зависят от веса и
        // профиля, и после их правки старое число обязано пересчитаться.
        val key = Calories.profileKey(weightKg, profile)

        val list =
            files
                .mapNotNull { file ->
                    val known = cached[file.name]
                    if (known != null && known.imported == (file.name in importedNames) &&
                        known.kcalKey == key && known.avgPower != null
                    ) {
                        return@mapNotNull known
                    }
                    runCatching {
                        val ride = FitParser.parse(file)
                        val cadences = ride.points.mapNotNull { it.cadence }.filter { it > 0 }
                        val powers = ride.points.mapNotNull { it.power }.filter { it >= 0 }
                        RideSummary(
                            file = file.name,
                            start = ride.start,
                            distanceM = ride.totalDistance ?: 0.0,
                            elapsedMin = Duration.between(ride.start, ride.end).toMinutes(),
                            // Считаем по тем же отрезкам, что уходят в Health Connect сегментами,
                            // иначе цифра в списке разойдётся с активной длительностью там.
                            movingMin = ride.movingSeconds / 60,
                            avgHeartRate = ride.avgHeartRate,
                            avgCadence = if (cadences.isEmpty()) null else cadences.average().toInt(),
                            avgPower = if (powers.isEmpty()) null else powers.average().toInt(),
                            ascent = ride.totalAscent,
                            points = ride.points.size,
                            hasRoute = ride.hasRoute,
                            imported = file.name in importedNames,
                            kcal = Calories.forRide(ride, weightKg, profile),
                            kcalKey = key,
                        )
                    }.onFailure { LogBus.e(R.string.log_parse_failed, it, file.name) }.getOrNull()
                }.sortedByDescending { it.start }

        AppState.saveRides(ctx, list)
        return list
    }
}
