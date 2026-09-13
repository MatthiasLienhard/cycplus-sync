package dev.komkov.m2sync

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

/** За столько секунд пролетаем весь заезд на однократной скорости. */
private const val FLIGHT_SECONDS = 75.0

private val SPEEDS = listOf(1f, 2f, 4f)

/** Делений отклика на всю длину заезда, когда его перематывают пальцем. */
private const val SCRUB_TICKS = 40

/**
 * Полёт над заездом: камера идёт по треку, земля — тайлы OpenStreetMap,
 * натянутые на плоскость в перспективе, трек — лента, поднятая на свою высоту.
 *
 * Не карта в 3D в честном смысле — рельефа под землёй нет, и высота живёт
 * только в самом треке. Зато не нужен ни ключ, ни аккаунт, ни движок: ровно то,
 * чем приложение и держится.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlyView(
    track: RideTrack,
    tiles: TileSource,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val locale = LocalConfiguration.current.locales[0]
    val layer by Settings.mapLayer.collectAsStateWithLifecycle()

    var meters by remember(track) { mutableFloatStateOf(0f) }
    var playing by remember(track) { mutableStateOf(true) }
    var rate by remember { mutableFloatStateOf(1f) }

    // Ручная камера поверх погони: облёт, высота и удаление.
    var yaw by remember(track) { mutableFloatStateOf(0f) }
    var heightScale by remember(track) { mutableFloatStateOf(1f) }
    var distanceScale by remember(track) { mutableFloatStateOf(1f) }
    val moved = yaw != 0f || heightScale != 1f || distanceScale != 1f

    val total = track.totalDistance.toFloat().coerceAtLeast(1f)

    // Перемотка отзывается делениями, а не каждым событием протяжки: иначе это
    // не шкала под пальцем, а сплошная вибрация.
    var lastTick by remember(track) { mutableIntStateOf(-1) }

    fun seek(
        x: Float,
        widthPx: Int,
    ) {
        if (widthPx <= 0) return
        val at = (x / widthPx * total).coerceIn(0f, total)
        val step = (at / total * SCRUB_TICKS).toInt()
        if (step != lastTick) {
            lastTick = step
            haptics.scrubTick()
        }
        meters = at
    }

    /** Камера возвращается в исходное положение — с земли, из-за спины райдера. */
    fun resetCamera() {
        haptics.gestureEnd()
        yaw = 0f
        heightScale = 1f
        distanceScale = 1f
    }

    val palette =
        remember(scheme, dark, layer) {
            val satellite = layer == MapLayer.SATELLITE
            // Над снимком тема уступает натуре: воздушная дымка в жизни голубовато-
            // белая, и зелёный от Material You превращал бы Дунай в болото. Оттенок
            // темы при этом остаётся — просто уведённый к нейтральному.
            val haze =
                lerp(scheme.surface, scheme.primaryContainer, if (dark) 0.5f else 0.8f)
                    .let { if (satellite) lerp(it, Color(0xFFC6D8E8), 0.62f) else it }

            FlyPalette(
                skyTop =
                    if (dark) {
                        lerp(scheme.surfaceContainerLowest, Color.Black, 0.55f)
                    } else {
                        lerp(scheme.surfaceContainerHighest, scheme.primary, 0.10f)
                            .let { if (satellite) lerp(it, Color(0xFF9FC0DC), 0.5f) else it }
                    },
                skyHorizon = haze,
                // Земля заметно темнее неба: иначе горизонт не читается, а дыра под
                // камерой, где тайл выброшен, выглядит проплешиной.
                ground =
                    if (dark) {
                        lerp(scheme.surfaceContainerLowest, Color.Black, 0.45f)
                    } else {
                        lerp(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, 0.28f)
                    },
                grid = scheme.primary.copy(alpha = if (dark) 0.24f else 0.32f),
                slow = scheme.tertiary,
                mid = scheme.secondary,
                fast = scheme.primary,
                marker = scheme.primary,
                milestone = scheme.tertiary,
                // Снимок и без дымки читается объёмным, а вот схема без неё
                // выглядит наклеенной, поэтому туда её кладём щедрее.
                fogAlpha = if (satellite) 0.42f else 0.7f,
                fogSpan = if (satellite) 0.20f else 0.30f,
            )
        }
    val paint = remember(dark, layer) { groundPaint(dark, layer) }

    LaunchedEffect(playing, rate, track) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && playing) {
                    // Кадр мог задержаться на секунды (свернули приложение) —
                    // за такой скачок пролетели бы половину заезда.
                    val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(0.1)
                    val next = meters + (total / FLIGHT_SECONDS * rate * dt).toFloat()
                    if (next >= total) {
                        meters = total
                        playing = false
                        // Прилетели: то же «готово», что и в конце синка.
                        haptics.done()
                    } else {
                        // Круглый километр отзывается тиком — вроде столбика на
                        // обочине: видно, что заезд идёт, не глядя на цифры.
                        if ((next / 1000).toInt() != (meters / 1000).toInt()) haptics.tick()
                        meters = next
                    }
                }
                last = now
            }
        }
    }

    // Кадр тянет только то, что видно сейчас, и на скорости карта не успевает.
    // Поэтому весь коридор вдоль маршрута заказываем заранее, один раз на заезд.
    LaunchedEffect(track, layer) {
        if (layer.enabled) tiles.prefetch(layer, FLY_GROUND_ZOOM, routeTiles(track, FLY_GROUND_ZOOM))
    }

    BackHandler { onClose() }

    val index = track.indexAtDistance(meters.toDouble())
    val here = track.points.getOrNull(index)

    Box(Modifier.fillMaxSize().background(palette.ground)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(track) {
                    // Один палец водит камеру вокруг райдера и поднимает её,
                    // щипок и разворот двумя — приближают и облетают.
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, rotation ->
                        yaw -= pan.x * 0.004f + rotation * (Math.PI.toFloat() / 180f)
                        heightScale =
                            (heightScale - pan.y * 0.003f)
                                .coerceIn(FLY_HEIGHT_MIN, FLY_HEIGHT_MAX)
                        distanceScale =
                            (distanceScale / zoom)
                                .coerceIn(FLY_DISTANCE_MIN, FLY_DISTANCE_MAX)
                    }
                }.pointerInput(track) {
                    detectTapGestures(onDoubleTap = { resetCamera() })
                },
        ) {
            if (size.width < 1f || size.height < 1f) return@Canvas
            drawFlyScene(
                track = track,
                camera =
                    flyCamera(
                        track = track,
                        meters = meters.toDouble(),
                        width = size.width,
                        height = size.height,
                        yaw = yaw,
                        heightScale = heightScale,
                        distanceScale = distanceScale,
                    ),
                meters = meters.toDouble(),
                tiles = tiles,
                palette = palette,
                paint = paint,
                layer = layer,
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.cd_close))
                }
                Spacer(Modifier.weight(1f))
                // Появляется только когда камеру увели: в исходном виде кнопка
                // «вернуть как было» — лишний орган управления.
                AnimatedVisibility(moved, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    FilledTonalIconButton(
                        onClick = { resetCamera() },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, stringResource(R.string.cd_reset_view))
                    }
                }
                FilledTonalIconButton(onClick = {
                    haptics.tick()
                    Settings.setMapLayer(ctx, layer.next())
                }) {
                    Icon(layerIcon(layer), stringResource(R.string.cd_basemap))
                }
            }

            Surface(
                Modifier.padding(12.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.surface.copy(alpha = 0.88f),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            (here?.speedKmh ?: 0.0).roundToInt().toString(),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.unit_kmh),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            here?.heartRate?.takeIf { it > 0 }?.let {
                                FlyStat(Icons.Rounded.Favorite, it.toString(), stringResource(R.string.unit_bpm))
                            }
                            here?.cadence?.takeIf { it > 0 }?.let {
                                FlyStat(Icons.Rounded.Refresh, it.toString(), stringResource(R.string.unit_rpm))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            here?.let {
                                FlyStat(
                                    Icons.Rounded.Terrain,
                                    it.altitude.roundToInt().toString(),
                                    stringResource(R.string.unit_m),
                                )
                            }
                            here?.power?.takeIf { it >= 0 }?.let {
                                FlyStat(Icons.Rounded.Bolt, it.toString(), stringResource(R.string.unit_watts))
                            }
                        }
                    }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Волнистый индикатор Expressive: он же ползунок — тащишь и
                    // перелетаешь в любое место заезда.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .pointerInput(track) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        playing = false
                                        seek(it.x, size.width)
                                    },
                                    onDragEnd = {
                                        haptics.gestureEnd()
                                        lastTick = -1
                                    },
                                    onDragCancel = { lastTick = -1 },
                                ) { change, _ ->
                                    seek(change.position.x, size.width)
                                }
                            }.pointerInput(track) {
                                detectTapGestures { seek(it.x, size.width) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        LinearWavyProgressIndicator(
                            progress = { (meters / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                R.string.fly_progress,
                                String.format(locale, "%.2f", meters / 1000),
                                String.format(locale, "%.2f", total / 1000),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatClock(here?.elapsed ?: 0),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = {
                            haptics.tick()
                            meters = 0f
                            playing = true
                        }) {
                            Icon(Icons.Rounded.Replay, stringResource(R.string.cd_restart))
                        }
                        FilledIconButton(
                            onClick = {
                                haptics.toggle(!playing)
                                if (meters >= total) meters = 0f
                                playing = !playing
                            },
                            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
                                Modifier.size(IconButtonDefaults.largeIconSize),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        SPEEDS.forEach { value ->
                            FilterChip(
                                selected = rate == value,
                                onClick = {
                                    haptics.tick()
                                    rate = value
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.fly_rate, value.roundToInt()),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** Иконка кнопки подложки: она же показывает, что включено сейчас. */
@Composable
fun layerIcon(layer: MapLayer): ImageVector =
    when (layer) {
        MapLayer.NONE -> Icons.Rounded.LayersClear
        MapLayer.MAP -> Icons.Rounded.Map
        MapLayer.SATELLITE -> Icons.Rounded.SatelliteAlt
    }

@Composable
private fun FlyStat(
    icon: ImageVector,
    value: String,
    unit: String,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Icon(
            icon,
            null,
            Modifier.size(16.dp).padding(bottom = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(2.dp))
        Text(
            unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
