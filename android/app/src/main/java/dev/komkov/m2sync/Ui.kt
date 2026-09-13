package dev.komkov.m2sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Локаль читаем через конфигурацию: иначе экран не перерисуется при её смене. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun dayFormat(): DateTimeFormatter {
    val locale = currentLocale()
    return remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    }
}

@Composable
private fun timeFormat(): DateTimeFormatter {
    val locale = currentLocale()
    return remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
}

/** Рисунок велокомпьютера: корпус, экран с зарядом и кольцо батареи вокруг. */
@Composable
fun DeviceArt(
    battery: Int?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(118.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(118.dp)) {
            val body = Size(size.minDimension * 0.5f, size.minDimension * 0.68f)
            val left = (size.width - body.width) / 2
            val top = (size.height - body.height) / 2

            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(left, top),
                size = body,
                cornerRadius = CornerRadius(22f, 22f),
            )
            drawRoundRect(
                color = scheme.outlineVariant,
                topLeft = Offset(left, top),
                size = body,
                cornerRadius = CornerRadius(22f, 22f),
                style = Stroke(width = 3f),
            )
            val screenInset = body.width * 0.12f
            drawRoundRect(
                color = scheme.primaryContainer,
                topLeft = Offset(left + screenInset, top + screenInset),
                size = Size(body.width - screenInset * 2, body.height * 0.62f),
                cornerRadius = CornerRadius(12f, 12f),
            )
            val buttonsY = top + body.height * 0.86f
            listOf(0.3f, 0.5f, 0.7f).forEach { fx ->
                drawCircle(
                    color = scheme.outline,
                    radius = body.width * 0.055f,
                    center = Offset(left + body.width * fx, buttonsY),
                )
            }

            val ring = size.minDimension - 8f
            val ringTopLeft = Offset((size.width - ring) / 2, (size.height - ring) / 2)
            drawArc(
                color = scheme.surfaceVariant,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = Size(ring, ring),
                style = Stroke(width = 7f, cap = StrokeCap.Round),
            )
            if (battery != null) {
                drawArc(
                    color = scheme.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * (battery / 100f),
                    useCenter = false,
                    topLeft = ringTopLeft,
                    size = Size(ring, ring),
                    style = Stroke(width = 7f, cap = StrokeCap.Round),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            Text(
                battery?.let { "$it%" } ?: stringResource(R.string.dash),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.battery_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceCard(
    device: DeviceSnapshot?,
    busy: Boolean,
    action: String?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceArt(device?.battery)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device?.name ?: stringResource(R.string.device_unknown),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    device?.address ?: stringResource(R.string.device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                device?.firmware?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.firmware_label) + ": ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                device?.usedKb?.let { used ->
                    val total = device.totalKb!!
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.memory_line, used, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { used.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
                device?.seenAt?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.last_seen,
                            timeFormat().format(it.atZone(ZoneId.systemDefault())),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(busy) {
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Expressive-индикатор: морфящаяся фигура вместо полосы, она же
                // подсказывает, что работа идёт, но её длительность неизвестна.
                LoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        when (action) {
                            "SYNC" -> R.string.busy_sync
                            "INFO" -> R.string.busy_info
                            "IMPORT" -> R.string.busy_import
                            "VERIFY" -> R.string.busy_verify
                            "WEIGH" -> R.string.busy_weigh
                            "SCAN" -> R.string.busy_scan
                            else -> R.string.busy_other
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
fun TotalsRow(
    rides: List<RideSummary>,
    modifier: Modifier = Modifier,
) {
    val km = rides.sumOf { it.distanceM } / 1000
    val hours = rides.sumOf { it.movingMin } / 60.0
    val locale = currentLocale()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_rides), rides.size.toString(), Modifier.weight(1f))
        StatTile(
            stringResource(R.string.stat_km),
            String.format(locale, "%.0f", km),
            Modifier.weight(1f),
        )
        StatTile(
            stringResource(R.string.stat_hours),
            String.format(locale, "%.1f", hours),
            Modifier.weight(1f),
        )
        StatTile(
            stringResource(R.string.stat_in_health),
            rides.count { it.imported }.toString(),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionsRow(
    onSync: () -> Unit,
    onInfo: () -> Unit,
    onVerify: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Главное действие отдельной кнопкой: втроём в один ряд подписи не помещаются,
    // а «Синхронизировать» — то, ради чего экран открывают.
    val syncInteraction = remember { MutableInteractionSource() }
    val pollInteraction = remember { MutableInteractionSource() }
    val verifyInteraction = remember { MutableInteractionSource() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSync,
            enabled = enabled,
            interactionSource = syncInteraction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_sync), maxLines = 1)
        }
        // ButtonGroup из Expressive: соседняя кнопка поджимается, когда нажимают
        // её пару, поэтому две второстепенные читаются как один орган управления.
        ButtonGroup(
            overflowIndicator = {},
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            customItem(
                buttonGroupContent = {
                    FilledTonalButton(
                        onClick = onInfo,
                        enabled = enabled,
                        interactionSource = pollInteraction,
                        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                        modifier =
                            Modifier
                                .weight(1f)
                                .animateWidth(pollInteraction),
                    ) {
                        Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_poll), maxLines = 1)
                    }
                },
                menuContent = {},
            )
            customItem(
                buttonGroupContent = {
                    OutlinedButton(
                        onClick = onVerify,
                        enabled = enabled,
                        interactionSource = verifyInteraction,
                        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                        modifier =
                            Modifier
                                .weight(1f)
                                .animateWidth(verifyInteraction),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_verify), maxLines = 1)
                    }
                },
                menuContent = {},
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RideCard(
    ride: RideSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val haptics = LocalHapticFeedback.current
    // Expressive-приём: выбранная карточка не только красится, но и меняет форму —
    // состояние читается боковым зрением, без вглядывания в цвет.
    val corner by animateDpAsState(
        targetValue = if (selected) 8.dp else 24.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "rideCardCorner",
    )
    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.longPress()
                    onLongClick()
                },
            ),
        shape = RoundedCornerShape(corner),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    stringResource(
                        R.string.ride_distance,
                        String.format(locale, "%.2f", ride.distanceM / 1000),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    dayFormat().format(ride.start.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (ride.imported) {
                    Icon(
                        Icons.Rounded.CloudDone,
                        stringResource(R.string.cd_in_health),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!selectionMode) {
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.cd_share), Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Metric(Icons.Rounded.Timer, stringResource(R.string.chip_moving, ride.movingMin))
                Metric(Icons.Rounded.Schedule, stringResource(R.string.chip_total, ride.elapsedMin))
                ride.avgHeartRate?.let { Metric(Icons.Rounded.Favorite, stringResource(R.string.chip_hr, it)) }
                ride.avgCadence?.let { Metric(Icons.Rounded.Refresh, stringResource(R.string.chip_cadence, it)) }
                ride.kcal?.let { Metric(Icons.Rounded.LocalFireDepartment, stringResource(R.string.chip_kcal, it)) }
                ride.ascent?.takeIf { it > 0 }?.let {
                    Metric(Icons.Rounded.Terrain, stringResource(R.string.chip_ascent, it))
                }
                if (ride.hasRoute) {
                    Metric(Icons.Rounded.Route, stringResource(R.string.chip_points, ride.points))
                }
            }
        }
    }
}

/** Компактная метрика: иконка и подпись, без контейнера чипа. */
@Composable
private fun Metric(
    icon: ImageVector,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            null,
            Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LogCard(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.log_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                lines.lastOrNull()?.take(40) ?: stringResource(R.string.log_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    lines.takeLast(120).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun RidesList(
    rides: List<RideSummary>,
    selection: Set<String>,
    onToggle: (RideSummary) -> Unit,
    onLongClick: (RideSummary) -> Unit,
    onShare: (RideSummary) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = bottomInset),
    ) {
        item { header() }
        if (rides.isEmpty()) {
            item {
                // Пустой экран объясняет, что делать дальше, а не просто пустует.
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.Sync,
                        null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.rides_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(rides, key = { it.file }) { ride ->
            RideCard(
                ride = ride,
                selected = ride.file in selection,
                selectionMode = selection.isNotEmpty(),
                onClick = { onToggle(ride) },
                onLongClick = { onLongClick(ride) },
                onShare = { onShare(ride) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateCard(
    update: UpdateChecker.Update,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    progress: UpdateProgress? = null,
) {
    Card(
        modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Download,
                null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.update_available, update.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    when (progress?.stage) {
                        UpdateProgress.Stage.DOWNLOAD -> stringResource(R.string.update_downloading)
                        UpdateProgress.Stage.VERIFY -> stringResource(R.string.update_verifying)
                        UpdateProgress.Stage.INSTALL -> stringResource(R.string.update_installing)
                        null -> stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            // Пока идёт установка, кнопки нет: нажимать второй раз нечего, а
            // полоса на её месте отвечает на «сколько ещё ждать».
            if (progress == null) {
                FilledTonalButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download), maxLines = 1)
                }
            }
        }
        if (progress != null) {
            val fraction = progress.fraction
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                // Длину ответа сервер может и не прислать — тогда полоса просто
                // бежит, не обещая доли.
                if (fraction == null) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Профиль для расчёта калорий. Вес приходит из Health Connect, а год рождения
 * и пол там не хранятся — это не типы записей, — поэтому спрашиваем здесь.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val savedYear by Settings.birthYear.collectAsStateWithLifecycle()
    val savedSex by Settings.sex.collectAsStateWithLifecycle()

    var year by remember { mutableStateOf(savedYear?.toString() ?: "") }
    var sex by remember { mutableStateOf(savedSex) }

    val parsedYear = year.toIntOrNull()
    val currentYear =
        java.time.Year
            .now()
            .value
    val yearValid = year.isEmpty() || (parsedYear != null && parsedYear in 1900..currentYear)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.profile_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { new -> year = new.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.profile_birth_year)) },
                    supportingText = {
                        if (!yearValid) Text(stringResource(R.string.profile_year_invalid))
                    },
                    isError = !yearValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Подписи берём заранее: внутри scope группы уже не composable-контекст.
                val maleLabel = stringResource(R.string.profile_male)
                val femaleLabel = stringResource(R.string.profile_female)
                ButtonGroup(
                    overflowIndicator = {},
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    toggleableItem(
                        checked = sex == Calories.Sex.MALE,
                        onCheckedChange = {
                            haptics.tick()
                            sex = Calories.Sex.MALE
                        },
                        label = maleLabel,
                    )
                    toggleableItem(
                        checked = sex == Calories.Sex.FEMALE,
                        onCheckedChange = {
                            haptics.tick()
                            sex = Calories.Sex.FEMALE
                        },
                        label = femaleLabel,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = yearValid,
                onClick = {
                    Settings.setProfile(ctx, parsedYear, sex)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.profile_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_cancel)) }
        },
    )
}

/** Последний вес: его же берёт расчёт калорий, поэтому он на виду. */
@Composable
fun WeightCard(
    weight: WeightReading?,
    onWeigh: () -> Unit,
    enabled: Boolean,
) {
    val locale = currentLocale()
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.MonitorWeight,
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (weight == null) {
                        stringResource(R.string.weight_unknown)
                    } else {
                        stringResource(
                            R.string.weight_value,
                            String.format(locale, "%.2f", weight.kilograms),
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    weight?.let {
                        stringResource(
                            R.string.weight_measured,
                            dayFormat().format(it.at.atZone(ZoneId.systemDefault())),
                        )
                    } ?: stringResource(R.string.weight_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FilledTonalButton(onClick = onWeigh, enabled = enabled) {
                Text(stringResource(R.string.btn_weigh), maxLines = 1)
            }
        }
    }
}
