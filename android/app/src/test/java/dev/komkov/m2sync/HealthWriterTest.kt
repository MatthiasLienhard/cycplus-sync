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
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Velocity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Instant
import java.time.ZoneId

/**
 * Запись и чтение Health Connect. Настоящего провайдера в JVM нет, поэтому
 * клиент подменён шэдоу — см. [ShadowHealthConnectCompanion].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [ShadowHealthConnectCompanion::class],
    instrumentedPackages = ["androidx.health.connect.client.HealthConnectClient"],
)
class HealthWriterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hc = FakeHealthConnectClient()

    private val start: Instant = Instant.parse("2026-07-25T10:20:49Z")
    private val zone = ZoneId.systemDefault()

    @Before
    fun installFakeClient() {
        ShadowHealthConnectCompanion.reset()
        ShadowHealthConnectCompanion.client = hc
    }

    @After
    fun dropFakeClient() {
        ShadowHealthConnectCompanion.reset()
    }

    // --- фикстуры ---

    private val device = Device(manufacturer = "test", model = "test", type = Device.TYPE_UNKNOWN)

    /**
     * Источник записи выставляет сама Health Connect, конструктора с ним наружу нет.
     * Прочитанные записи иначе не подделать, поэтому подставляем полем.
     */
    private fun meta(origin: String = "") =
        Metadata.autoRecorded(device).also {
            ReflectionHelpers.setField(it, "dataOrigin", DataOrigin(origin))
        }

    private fun at(second: Long): Instant = start.plusSeconds(second)

    private fun offset(instant: Instant) = zone.rules.getOffset(instant)

    private fun point(
        second: Long,
        lat: Double? = null,
        lon: Double? = null,
        altitude: Double? = null,
        speed: Double? = null,
        heartRate: Int? = null,
        cadence: Int? = null,
    ) = FitParser.Point(
        time = at(second),
        lat = lat,
        lon = lon,
        altitude = altitude,
        speed = speed,
        heartRate = heartRate,
        cadence = cadence,
        distance = null,
    )

    private fun ride(
        fileName: String = "20260725102049.fit",
        end: Instant = at(600),
        totalDistance: Double? = 7350.0,
        totalAscent: Int? = 13,
        totalCalories: Int? = 232,
        points: List<FitParser.Point> = emptyList(),
        activeSpans: List<Pair<Instant, Instant>> = listOf(start to end),
    ) = FitParser.Ride(
        fileName = fileName,
        start = start,
        end = end,
        sport = "cycling",
        totalDistance = totalDistance,
        totalTimerTime = 600.0,
        totalAscent = totalAscent,
        totalCalories = totalCalories,
        avgHeartRate = 128,
        points = points,
        activeSpans = activeSpans,
    )

    /** Полный набор точек: и трек, и все три потока сэмплов. */
    private fun richPoints(count: Int): List<FitParser.Point> =
        (0 until count).map {
            point(
                second = it.toLong(),
                lat = 55.75 + it * 1e-5,
                lon = 37.61 + it * 1e-5,
                altitude = 150.0 + it,
                speed = 5.5,
                heartRate = 120 + it % 10,
                cadence = 60 + it % 5,
            )
        }

    private inline fun <reified T : Record> inserted(): List<T> = hc.inserted.filterIsInstance<T>()

    private fun session(): ExerciseSessionRecord = inserted<ExerciseSessionRecord>().single()

    /** Маршрут наружу отдаётся обёрткой, а не самим списком точек. */
    private fun routePoints(): List<ExerciseRoute.Location> =
        (session().exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route

    // --- наборы разрешений ---

    /**
     * Маршрут — отдельное разрешение, а каденс отдельного не имеет и живёт под
    * тем же WRITE_EXERCISE, что и сама тренировка: десять просимых типов
    * сворачиваются в девять строк.
     */
    @Test
    fun `write permissions cover every record the app produces`() {
        assertEquals(
            listOf(
                "android.permission.health.WRITE_DISTANCE",
                "android.permission.health.WRITE_ELEVATION_GAINED",
                "android.permission.health.WRITE_EXERCISE",
                "android.permission.health.WRITE_EXERCISE_ROUTE",
                "android.permission.health.WRITE_HEART_RATE",
                "android.permission.health.WRITE_POWER",
                "android.permission.health.WRITE_SPEED",
                "android.permission.health.WRITE_TOTAL_CALORIES_BURNED",
                "android.permission.health.WRITE_WEIGHT",
            ),
            HealthWriter.permissions.sorted(),
        )
        assertTrue(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in HealthWriter.permissions)
        // Читать на запись не просим: это отдельный набор.
        assertFalse(
            HealthPermission.getReadPermission(WeightRecord::class) in HealthWriter.permissions,
        )
    }

    /** Вес ведёт кто-то другой, поэтому право на чтение веса обязано быть в наборе. */
    @Test
    fun `read permissions include weight`() {
        assertTrue(
            HealthPermission.getReadPermission(WeightRecord::class) in HealthWriter.readPermissions,
        )
        assertEquals(
            listOf(
                "android.permission.health.READ_DISTANCE",
                "android.permission.health.READ_EXERCISE",
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_POWER",
                "android.permission.health.READ_SPEED",
                "android.permission.health.READ_WEIGHT",
            ),
            HealthWriter.readPermissions.sorted(),
        )
    }

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    @Test
    fun `medical permissions ask only for personal details`() {
        assertEquals(
            setOf(HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS),
            HealthWriter.medicalPermissions,
        )
    }

    // --- доступность и клиент ---

    @Test
    fun `client comes from the platform factory`() {
        assertSame(hc, HealthWriter.client(ctx))
    }

    @Test
    fun `availability follows the sdk status`() {
        ShadowHealthConnectCompanion.sdkStatus = HealthConnectClient.SDK_AVAILABLE
        assertTrue(HealthWriter.available(ctx))
        ShadowHealthConnectCompanion.sdkStatus = HealthConnectClient.SDK_UNAVAILABLE
        assertFalse(HealthWriter.available(ctx))
        ShadowHealthConnectCompanion.sdkStatus =
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        assertFalse(HealthWriter.available(ctx))
    }

    @Test
    fun `granted permissions come from the permission controller`() {
        hc.grantedPermissions = setOf("a", "b")
        assertEquals(setOf("a", "b"), runBlocking { HealthWriter.granted(ctx) })
    }

    // --- медкарта ---

    @Test
    fun `personal records availability follows the feature flag`() {
        hc.featureStatus = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        assertTrue(HealthWriter.personalRecordsAvailable(ctx))
        hc.featureStatus = HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        assertFalse(HealthWriter.personalRecordsAvailable(ctx))
    }

    /** Клиента может не быть вовсе — падать из-за этого нельзя. */
    @Test
    fun `personal records are unavailable when the client cannot be created`() {
        ShadowHealthConnectCompanion.client = null
        assertFalse(HealthWriter.personalRecordsAvailable(ctx))
    }

    @Test
    fun `personal details are skipped when the feature is off`() {
        hc.featureStatus = HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        hc.grantedPermissions = HealthWriter.medicalPermissions
        assertNull(runBlocking { HealthWriter.readPersonalDetails(ctx) })
    }

    @Test
    fun `personal details are skipped without the medical permission`() {
        hc.featureStatus = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        hc.grantedPermissions = emptySet()
        assertNull(runBlocking { HealthWriter.readPersonalDetails(ctx) })
    }

    /** Разрешение есть, а медкарта пуста (или чтение упало) — тогда профиля нет. */
    @Test
    fun `personal details are null when the medical record yields nothing`() {
        hc.featureStatus = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        hc.grantedPermissions = HealthWriter.medicalPermissions
        assertNull(runBlocking { HealthWriter.readPersonalDetails(ctx) })
    }

    // --- вес ---

    @Test
    fun `latest weight is read newest first and one at a time`() {
        val time = at(-3600)
        hc.stored[WeightRecord::class] =
            listOf(
                WeightRecord(
                    time = time,
                    zoneOffset = offset(time),
                    weight = Mass.kilograms(72.65),
                    metadata = meta(),
                ),
            )

        val reading = runBlocking { HealthWriter.readLatestWeight(ctx) }!!
        assertEquals(72.65, reading.kilograms, 1e-6)
        assertEquals(time, reading.at)

        val request = hc.readRequests.single()
        assertFalse(request.ascendingOrder)
        assertEquals(1, request.pageSize)
    }

    @Test
    fun `no weight in health connect means no reading`() {
        assertNull(runBlocking { HealthWriter.readLatestWeight(ctx) })
    }

    /** Замер пришёл с весов, значит и в Health Connect он должен числиться за ними. */
    @Test
    fun `a scale measurement is written with the scale as its device`() {
        val time = at(42)
        runBlocking { HealthWriter.writeWeight(ctx, 72.65, time) }

        val record = inserted<WeightRecord>().single()
        assertEquals(72.65, record.weight.inKilograms, 1e-6)
        assertEquals(time, record.time)
        assertEquals(offset(time), record.zoneOffset)
        assertEquals("Mi Smart Scale 2", record.metadata.device?.model)
        assertEquals(Device.TYPE_SCALE, record.metadata.device?.type)
    }

    // --- чтения для самопроверки ---

    @Test
    fun `sessions are read from the given moment onwards`() {
        val record =
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = at(600),
                endZoneOffset = offset(at(600)),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                metadata = meta(),
            )
        hc.stored[ExerciseSessionRecord::class] = listOf(record)

        assertEquals(listOf(record), runBlocking { HealthWriter.readSessions(ctx, start) })
        val filter = hc.readRequests.single().timeRangeFilter
        assertEquals(start, filter.startTime)
        assertNull(filter.endTime)
    }

    /** Считаем только своё: в том же окне параллельно пишет Google Fit. */
    @Test
    fun `distance is summed over own records only`() {
        hc.stored[DistanceRecord::class] = listOf(distance(100.0), distance(250.0))

        val total = runBlocking { HealthWriter.readDistanceTotal(ctx, start, at(600)) }

        assertEquals(350.0, total, 1e-6)
        val request = hc.readRequests.single()
        assertEquals(setOf(DataOrigin(ctx.packageName)), request.dataOriginFilter)
        assertEquals(start, request.timeRangeFilter.startTime)
        assertEquals(at(600), request.timeRangeFilter.endTime)
    }

    @Test
    fun `sample counters add up samples across records`() {
        hc.stored[HeartRateRecord::class] = listOf(heartRate(3), heartRate(2))
        hc.stored[CyclingPedalingCadenceRecord::class] = listOf(cadence(4))
        hc.stored[SpeedRecord::class] = listOf(speed(1), speed(6))

        runBlocking {
            assertEquals(5, HealthWriter.readHeartRateCount(ctx, start, at(600)))
            assertEquals(4, HealthWriter.readCadenceCount(ctx, start, at(600)))
            assertEquals(7, HealthWriter.readSpeedCount(ctx, start, at(600)))
        }
        assertTrue(hc.readRequests.all { it.dataOriginFilter.isNotEmpty() })
    }

    @Test
    fun `calories are summed in kilocalories`() {
        hc.stored[TotalCaloriesBurnedRecord::class] = listOf(calories(100.0), calories(50.0))
        assertEquals(150.0, runBlocking { HealthWriter.readCaloriesTotal(ctx, start, at(600)) }, 1e-6)
    }

    @Test
    fun `empty windows read as zero`() {
        runBlocking {
            assertEquals(0.0, HealthWriter.readDistanceTotal(ctx, start, at(600)), 1e-9)
            assertEquals(0, HealthWriter.readHeartRateCount(ctx, start, at(600)))
            assertEquals(0, HealthWriter.readCadenceCount(ctx, start, at(600)))
            assertEquals(0, HealthWriter.readSpeedCount(ctx, start, at(600)))
            assertEquals(0.0, HealthWriter.readCaloriesTotal(ctx, start, at(600)), 1e-9)
            assertEquals(emptyList<ExerciseSessionRecord>(), HealthWriter.readSessions(ctx, start))
        }
    }

    // --- кто ещё пишет в окно ---

    @Test
    fun `origins report samples per package for every record type`() {
        hc.stored[HeartRateRecord::class] =
            listOf(
                heartRate(3, "dev.komkov.m2sync"),
                heartRate(2, "dev.komkov.m2sync"),
                heartRate(7, "com.google.android.apps.fitness"),
            )
        hc.stored[SpeedRecord::class] = listOf(speed(4, "dev.komkov.m2sync"))
        hc.stored[CyclingPedalingCadenceRecord::class] = listOf(cadence(6, "dev.komkov.m2sync"))
        hc.stored[DistanceRecord::class] =
            listOf(
                distance(100.0, "dev.komkov.m2sync"),
                distance(200.0, "com.google.android.apps.fitness"),
            )

        val origins = runBlocking { HealthWriter.readOrigins(ctx, start, at(600)) }

        assertEquals(
            listOf("heart rate", "speed", "cadence", "power", "distance", "session"),
            origins.keys.toList(),
        )
        assertEquals(mapOf("dev.komkov.m2sync" to 5, "com.google.android.apps.fitness" to 7), origins["heart rate"])
        assertEquals(mapOf("dev.komkov.m2sync" to 4), origins["speed"])
        assertEquals(mapOf("dev.komkov.m2sync" to 6), origins["cadence"])
        assertEquals(emptyMap<String, Int>(), origins["power"])
        // Дистанция считается записями, а не сэмплами: их по одной на источник.
        assertEquals(mapOf("dev.komkov.m2sync" to 1, "com.google.android.apps.fitness" to 1), origins["distance"])
        assertEquals(emptyMap<String, Int>(), origins["session"])
        // Чужие источники смотрим без фильтра по своему пакету.
        assertTrue(hc.readRequests.all { it.dataOriginFilter.isEmpty() })
    }

    // --- запись заезда ---

    @Test
    fun `a full ride is written as a session with every metric`() {
        val count = runBlocking { HealthWriter.write(ctx, ride(points = richPoints(10))) }

        assertEquals(7, count)
        assertEquals(7, hc.inserted.size)

        val session = session()
        assertEquals(start, session.startTime)
        assertEquals(at(600), session.endTime)
        assertEquals(offset(start), session.startZoneOffset)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, session.exerciseType)
        assertEquals(ctx.getString(R.string.session_title), session.title)
        assertEquals("20260725102049.fit", session.notes)
        assertEquals("m2:20260725102049.fit", session.metadata.clientRecordId)
        assertEquals("CYCPLUS", session.metadata.device?.manufacturer)
        assertEquals("M2", session.metadata.device?.model)

        assertEquals(7350.0, inserted<DistanceRecord>().single().distance.inMeters, 1e-6)
        assertEquals(232.0, inserted<TotalCaloriesBurnedRecord>().single().energy.inKilocalories, 1e-6)
        assertEquals(13.0, inserted<ElevationGainedRecord>().single().elevation.inMeters, 1e-6)
    }

    /** Дедупликация держится на clientRecordId: у каждой записи он свой и стабильный. */
    @Test
    fun `every record carries a client record id derived from the file name`() {
        runBlocking { HealthWriter.write(ctx, ride(points = richPoints(10))) }

        assertEquals(
            listOf(
                "m2:20260725102049.fit",
                "m2:20260725102049.fit:distance",
                "m2:20260725102049.fit:calories",
                "m2:20260725102049.fit:ascent",
                "m2:20260725102049.fit:hr0",
                "m2:20260725102049.fit:cadence0",
                "m2:20260725102049.fit:speed0",
            ),
            hc.inserted.map { it.metadata.clientRecordId },
        )
    }

    @Test
    fun `samples land in the records with their timestamps`() {
        runBlocking { HealthWriter.write(ctx, ride(points = richPoints(10))) }

        val hr = inserted<HeartRateRecord>().single()
        assertEquals(10, hr.samples.size)
        assertEquals(at(0), hr.samples.first().time)
        assertEquals(120L, hr.samples.first().beatsPerMinute)
        // Конец записи на секунду позже последнего сэмпла, иначе он в неё не попадёт.
        assertEquals(at(9).plusSeconds(1), hr.endTime)

        val cadence = inserted<CyclingPedalingCadenceRecord>().single()
        assertEquals(10, cadence.samples.size)
        assertEquals(60.0, cadence.samples.first().revolutionsPerMinute, 1e-6)

        val speed = inserted<SpeedRecord>().single()
        assertEquals(10, speed.samples.size)
        assertEquals(
            5.5,
            speed.samples
                .first()
                .speed.inMetersPerSecond,
            1e-6,
        )
    }

    @Test
    fun `the route repeats the track including altitude`() {
        runBlocking { HealthWriter.write(ctx, ride(points = richPoints(3))) }

        val route = routePoints()
        assertEquals(3, route.size)
        assertEquals(at(0), route.first().time)
        assertEquals(55.75, route.first().latitude, 1e-9)
        assertEquals(37.61, route.first().longitude, 1e-9)
        assertEquals(150.0, route.first().altitude!!.inMeters, 1e-6)
    }

    @Test
    fun `route points outside the session are dropped`() {
        val points =
            listOf(
                point(-1, lat = 55.0, lon = 37.0),
                point(0, lat = 55.1, lon = 37.1),
                point(600, lat = 55.2, lon = 37.2),
                point(601, lat = 55.3, lon = 37.3),
            )

        runBlocking { HealthWriter.write(ctx, ride(points = points)) }

        assertEquals(listOf(at(0)), routePoints().map { it.time })
    }

    /** Точек в заезде тысячи, а Health Connect берёт не больше тысячи. */
    @Test
    fun `a long track is downsampled and long sample streams are split`() {
        runBlocking { HealthWriter.write(ctx, ride(end = at(2500), points = richPoints(2500))) }

        assertEquals(1000, routePoints().size)
        assertEquals(listOf(1000, 1000, 500), inserted<HeartRateRecord>().map { it.samples.size })
        assertEquals(
            listOf("m2:20260725102049.fit:hr0", "m2:20260725102049.fit:hr1", "m2:20260725102049.fit:hr2"),
            inserted<HeartRateRecord>().map { it.metadata.clientRecordId },
        )
        assertEquals(listOf(1000, 1000, 500), inserted<SpeedRecord>().map { it.samples.size })
        assertEquals(
            listOf(1000, 1000, 500),
            inserted<CyclingPedalingCadenceRecord>().map { it.samples.size },
        )
    }

    /** Пустой заезд — только сессия: писать нули в Health Connect незачем. */
    @Test
    fun `a ride without metrics yields the session alone`() {
        val count =
            runBlocking {
                HealthWriter.write(
                    ctx,
                    ride(
                        totalDistance = 0.0,
                        totalAscent = 0,
                        totalCalories = null,
                        points = listOf(point(0), point(1)),
                    ),
                )
            }

        assertEquals(1, count)
        assertTrue(session().exerciseRouteResult is ExerciseRouteResult.NoData)
        assertTrue(inserted<DistanceRecord>().isEmpty())
        assertTrue(inserted<ElevationGainedRecord>().isEmpty())
        assertTrue(inserted<TotalCaloriesBurnedRecord>().isEmpty())
        assertTrue(inserted<HeartRateRecord>().isEmpty())
    }

    /** Нулевые сэмплы — это «датчика не было», а не «пульс ноль». */
    @Test
    fun `zero samples are dropped instead of being written`() {
        runBlocking {
            HealthWriter.write(
                ctx,
                ride(
                    totalCalories = null,
                    points =
                        listOf(
                            point(0, heartRate = 0, cadence = 0, speed = 0.0),
                            point(1, heartRate = 130, cadence = 0, speed = 0.0),
                        ),
                ),
            )
        }

        assertEquals(1, inserted<HeartRateRecord>().single().samples.size)
        assertTrue(inserted<CyclingPedalingCadenceRecord>().isEmpty())
        assertTrue(inserted<SpeedRecord>().isEmpty())
    }

    /** Велокомп калорий не пишет — тогда считаем сами по весу и профилю. */
    @Test
    fun `calories are estimated when the file has none`() {
        val profile = Calories.Profile(birthYear = 1990, sex = Calories.Sex.MALE)
        runBlocking {
            HealthWriter.write(
                ctx,
                ride(totalCalories = null, points = richPoints(120)),
                weightKg = 72.65,
                profile = profile,
            )
        }

        val kcal = inserted<TotalCaloriesBurnedRecord>().single().energy.inKilocalories
        assertTrue("ожидали положительный расход, получили $kcal", kcal > 0.0)
    }

    @Test
    fun `without a weight there is nothing to estimate`() {
        runBlocking {
            HealthWriter.write(ctx, ride(totalCalories = null, points = richPoints(120)))
        }
        assertTrue(inserted<TotalCaloriesBurnedRecord>().isEmpty())
    }

    // --- паузы ---

    /** Остановки размечаем паузами: без них «активное время» равно всему заезду. */
    @Test
    fun `gaps between active spans become pause segments`() {
        runBlocking {
            HealthWriter.write(
                ctx,
                ride(
                    points = richPoints(10),
                    activeSpans = listOf(start to at(300), at(400) to at(600)),
                ),
            )
        }

        val segments = session().segments
        assertEquals(3, segments.size)
        assertEquals(
            listOf(
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING,
            ),
            segments.map { it.segmentType },
        )
        assertEquals(at(300), segments[1].startTime)
        assertEquals(at(400), segments[1].endTime)
    }

    /** Отрезки без разрыва паузой не разделяются. */
    @Test
    fun `back to back spans produce no pause`() {
        runBlocking {
            HealthWriter.write(
                ctx,
                ride(points = richPoints(10), activeSpans = listOf(start to at(300), at(300) to at(600))),
            )
        }

        val segments = session().segments
        assertEquals(2, segments.size)
        assertTrue(segments.none { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE })
    }

    /** Health Connect отвергает сессию целиком, если отрезок вылез за её границы. */
    @Test
    fun `segments outside the session are dropped`() {
        runBlocking {
            HealthWriter.write(
                ctx,
                ride(
                    points = richPoints(10),
                    activeSpans = listOf(at(-60) to at(300), at(400) to at(900)),
                ),
            )
        }

        val segments = session().segments
        assertEquals(1, segments.size)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, segments.single().segmentType)
        assertEquals(at(300), segments.single().startTime)
        assertEquals(at(400), segments.single().endTime)
    }

    // --- вспомогательные записи для чтений ---

    private fun distance(
        meters: Double,
        origin: String = "",
    ) = DistanceRecord(
        startTime = start,
        startZoneOffset = offset(start),
        endTime = at(600),
        endZoneOffset = offset(at(600)),
        distance = Length.meters(meters),
        metadata = meta(origin),
    )

    private fun calories(
        kcal: Double,
        origin: String = "",
    ) = TotalCaloriesBurnedRecord(
        startTime = start,
        startZoneOffset = offset(start),
        endTime = at(600),
        endZoneOffset = offset(at(600)),
        energy = Energy.kilocalories(kcal),
        metadata = meta(origin),
    )

    private fun heartRate(
        samples: Int,
        origin: String = "",
    ) = HeartRateRecord(
        startTime = start,
        startZoneOffset = offset(start),
        endTime = at(samples.toLong()),
        endZoneOffset = offset(at(samples.toLong())),
        samples = (0 until samples).map { HeartRateRecord.Sample(at(it.toLong()), 120L) },
        metadata = meta(origin),
    )

    private fun cadence(
        samples: Int,
        origin: String = "",
    ) = CyclingPedalingCadenceRecord(
        startTime = start,
        startZoneOffset = offset(start),
        endTime = at(samples.toLong()),
        endZoneOffset = offset(at(samples.toLong())),
        samples =
            (0 until samples).map {
                CyclingPedalingCadenceRecord.Sample(at(it.toLong()), 60.0)
            },
        metadata = meta(origin),
    )

    private fun speed(
        samples: Int,
        origin: String = "",
    ) = SpeedRecord(
        startTime = start,
        startZoneOffset = offset(start),
        endTime = at(samples.toLong()),
        endZoneOffset = offset(at(samples.toLong())),
        samples =
            (0 until samples).map {
                SpeedRecord.Sample(at(it.toLong()), Velocity.metersPerSecond(5.0))
            },
        metadata = meta(origin),
    )
}
