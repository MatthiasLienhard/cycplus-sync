package dev.komkov.m2sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Снимок состояния велокомпьютера — то, что показываем на карточке устройства. */
data class DeviceSnapshot(
    val name: String,
    val address: String,
    val firmware: String?,
    val battery: Int?,
    val freeKb: Int?,
    val totalKb: Int?,
    val seenAt: Instant,
) {
    val usedKb: Int?
        get() =
            if (freeKb != null && totalKb != null && freeKb in 0..totalKb) {
                totalKb - freeKb
            } else {
                null
            }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("address", address)
            put("firmware", firmware ?: JSONObject.NULL)
            put("battery", battery ?: JSONObject.NULL)
            put("freeKb", freeKb ?: JSONObject.NULL)
            put("totalKb", totalKb ?: JSONObject.NULL)
            put("seenAt", seenAt.toEpochMilli())
        }

    companion object {
        fun fromJson(o: JSONObject) =
            DeviceSnapshot(
                name = o.getString("name"),
                address = o.getString("address"),
                firmware = o.optString("firmware").takeIf { it.isNotEmpty() && it != "null" },
                battery = o.opt("battery")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                freeKb = o.opt("freeKb")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                totalKb = o.opt("totalKb")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                seenAt = Instant.ofEpochMilli(o.optLong("seenAt")),
            )
    }
}

/** Последний вес: показываем на экране и считаем по нему калории. */
data class WeightReading(
    val kilograms: Double,
    val at: Instant,
)

/** Разобранный заезд для списка на экране. */
data class RideSummary(
    val file: String,
    val start: Instant,
    val distanceM: Double,
    val elapsedMin: Long,
    val movingMin: Long,
    val avgHeartRate: Int?,
    val avgCadence: Int?,
    val avgPower: Int? = null,
    val ascent: Int?,
    val points: Int,
    val hasRoute: Boolean,
    val imported: Boolean,
    /** Наша оценка: велокомп калорий не пишет. */
    val kcal: Int? = null,
    /** Вес и профиль, на которых посчитан [kcal]; сменились — пересчитываем. */
    val kcalKey: String? = null,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("file", file)
            put("start", start.toEpochMilli())
            put("distanceM", distanceM)
            put("elapsedMin", elapsedMin)
            put("movingMin", movingMin)
            put("avgHeartRate", avgHeartRate ?: JSONObject.NULL)
            put("avgCadence", avgCadence ?: JSONObject.NULL)
            put("avgPower", avgPower ?: JSONObject.NULL)
            put("ascent", ascent ?: JSONObject.NULL)
            put("points", points)
            put("hasRoute", hasRoute)
            put("imported", imported)
            put("kcal", kcal ?: JSONObject.NULL)
            put("kcalKey", kcalKey ?: JSONObject.NULL)
        }

    companion object {
        fun fromJson(o: JSONObject) =
            RideSummary(
                file = o.getString("file"),
                start = Instant.ofEpochMilli(o.getLong("start")),
                distanceM = o.getDouble("distanceM"),
                elapsedMin = o.getLong("elapsedMin"),
                movingMin = o.getLong("movingMin"),
                avgHeartRate = o.opt("avgHeartRate")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                avgCadence = o.opt("avgCadence")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                avgPower = o.opt("avgPower")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                ascent = o.opt("ascent")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                points = o.getInt("points"),
                hasRoute = o.getBoolean("hasRoute"),
                imported = o.getBoolean("imported"),
                kcal = o.opt("kcal")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                kcalKey = o.opt("kcalKey")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
    }
}

/**
 * Состояние, общее для сервиса и экрана. Переживает перезапуск приложения:
 * карточка устройства и список заездов кладутся в prefs.
 */
object AppState {
    private const val PREFS = "m2sync_state"
    private const val KEY_DEVICE = "device"
    private const val KEY_RIDES = "rides"
    private const val KEY_WEIGHT = "weight"

    val busy = MutableStateFlow(false)
    val action = MutableStateFlow<String?>(null)
    val device = MutableStateFlow<DeviceSnapshot?>(null)
    val rides = MutableStateFlow<List<RideSummary>>(emptyList())

    /** Прогресс скачивания: имя файла, сколько уже байт, сколько всего. */
    val transfer = MutableStateFlow<Triple<String, Int, Int>?>(null)

    val weight = MutableStateFlow<WeightReading?>(null)

    /** Найденное на GitHub обновление, если оно новее установленного. */
    val update = MutableStateFlow<UpdateChecker.Update?>(null)

    /** Ход скачивания и установки этого обновления; null — ничего не идёт. */
    val updateProgress = MutableStateFlow<UpdateProgress?>(null)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_DEVICE, null)?.let {
            runCatching { device.value = DeviceSnapshot.fromJson(JSONObject(it)) }
        }
        p.getString(KEY_WEIGHT, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                weight.value = WeightReading(o.getDouble("kg"), Instant.ofEpochMilli(o.getLong("at")))
            }
        }
        p.getString(KEY_RIDES, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                rides.value = (0 until arr.length()).map { RideSummary.fromJson(arr.getJSONObject(it)) }
            }
        }
    }

    fun saveDevice(
        ctx: Context,
        snapshot: DeviceSnapshot,
    ) {
        device.value = snapshot
        ctx
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE, snapshot.toJson().toString())
            .apply()
    }

    fun saveWeight(
        ctx: Context,
        reading: WeightReading?,
    ) {
        weight.value = reading
        val json =
            reading?.let {
                JSONObject()
                    .apply {
                        put("kg", it.kilograms)
                        put("at", it.at.toEpochMilli())
                    }.toString()
            }
        ctx
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WEIGHT, json)
            .apply()
    }

    fun saveRides(
        ctx: Context,
        list: List<RideSummary>,
    ) {
        rides.value = list
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        ctx
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RIDES, arr.toString())
            .apply()
    }
}
