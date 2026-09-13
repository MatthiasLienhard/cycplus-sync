package dev.komkov.m2sync

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** Точек по горизонтали: больше на телефоне всё равно не различить. */
private const val BUCKETS = 220

/**
 * Делений отклика на всю ширину графика. Бакетов больше двух сотен, и тик на
 * каждом слился бы в дрожь; сорок — это шаг примерно в палец толщиной, кривая
 * под ним ощущается шкалой.
 */
private const val SCRUB_TICKS = 40

/**
 * Ряд для графика: значения по бакетам и точка трека, представляющая бакет.
 * [values] содержит NaN там, где датчик молчал, — разрыв так и рисуется дырой.
 */
private class Series(
    val values: FloatArray,
    val indices: IntArray,
) {
    val min: Float = values.filter { !it.isNaN() }.minOrNull() ?: 0f
    val max: Float = values.filter { !it.isNaN() }.maxOrNull() ?: 0f
    val avg: Float = values.filter { !it.isNaN() }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
    val any: Boolean = values.any { !it.isNaN() }
}

private fun seriesOf(
    track: RideTrack,
    metric: TrackMetric,
): Series {
    val n = track.points.size
    val buckets = minOf(BUCKETS, n).coerceAtLeast(1)
    val values = FloatArray(buckets)
    val indices = IntArray(buckets)
    for (b in 0 until buckets) {
        val from = (b.toLong() * n / buckets).toInt()
        val to = (((b + 1).toLong() * n / buckets).toInt()).coerceAtLeast(from + 1).coerceAtMost(n)
        var sum = 0.0
        var count = 0
        for (i in from until to) {
            track.valueAt(i, metric)?.let {
                sum += it
                count++
            }
        }
        values[b] = if (count == 0) Float.NaN else (sum / count).toFloat()
        indices[b] = (from + to - 1) / 2
    }
    return Series(values, indices)
}

@Composable
private fun labelOf(metric: TrackMetric): String =
    stringResource(
        when (metric) {
            TrackMetric.ELEVATION -> R.string.chart_elevation
            TrackMetric.SPEED -> R.string.chart_speed
            TrackMetric.HEART_RATE -> R.string.chart_heart_rate
            TrackMetric.CADENCE -> R.string.chart_cadence
            TrackMetric.POWER -> R.string.chart_power
        },
    )

@Composable
private fun unitOf(metric: TrackMetric): String =
    stringResource(
        when (metric) {
            TrackMetric.ELEVATION -> R.string.unit_m
            TrackMetric.SPEED -> R.string.unit_kmh
            TrackMetric.HEART_RATE -> R.string.unit_bpm
            TrackMetric.CADENCE -> R.string.unit_rpm
            TrackMetric.POWER -> R.string.unit_watts
        },
    )

private fun iconOf(metric: TrackMetric) =
    when (metric) {
        TrackMetric.ELEVATION -> Icons.Rounded.Terrain
        TrackMetric.SPEED -> Icons.Rounded.Speed
        TrackMetric.HEART_RATE -> Icons.Rounded.Favorite
        TrackMetric.CADENCE -> Icons.Rounded.Refresh
        TrackMetric.POWER -> Icons.Rounded.Bolt
    }

