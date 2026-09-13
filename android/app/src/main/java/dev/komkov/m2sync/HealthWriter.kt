package dev.komkov.m2sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FhirResource
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Writes a ride to Health Connect.
 *
 * Deduplication uses a clientRecordId in the form "m2:<file name>": importing
 * the same file again updates the record instead of creating duplicates.
 */
object HealthWriter {
    /** Health Connect does not accept more route points in one record. */
    private const val MAX_ROUTE_POINTS = 1000
    private const val MAX_SAMPLES_PER_RECORD = 1000

    private val SCALE_DEVICE =
        Device(
            manufacturer = "Xiaomi",
            model = "Mi Smart Scale 2",
            type = Device.TYPE_SCALE,
        )

    private val DEVICE =
        Device(
            manufacturer = "CYCPLUS",
            model = "M2",
            type = Device.TYPE_UNKNOWN,
        )

    val permissions: Set<String> =
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(ElevationGainedRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
        )

    /** Self-check plus weight: we calculate calories, while another source owns weight. */
    val readPermissions: Set<String> =
        setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getReadPermission(PowerRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

    /**
    * Medical records are a separate Health Connect branch with their own permission,
    * and are not available on every device. Request them only when available.
     */
    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    val medicalPermissions: Set<String> =
        setOf(
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS,
        )

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    fun personalRecordsAvailable(ctx: Context): Boolean =
        runCatching {
            client(ctx).features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)

    /**
    * Reads birth year and sex from a FHIR Patient resource. This is populated only
    * by a provider's medical-record import, so it is null for most users and the
    * profile is taken from the dialog instead.
     */
    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    suspend fun readPersonalDetails(ctx: Context): Calories.Profile? {
        if (!personalRecordsAvailable(ctx)) return null
        if (HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS !in granted(ctx)) return null
        val resources =
            runCatching {
                client(ctx)
                    .readMedicalResources(
                        ReadMedicalResourcesInitialRequest(
                            medicalResourceType = MedicalResource.MEDICAL_RESOURCE_TYPE_PERSONAL_DETAILS,
                            medicalDataSourceIds = emptySet(),
                        ),
                    ).medicalResources
            }.getOrNull().orEmpty()

        return resources
            .filter { it.fhirResource.type == FhirResource.FHIR_RESOURCE_TYPE_PATIENT }
            .firstNotNullOfOrNull { Calories.profileFromFhir(it.fhirResource.data) }
    }

    /**
    * The latest known weight is used for all calorie calculations.
    * Any source is acceptable: scale, Fit, or manual entry.
     */
    suspend fun readLatestWeight(ctx: Context): WeightReading? =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.before(java.time.Instant.now()),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records
            .firstOrNull()
            ?.let { WeightReading(it.weight.inKilograms, it.time) }

    /** A scale measurement. The source is the scale itself, so record it as the device. */
    suspend fun writeWeight(
        ctx: Context,
        kilograms: Double,
        at: java.time.Instant,
    ): Unit =
        client(ctx)
            .insertRecords(
                listOf(
                    WeightRecord(
                        time = at,
                        zoneOffset = ZoneId.systemDefault().rules.getOffset(at),
                        weight =
                            androidx.health.connect.client.units.Mass
                                .kilograms(kilograms),
                        metadata = Metadata.autoRecorded(SCALE_DEVICE),
                    ),
                ),
            ).let { }

    suspend fun readSessions(
        ctx: Context,
        since: java.time.Instant,
    ): List<ExerciseSessionRecord> =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(since),
                ),
            ).records

    /** Only our records; otherwise entries written by Google Fit also enter the range. */
    private fun ownOrigin(ctx: Context) = setOf(DataOrigin(ctx.packageName))

    suspend fun readDistanceTotal(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Double =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    dataOriginFilter = ownOrigin(ctx),
                ),
            ).records
            .sumOf { it.distance.inMeters }

    suspend fun readHeartRateCount(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Int =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    dataOriginFilter = ownOrigin(ctx),
                ),
            ).records
            .sumOf { it.samples.size }

    suspend fun readCadenceCount(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Int =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = CyclingPedalingCadenceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    dataOriginFilter = ownOrigin(ctx),
                ),
            ).records
            .sumOf { it.samples.size }

    suspend fun readSpeedCount(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Int =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = SpeedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    dataOriginFilter = ownOrigin(ctx),
                ),
            ).records
            .sumOf { it.samples.size }

    suspend fun readCaloriesTotal(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Double =
        client(ctx)
            .readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    dataOriginFilter = ownOrigin(ctx),
                ),
            ).records
            .sumOf { it.energy.inKilocalories }

    /**
    * Shows who else writes in the same time range: record type -> source -> sample count.
    * Fit draws charts from all sources together, so foreign records matter because
    * they explain differences from the .fit file.
     */
    suspend fun readOrigins(
        ctx: Context,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Map<String, Map<String, Int>> {
        val hc = client(ctx)
        val range = TimeRangeFilter.between(from, to)

        suspend fun <T : Record> count(
            type: kotlin.reflect.KClass<T>,
            samples: (T) -> Int,
        ): Map<String, Int> =
            hc
                .readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = range))
                .records
                .groupingBy { it.metadata.dataOrigin.packageName }
                .fold(0) { acc, r -> acc + samples(r) }

        return linkedMapOf(
            "heart rate" to count(HeartRateRecord::class) { it.samples.size },
            "speed" to count(SpeedRecord::class) { it.samples.size },
            "cadence" to count(CyclingPedalingCadenceRecord::class) { it.samples.size },
            "power" to count(PowerRecord::class) { it.samples.size },
            "distance" to count(DistanceRecord::class) { 1 },
            "session" to count(ExerciseSessionRecord::class) { 1 },
        )
    }

    fun available(ctx: Context): Boolean = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context): HealthConnectClient = HealthConnectClient.getOrCreate(ctx)

    suspend fun granted(ctx: Context): Set<String> = client(ctx).permissionController.getGrantedPermissions()

    suspend fun write(
        ctx: Context,
        ride: FitParser.Ride,
        weightKg: Double? = null,
        profile: Calories.Profile = Calories.Profile.EMPTY,
    ): Int {
        val hc = client(ctx)
        val zone = ZoneId.systemDefault()
        val startOffset: ZoneOffset = zone.rules.getOffset(ride.start)
        val endOffset: ZoneOffset = zone.rules.getOffset(ride.end)
        val id = "m2:${ride.fileName}"

        fun meta(suffix: String) = Metadata.autoRecorded(DEVICE, "$id:$suffix")

        val records = ArrayList<Record>()

        val route =
            ride.points
                .filter {
                    it.lat != null &&
                        it.lon != null &&
                        !it.time.isBefore(ride.start) &&
                        it.time.isBefore(ride.end)
                }
                .downsample(MAX_ROUTE_POINTS)
                .map {
                    ExerciseRoute.Location(
                        time = it.time,
                        latitude = it.lat!!,
                        longitude = it.lon!!,
                        altitude = it.altitude?.let { a -> Length.meters(a) },
                    )
                }

        // The session covers the entire ride, while stops are marked as pauses so
        // Health Connect's active duration matches the time spent moving.
        val segments = ArrayList<ExerciseSegment>()
        ride.activeSpans.forEachIndexed { i, span ->
            if (i > 0) {
                val gapStart = ride.activeSpans[i - 1].second
                if (gapStart.isBefore(span.first)) {
                    segments +=
                        ExerciseSegment(
                            startTime = gapStart,
                            endTime = span.first,
                            segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                        )
                }
            }
            segments +=
                ExerciseSegment(
                    startTime = span.first,
                    endTime = span.second,
                    segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING,
                )
        }

        records +=
            ExerciseSessionRecord(
                startTime = ride.start,
                startZoneOffset = startOffset,
                endTime = ride.end,
                endZoneOffset = endOffset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                title = ctx.getString(R.string.session_title),
                notes = ride.fileName,
                metadata = Metadata.autoRecorded(DEVICE, id),
                segments =
                    segments.filter {
                        !it.startTime.isBefore(ride.start) && !it.endTime.isAfter(ride.end)
                    },
                exerciseRoute = if (route.isNotEmpty()) ExerciseRoute(route) else null,
            )

        ride.totalDistance?.takeIf { it > 0 }?.let {
            records +=
                DistanceRecord(
                    startTime = ride.start,
                    startZoneOffset = startOffset,
                    endTime = ride.end,
                    endZoneOffset = endOffset,
                    distance = Length.meters(it),
                    metadata = meta("distance"),
                )
        }

        Calories.forRide(ride, weightKg, profile)?.let {
            records +=
                TotalCaloriesBurnedRecord(
                    startTime = ride.start,
                    startZoneOffset = startOffset,
                    endTime = ride.end,
                    endZoneOffset = endOffset,
                    energy = Energy.kilocalories(it.toDouble()),
                    metadata = meta("calories"),
                )
        }

        ride.totalAscent?.takeIf { it > 0 }?.let {
            records +=
                ElevationGainedRecord(
                    startTime = ride.start,
                    startZoneOffset = startOffset,
                    endTime = ride.end,
                    endZoneOffset = endOffset,
                    elevation = Length.meters(it.toDouble()),
                    metadata = meta("ascent"),
                )
        }

        val hrPoints = ride.points.filter { (it.heartRate ?: 0) > 0 }
        hrPoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records +=
                HeartRateRecord(
                    startTime = chunk.first().time,
                    startZoneOffset = startOffset,
                    endTime = chunk.last().time.plusSeconds(1),
                    endZoneOffset = endOffset,
                    samples =
                        chunk.map {
                            HeartRateRecord.Sample(it.time, it.heartRate!!.toLong())
                        },
                    metadata = meta("hr$i"),
                )
        }

        val cadencePoints = ride.points.filter { (it.cadence ?: 0) > 0 }
        cadencePoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records +=
                CyclingPedalingCadenceRecord(
                    startTime = chunk.first().time,
                    startZoneOffset = startOffset,
                    endTime = chunk.last().time.plusSeconds(1),
                    endZoneOffset = endOffset,
                    samples =
                        chunk.map {
                            CyclingPedalingCadenceRecord.Sample(it.time, it.cadence!!.toDouble())
                        },
                    metadata = meta("cadence$i"),
                )
        }

        val speedPoints = ride.points.filter { (it.speed ?: 0.0) > 0.0 }
        speedPoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records +=
                SpeedRecord(
                    startTime = chunk.first().time,
                    startZoneOffset = startOffset,
                    endTime = chunk.last().time.plusSeconds(1),
                    endZoneOffset = endOffset,
                    samples =
                        chunk.map {
                            SpeedRecord.Sample(it.time, Velocity.metersPerSecond(it.speed!!))
                        },
                    metadata = meta("speed$i"),
                )
        }

        val powerPoints = ride.points.filter { it.power != null && it.power >= 0 }
        powerPoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records +=
                PowerRecord(
                    startTime = chunk.first().time,
                    startZoneOffset = startOffset,
                    endTime = chunk.last().time.plusSeconds(1),
                    endZoneOffset = endOffset,
                    samples =
                        chunk.map {
                            PowerRecord.Sample(it.time, Power.watts(it.power!!.toDouble()))
                        },
                    metadata = meta("power$i"),
                )
        }

        hc.insertRecords(records)
        return records.size
    }

    private fun <T> List<T>.downsample(max: Int): List<T> {
        if (size <= max) return this
        val step = size.toDouble() / max
        return (0 until max).map { this[(it * step).toInt()] }
    }
}
