package dev.komkov.m2sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Экран одного заезда: карта маршрута, цифры и графики, кнопка полёта.
 *
 * Разбор .fit идёт вне главного потока — файл на сорок километров это две
 * тысячи точек, и на открытии экрана это заметно.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun RideDetailScreen(
    ride: RideSummary,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val haptics = LocalHapticFeedback.current
    val tiles = rememberTileSource()
    val mapLayer by Settings.mapLayer.collectAsStateWithLifecycle()

    var track by remember(ride.file) { mutableStateOf<RideTrack?>(null) }
    var metric by remember(ride.file) { mutableStateOf(TrackMetric.ELEVATION) }
    var highlight by remember(ride.file) { mutableStateOf<Int?>(null) }
    var flying by remember(ride.file) { mutableStateOf(false) }
    var unreadable by remember(ride.file) { mutableStateOf(false) }

    LaunchedEffect(ride.file) {
        val loaded = RideTrack.load(ctx, ride)
        track = loaded
        // Файл могли удалить снаружи или он побился при передаче — крутить
        // индикатор до бесконечности в таком случае некуда.
        unreadable = loaded == null
    }

    // Прогрев коридора начинаем уже здесь, а не в полёте: пока смотрят цифры и
    // графики, квадраты успевают доехать, и взлёт получается без дыр.
    LaunchedEffect(track, mapLayer) {
        val loaded = track ?: return@LaunchedEffect
        if (loaded.hasRoute && mapLayer.enabled) {
            tiles.prefetch(mapLayer, FLY_GROUND_ZOOM, routeTiles(loaded, FLY_GROUND_ZOOM))
        }
    }

    // Первый график выбираем по тому, что в заезде вообще есть: без высоты
    // экран открывался бы пустой рамкой.
    LaunchedEffect(track) {
        track?.let { t -> if (!t.has(metric)) metric = TrackMetric.entries.firstOrNull { t.has(it) } ?: metric }
    }

    val loaded = track
    if (flying && loaded != null && loaded.hasRoute) {
        FlyView(loaded, tiles, onClose = { flying = false })
        return
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val dayFormat =
        remember(locale) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                title = {
                    Text(
                        stringResource(
                            R.string.ride_distance,
                            String.format(locale, "%.2f", ride.distanceM / 1000),
                        ),
                    )
                },
                subtitle = { Text(dayFormat.format(ride.start.atZone(ZoneId.systemDefault()))) },
                actions = {
                    IconButton(onClick = { Sharing.shareRide(ctx, ride) }) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.cd_share))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loaded == null) {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    if (unreadable) {
                        Text(
                            stringResource(R.string.detail_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        LoadingIndicator()
                    }
                }
                return@Column
            }

            if (loaded.hasRoute) {
                // Keep the 3D action visible before the map; it is the primary route action.
                Button(
                    onClick = {
                        haptics.done()
                        flying = true
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                ) {
                    Icon(Icons.Rounded.ViewInAr, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.btn_fly),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                ElevatedCard(shape = RoundedCornerShape(28.dp)) {
                    Box {
                        RouteMap(
                            track = loaded,
                            tiles = tiles,
                            highlight = highlight,
                            metric = metric,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(28.dp)),
                        )
                        FilledTonalIconButton(
                            onClick = {
                                haptics.tick()
                                Settings.setMapLayer(ctx, mapLayer.next())
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(layerIcon(mapLayer), stringResource(R.string.cd_basemap))
                        }
                    }

                    if (TrackMetric.entries.any { loaded.has(it) }) {
                        Card(shape = RoundedCornerShape(28.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                RideChartCard(
                                    track = loaded,
                                    metric = metric,
                                    onMetric = {
                                        metric = it
                                        highlight = null
                                    },
                                    highlight = highlight,
                                    onHighlight = { highlight = it },
                                )
                            }
                        }
                    }
                }

            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2,
            ) {
                val tile = Modifier.weight(1f)
                DetailStat(
                    Icons.Rounded.Route,
                    stringResource(R.string.detail_distance),
                    String.format(locale, "%.2f", loaded.totalDistance / 1000),
                    stringResource(R.string.unit_km),
                    tile,
                )
                DetailStat(
                    Icons.Rounded.Timer,
                    stringResource(R.string.detail_moving),
                    formatClock(ride.movingMin * 60),
                    "",
                    tile,
                )
                DetailStat(
                    Icons.Rounded.Speed,
                    stringResource(R.string.detail_avg_speed),
                    String.format(locale, "%.1f", loaded.avgSpeed),
                    stringResource(R.string.unit_kmh),
                    tile,
                )
                DetailStat(
                    Icons.Rounded.Speed,
                    stringResource(R.string.detail_max_speed),
                    String.format(locale, "%.1f", loaded.maxSpeed),
                    stringResource(R.string.unit_kmh),
                    tile,
                )
                loaded.ascent?.takeIf { it > 0 }?.let {
                    DetailStat(
                        Icons.Rounded.Terrain,
                        stringResource(R.string.detail_ascent),
                        it.toString(),
                        stringResource(R.string.unit_m),
                        tile,
                    )
                }
                ride.avgHeartRate?.let {
                    DetailStat(
                        Icons.Rounded.Favorite,
                        stringResource(R.string.detail_avg_hr),
                        it.toString(),
                        stringResource(R.string.unit_bpm),
                        tile,
                    )
                }
                ride.avgPower?.let {
                    DetailStat(
                        Icons.Rounded.Bolt,
                        stringResource(R.string.detail_avg_power),
                        it.toString(),
                        stringResource(R.string.unit_watts),
                        tile,
                    )
                }
                loaded.maxHeartRate?.let {
                    DetailStat(
                        Icons.Rounded.Favorite,
                        stringResource(R.string.detail_max_hr),
                        it.toString(),
                        stringResource(R.string.unit_bpm),
                        tile,
                    )
                }
                loaded.calories?.let {
                    DetailStat(
                        Icons.Rounded.LocalFireDepartment,
                        stringResource(R.string.detail_calories),
                        it.toString(),
                        stringResource(R.string.unit_kcal),
                        tile,
                    )
                }
                DetailStat(
                    Icons.Rounded.Schedule,
                    stringResource(R.string.detail_elapsed),
                    formatClock(loaded.duration.seconds),
                    "",
                    tile,
                )
                if (loaded.maxAltitude - loaded.minAltitude > 1) {
                    DetailStat(
                        Icons.Rounded.Terrain,
                        stringResource(R.string.detail_altitude_range),
                        "${loaded.minAltitude.roundToInt()}–${loaded.maxAltitude.roundToInt()}",
                        stringResource(R.string.unit_m),
                        tile,
                    )
                }
            }

            val powerCurve = remember(loaded) { PowerCurve.calculate(loaded.points) }
            if (powerCurve.isNotEmpty()) {
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.power_curve_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        powerCurve.forEach { best ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    powerDurationLabel(best.seconds),
                                    modifier = Modifier.width(64.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${best.watts} ${stringResource(R.string.unit_watts)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(ride.imported, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CloudDone,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cd_in_health),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun powerDurationLabel(seconds: Int): String =
    when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 && seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

@Composable
private fun DetailStat(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    null,
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
    }
}
