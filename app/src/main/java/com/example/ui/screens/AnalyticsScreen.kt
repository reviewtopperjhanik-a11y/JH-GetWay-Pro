package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.RequestMetricEntity
import com.example.network.ProviderPresets
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class AnalyticsRange { TODAY, DAYS_7, DAYS_30, ALL }

internal data class TimeBucketPoint(
    val label: String,
    val successCount: Int,
    val failCount: Int,
    val totalCount: Int,
    val avgLatencyMs: Long
)

internal fun aggregateMetricsIntoBuckets(
    metrics: List<RequestMetricEntity>,
    range: AnalyticsRange,
    nowMs: Long = System.currentTimeMillis()
): List<TimeBucketPoint> {
    if (metrics.isEmpty()) return emptyList()
    val earliestTs = metrics.minOf { it.timestamp }
    val dayMs = 86_400_000L
    val bucketCount = when (range) {
        AnalyticsRange.TODAY -> 6
        AnalyticsRange.DAYS_7 -> 7
        AnalyticsRange.DAYS_30 -> 10
        AnalyticsRange.ALL -> 8
    }
    val startMs = when (range) {
        AnalyticsRange.TODAY -> Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        AnalyticsRange.DAYS_7 -> nowMs - 7 * dayMs
        AnalyticsRange.DAYS_30 -> nowMs - 30 * dayMs
        AnalyticsRange.ALL -> earliestTs.coerceAtMost(nowMs - 60_000L)
    }
    val span = (nowMs - startMs).coerceAtLeast(bucketCount.toLong())
    val step = (span / bucketCount).coerceAtLeast(1L)
    val pattern = if (range == AnalyticsRange.TODAY) "HH:mm" else "MMM d"
    val fmt = SimpleDateFormat(pattern, Locale.getDefault())

    return (0 until bucketCount).map { idx ->
        val bStart = startMs + idx * step
        val bEnd = if (idx == bucketCount - 1) Long.MAX_VALUE else bStart + step
        val inBucket = metrics.filter { it.timestamp in bStart until bEnd }
        val ok = inBucket.count { it.success }
        val fail = inBucket.size - ok
        val lat = if (inBucket.isEmpty()) 0L else inBucket.map { it.latencyMs }.average().toLong()
        TimeBucketPoint(
            label = fmt.format(Date(bStart)),
            successCount = ok,
            failCount = fail,
            totalCount = inBucket.size,
            avgLatencyMs = lat
        )
    }
}