/**
 * График величины вдоль заезда с переключателем и протяжкой пальцем.
 *
 * Показывает ровно одну величину: четыре наложенных кривых в ширину телефона
 * превращаются в кашу, а переключение чипами — жест Expressive и без того.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideChartCard(
    track: RideTrack,
    metric: TrackMetric,
    onMetric: (TrackMetric) -> Unit,
    highlight: Int?,
    onHighlight: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val haptics = LocalHapticFeedback.current
    val available = remember(track) { TrackMetric.entries.filter { track.has(it) } }
    val series = remember(track, metric) { seriesOf(track, metric) }

    // Тикаем на смене деления, а не на каждом событии протяжки: палец за один
    // проход выдаёт их сотни, и без загрубления это была бы сплошная вибрация.
    val tickStep = remember(series) { (series.indices.size / SCRUB_TICKS).coerceAtLeast(1) }
    var lastTick by remember(track, metric) { mutableIntStateOf(-1) }

    fun scrub(
        x: Float,
        widthPx: Int,
    ) {
        val bucket = bucketAt(x, widthPx, series)
        if (bucket / tickStep != lastTick) {
            lastTick = bucket / tickStep
            haptics.scrubTick()
        }
        onHighlight(series.indices.getOrNull(bucket) ?: 0)
    }

    // Смена величины проявляет новую кривую снизу вверх — иначе подмена
    // происходит рывком и глазу не за что зацепиться.
    val reveal by animateFloatAsState(
        targetValue = if (series.any) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "chartReveal",
    )

    Column(modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { m ->
                FilterChip(
                    selected = m == metric,
                    onClick = {
                        haptics.tick()
                        onMetric(m)
                    },
                    label = { Text(labelOf(m), maxLines = 1) },
                    leadingIcon = { Icon(iconOf(m), null, Modifier.width(18.dp)) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Подпись значения живёт над холстом, а не поверх него: кривая высоты
        // занимает всю высоту кадра, и текст на ней просто не читался.
        val shown = highlight?.let { track.valueAt(it, metric) } ?: series.avg.toDouble()
        Text(
            "${shown.roundToInt()} ${unitOf(metric)}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.primary,
        )
        Text(
            if (highlight != null) {
                stringResource(
                    R.string.chart_at,
                    String.format(locale, "%.2f", track.points[highlight].distance / 1000),
                )
            } else {
                stringResource(R.string.chart_average)
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .pointerInput(track, metric) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrub(it.x, size.width) },
                        onDragEnd = {
                            haptics.gestureEnd()
                            lastTick = -1
                            onHighlight(null)
                        },
                        onDragCancel = {
                            lastTick = -1
                            onHighlight(null)
                        },
                    ) { change, _ ->
                        scrub(change.position.x, size.width)
                    }
                }.pointerInput(track, metric) {
                    detectTapGestures { scrub(it.x, size.width) }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(168.dp)) {
                if (!series.any) return@Canvas

                val bottom = size.height - 18.dp.toPx()
                val top = 12.dp.toPx()
                // Ноль на графике высоты бессмыслен — там важен рельеф, а не
                // абсолютная отметка. Остальные величины считаем от нуля.
                val lo = if (metric == TrackMetric.ELEVATION) series.min - 2f else 0f
                val hi = (series.max + (series.max - lo) * 0.08f).coerceAtLeast(lo + 1f)
                val stepX = size.width / (series.values.size - 1).coerceAtLeast(1)

                fun px(i: Int) = i * stepX

                fun py(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top) * reveal

                repeat(3) { i ->
                    val y = top + (bottom - top) * (i + 1) / 4f
                    drawLine(
                        scheme.outlineVariant.copy(alpha = 0.4f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)),
                    )
                }

                val line = Path()
                val area = Path()
                var open = false
                series.values.forEachIndexed { i, v ->
                    if (v.isNaN()) {
                        if (open) area.lineTo(px(i - 1), bottom)
                        open = false
                        return@forEachIndexed
                    }
                    val x = px(i)
                    val y = py(v)
                    if (!open) {
                        line.moveTo(x, y)
                        area.moveTo(x, bottom)
                        area.lineTo(x, y)
                        open = true
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                if (open) area.lineTo(px(series.values.lastIndex), bottom)

                drawPath(
                    area,
                    brush =
                        Brush.verticalGradient(
                            0f to scheme.primary.copy(alpha = 0.38f),
                            1f to scheme.primary.copy(alpha = 0.02f),
                            startY = top,
                            endY = bottom,
                        ),
                )
                drawPath(
                    line,
                    color = scheme.primary,
                    style =
                        Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )

                val at = highlight?.let { h -> series.indices.indexOfFirst { it >= h }.takeIf { it >= 0 } }
                if (at != null && !series.values[at].isNaN()) {
                    val x = px(at)
                    val y = py(series.values[at])
                    drawLine(
                        scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        Offset(x, top),
                        Offset(x, bottom),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawCircle(scheme.surface, 7.dp.toPx(), Offset(x, y))
                    drawCircle(scheme.primary, 5.dp.toPx(), Offset(x, y))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AxisLabel("0")
            AxisLabel(
                stringResource(
                    R.string.chart_axis_km,
                    String.format(locale, "%.1f", track.totalDistance / 1000),
                ),
            )
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun bucketAt(
    x: Float,
    widthPx: Int,
    series: Series,
): Int {
    if (widthPx <= 0 || series.indices.isEmpty()) return 0
    return (x / widthPx * (series.indices.size - 1))
        .roundToInt()
        .coerceIn(0, series.indices.lastIndex)
}