@Composable
fun AnalyticsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val metrics by viewModel.metrics.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var range by remember { mutableStateOf(AnalyticsRange.DAYS_7) }
    var statusFilter by remember { mutableStateOf("ALL") } // ALL, SUCCESS, FAILED
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var modelFilter by remember { mutableStateOf<String?>(null) }
    var deviceFilter by remember { mutableStateOf<String?>(null) }

    val distinctProviders = remember(metrics) { metrics.map { it.providerName }.distinct().sorted() }
    val distinctModels = remember(metrics) { metrics.map { it.modelId }.distinct().sorted() }
    val distinctDevices = remember(metrics) { metrics.map { it.deviceName }.distinct().sorted() }

    val filtered = remember(metrics, range, statusFilter, providerFilter, modelFilter, deviceFilter) {
        val now = System.currentTimeMillis()
        val cutoff = when (range) {
            AnalyticsRange.TODAY -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            AnalyticsRange.DAYS_7 -> now - 7 * 86_400_000L
            AnalyticsRange.DAYS_30 -> now - 30 * 86_400_000L
            AnalyticsRange.ALL -> 0L
        }
        metrics.filter { m ->
            m.timestamp >= cutoff &&
                (statusFilter == "ALL" || (statusFilter == "SUCCESS" && m.success) || (statusFilter == "FAILED" && !m.success)) &&
                (providerFilter == null || m.providerName == providerFilter) &&
                (modelFilter == null || m.modelId == modelFilter) &&
                (deviceFilter == null || m.deviceName == deviceFilter)
        }
    }

    val total = filtered.size
    val successCount = filtered.count { it.success }
    val failCount = total - successCount
    val inTokens = filtered.sumOf { it.inputTokens.toLong() }
    val outTokens = filtered.sumOf { it.outputTokens.toLong() }
    val billedCosts = filtered.mapNotNull { it.actualBilledCostUsd }
    val actualBilledTotal: Double? = if (billedCosts.isEmpty()) null else billedCosts.sum()
    val knownEstCosts = filtered.mapNotNull { it.estimatedCostUsd }
    val estCost: Double? = if (knownEstCosts.isEmpty()) null else knownEstCosts.sum()
    val pricingMeta = remember(filtered) {
        val sources = filtered.mapNotNull { it.pricingSource?.takeIf(String::isNotBlank) }.distinct()
        val updatedDates = filtered.mapNotNull { it.pricingUpdatedAt?.takeIf(String::isNotBlank) }.distinct()
        val fallbackDates = filtered
            .filter { it.actualBilledCostUsd == null && it.pricingSource.isNullOrBlank() }
            .mapNotNull { ProviderPresets.lookupPricing(it.modelId)?.updatedAt }
            .distinct()
        val finalDate = updatedDates.maxOrNull() ?: fallbackDates.maxOrNull()
        val sourceText = sources.joinToString(", ").ifBlank { s.unknown }
        sourceText to finalDate
    }
    val avgLatency = if (filtered.isEmpty()) 0L else filtered.map { it.latencyMs }.average().toLong()
    val p95Latency = if (filtered.isEmpty()) 0L else {
        val sorted = filtered.map { it.latencyMs }.sorted()
        sorted[((sorted.size - 1) * 0.95).toInt()]
    }
    val fallbacks = filtered.sumOf { it.fallbackAttempts }
    val actualUsageCount = filtered.count { it.usageSource == "ACTUAL" }
    val estimatedUsageCount = filtered.count { it.usageSource == "ESTIMATED" }
    val unknownUsageCount = filtered.count { it.usageSource != "ACTUAL" && it.usageSource != "ESTIMATED" }
    val buckets = remember(filtered, range) { aggregateMetricsIntoBuckets(filtered, range) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("analytics_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.analyticsTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("USD", "BDT", "EUR").forEach { cur ->
                        FilterChip(
                            selected = settings.analyticsCurrency == cur,
                            onClick = { viewModel.setCurrency(cur) },
                            label = { Text(cur) }
                        )
                    }
                }
            }
        }

        // Timeframe, Status, Provider, Model, Device Filters
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = range == AnalyticsRange.TODAY, onClick = { range = AnalyticsRange.TODAY }, label = { Text(s.today) }) }
                item { FilterChip(selected = range == AnalyticsRange.DAYS_7, onClick = { range = AnalyticsRange.DAYS_7 }, label = { Text(s.last7Days) }) }
                item { FilterChip(selected = range == AnalyticsRange.DAYS_30, onClick = { range = AnalyticsRange.DAYS_30 }, label = { Text(s.last30Days) }) }
                item { FilterChip(selected = range == AnalyticsRange.ALL, onClick = { range = AnalyticsRange.ALL }, label = { Text(s.allTime) }) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = statusFilter == "ALL", onClick = { statusFilter = "ALL" }, label = { Text(s.allStatusLabel) }) }
                item { FilterChip(selected = statusFilter == "SUCCESS", onClick = { statusFilter = "SUCCESS" }, label = { Text(s.success) }) }
                item { FilterChip(selected = statusFilter == "FAILED", onClick = { statusFilter = "FAILED" }, label = { Text(s.error) }) }
                items(distinctProviders) { pn ->
                    FilterChip(
                        selected = providerFilter == pn,
                        onClick = { providerFilter = if (providerFilter == pn) null else pn },
                        label = { Text(pn) }
                    )
                }
            }
            if (distinctModels.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = modelFilter == null,
                            onClick = { modelFilter = null },
                            label = { Text("${s.filterModelLabel}: ${s.allFilterLabel}") }
                        )
                    }
                    items(distinctModels) { mid ->
                        FilterChip(
                            selected = modelFilter == mid,
                            onClick = { modelFilter = if (modelFilter == mid) null else mid },
                            label = { Text(mid) }
                        )
                    }
                }
            }
            if (distinctDevices.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = deviceFilter == null,
                            onClick = { deviceFilter = null },
                            label = { Text("${s.filterDeviceLabel}: ${s.allFilterLabel}") }
                        )
                    }
                    items(distinctDevices) { dn ->
                        FilterChip(
                            selected = deviceFilter == dn,
                            onClick = { deviceFilter = if (deviceFilter == dn) null else dn },
                            label = { Text(dn) }
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Default.Analytics,
                    title = s.analyticsTitle,
                    message = s.noAnalyticsData
                )
            }
        } else {
            item {
                GatewayCard {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isNarrow = maxWidth < 360.dp
                        val topTiles = listOf(
                            Triple(s.totalRequests, total.toString(), MaterialTheme.colorScheme.primary),
                            Triple(s.success, successCount.toString(), StatusConnected),
                            Triple(s.error, failCount.toString(), StatusError),
                            Triple(s.fallbackCount, fallbacks.toString(), MaterialTheme.colorScheme.primary)
                        )
                        val bottomTiles = listOf(
                            Triple(s.inputTokens, formatNumberCompact(inTokens), MaterialTheme.colorScheme.primary),
                            Triple(s.outputTokens, formatNumberCompact(outTokens), MaterialTheme.colorScheme.primary),
                            Triple(s.avgLatency, "${avgLatency}ms", MaterialTheme.colorScheme.primary),
                            Triple(s.p95Latency, "${p95Latency}ms", MaterialTheme.colorScheme.primary)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            topTiles.chunked(if (isNarrow) 2 else 4).forEach { rowTiles ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    rowTiles.forEach { (lbl, value, accent) ->
                                        MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            bottomTiles.chunked(if (isNarrow) 2 else 4).forEach { rowTiles ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    rowTiles.forEach { (lbl, value, accent) ->
                                        MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    if (actualBilledTotal != null) {
                        Text(
                            text = "${s.actualBilledCostLabel}: ${formatCurrency(actualBilledTotal, settings.analyticsCurrency, s.unknown)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = StatusConnected
                        )
                    }
                    Text(
                        text = "${s.costLabel}: ${formatCurrency(estCost, settings.analyticsCurrency, s.unknown)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append("${s.pricingSourceLabel}: ${pricingMeta.first}")
                            pricingMeta.second?.let { append(" ($it)") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${s.tokenAccuracyLabel}: ${s.usageSourceActual} $actualUsageCount • ${s.usageSourceEstimated} $estimatedUsageCount • ${s.unknown} $unknownUsageCount",
                        style = MonoTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Time-Bucketed Requests Over Time Bar Chart with Axis Labels
            item {
                GatewayCard {
                    Text(s.requestsOverTime, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val barColor = MaterialTheme.colorScheme.primary
                    val errBarColor = MaterialTheme.colorScheme.error
                    val axisColor = MaterialTheme.colorScheme.outlineVariant
                    val maxTotal = (buckets.maxOfOrNull { it.totalCount } ?: 1).coerceAtLeast(1)

                    Row(
                        modifier = Modifier.fillMaxWidth().height(136.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight().padding(end = 6.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(maxTotal.toString(), style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("0", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("analytics_time_bucket_chart")
                        ) {
                            drawLine(
                                color = axisColor,
                                start = Offset(0f, size.height - 1f),
                                end = Offset(size.width, size.height - 1f),
                                strokeWidth = 1.dp.toPx()
                            )
                            val count = buckets.size.coerceAtLeast(1)
                            val gap = 6.dp.toPx()
                            val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(8f)
                            buckets.forEachIndexed { idx, pt ->
                                val x = idx * (barW + gap)
                                if (pt.totalCount > 0) {
                                    val totalRatio = (pt.totalCount.toFloat() / maxTotal.toFloat()).coerceIn(0.06f, 1f)
                                    val totalH = size.height * totalRatio
                                    val okH = totalH * (pt.successCount.toFloat() / pt.totalCount.toFloat())
                                    val failH = totalH - okH
                                    if (okH > 0f) {
                                        drawRoundRect(
                                            color = barColor,
                                            topLeft = Offset(x, size.height - okH),
                                            size = Size(barW, okH),
                                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                        )
                                    }
                                    if (failH > 0f) {
                                        drawRoundRect(
                                            color = errBarColor,
                                            topLeft = Offset(x, size.height - okH - failH),
                                            size = Size(barW, failH),
                                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(buckets.firstOrNull()?.label.orEmpty(), style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${s.recentRequestsPrefix}: $total • ${s.avgLatencyLabel}: ${avgLatency}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(buckets.lastOrNull()?.label.orEmpty(), style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Provider Usage Distribution
            item {
                GatewayCard {
                    Text(s.providerUsage, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val byProvider = filtered.groupBy { it.providerName }
                    byProvider.forEach { (pName, list) ->
                        val ratio = list.size.toFloat() / total.toFloat()
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pName, style = MaterialTheme.typography.bodyMedium)
                                Text("${list.size} (${(ratio * 100).toInt()}%)", style = MonoTextStyle)
                            }
                            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }

            // Request Inspector
            item { SectionHeader(title = s.requestDetails) }
            items(filtered.take(40), key = { it.id }) { m ->
                RequestInspectorCard(m, settings.analyticsCurrency)
            }
        }
    }
}

@Composable
private fun RequestInspectorCard(m: RequestMetricEntity, currency: String) {
    val s = LocalStrings.current
    val sourceAccuracy = when (m.usageSource) {
        "ACTUAL" -> s.usageSourceActualDesc
        "ESTIMATED" -> s.usageSourceEstimatedDesc
        else -> s.usageSourceUnknownDesc
    }
    val costText = m.actualBilledCostUsd?.let {
        "${s.actualBilledCostLabel}: ${formatCurrency(it, currency, s.unknown)}"
    } ?: "${s.costLabel}: ${formatCurrency(m.estimatedCostUsd, currency, s.unknown)}"

    GatewayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${m.providerName} • ${m.modelId}", style = MaterialTheme.typography.titleSmall)
                Text("ID: ${m.requestId} • ${m.deviceName} • ${m.endpoint}", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(
                text = "${m.statusCode}",
                tone = if (m.success) StatusTone.SUCCESS else StatusTone.ERROR
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${m.latencyMs}ms • ${m.inputTokens} in / ${m.outputTokens} out (${m.usageSource}) • ${s.fallbacksLabel}: ${m.fallbackAttempts} • $costText",
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${s.usageSourceLabel}: ${m.usageSource} — $sourceAccuracy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(formatTimestamp(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.safeErrorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
