package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.KeyStatus
import com.example.data.local.ProviderStatus
import com.example.gateway.GatewayRunStatus
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GatewayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            border = border,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            border = border,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val (fg, bg) = when (tone) {
        StatusTone.SUCCESS -> StatusConnected to (if (isDark) StatusConnectedBgDark else StatusConnectedBgLight)
        StatusTone.WARNING -> StatusWarning to (if (isDark) StatusWarningBgDark else StatusWarningBgLight)
        StatusTone.RATE_LIMIT -> StatusRateLimit to (if (isDark) StatusRateLimitBgDark else StatusRateLimitBgLight)
        StatusTone.ERROR -> StatusError to (if (isDark) StatusErrorBgDark else StatusErrorBgLight)
        StatusTone.INFO -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        StatusTone.NEUTRAL -> StatusNeutral to (if (isDark) StatusNeutralBgDark else StatusNeutralBgLight)
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

enum class StatusTone { SUCCESS, WARNING, RATE_LIMIT, ERROR, INFO, NEUTRAL }

@Composable
fun providerStatusLabelAndTone(status: ProviderStatus): Pair<String, StatusTone> {
    val s = LocalStrings.current
    return when (status) {
        ProviderStatus.CONNECTED -> s.connected to StatusTone.SUCCESS
        ProviderStatus.DISCONNECTED -> s.disconnected to StatusTone.NEUTRAL
        ProviderStatus.CHECKING -> s.checking to StatusTone.INFO
        ProviderStatus.ERROR -> s.error to StatusTone.ERROR
        ProviderStatus.RATE_LIMITED -> s.rateLimited to StatusTone.RATE_LIMIT
        ProviderStatus.DISABLED -> s.disabled to StatusTone.NEUTRAL
        ProviderStatus.UNKNOWN -> s.unknown to StatusTone.WARNING
    }
}

@Composable
fun gatewayStatusLabelAndTone(status: GatewayRunStatus): Pair<String, StatusTone> {
    val s = LocalStrings.current
    return when (status) {
        GatewayRunStatus.LOCAL_RUNNING -> s.localRunning to StatusTone.SUCCESS
        GatewayRunStatus.REMOTE_CONNECTED -> s.remoteConnected to StatusTone.SUCCESS
        GatewayRunStatus.STARTING -> s.starting to StatusTone.INFO
        GatewayRunStatus.REMOTE_CONNECTING -> s.remoteConnecting to StatusTone.INFO
        GatewayRunStatus.STOPPED -> s.stopped to StatusTone.NEUTRAL
        GatewayRunStatus.REMOTE_UNAVAILABLE -> s.remoteUnavailable to StatusTone.WARNING
        GatewayRunStatus.ERROR -> s.error to StatusTone.ERROR
    }
}

@Composable
fun keyStatusLabelAndTone(status: KeyStatus): Pair<String, StatusTone> {
    val s = LocalStrings.current
    return when (status) {
        KeyStatus.ACTIVE -> s.connected to StatusTone.SUCCESS
        KeyStatus.INVALID -> s.error to StatusTone.ERROR
        KeyStatus.EXPIRED -> s.error to StatusTone.ERROR
        KeyStatus.RATE_LIMITED -> s.rateLimited to StatusTone.RATE_LIMIT
        KeyStatus.DISABLED -> s.disabled to StatusTone.NEUTRAL
        KeyStatus.UNKNOWN -> s.unknown to StatusTone.WARNING
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector = Icons.Outlined.Inbox,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String = "empty_state_action"
) {
    GatewayCard(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(testTag)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun MetricMiniTile(
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text(s.delete)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel)
            }
        }
    )
}

fun formatTimestamp(ts: Long?): String {
    if (ts == null || ts <= 0L) return "—"
    return SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

fun formatNumberCompact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatCurrency(usdAmount: Double?, currency: String, unknownLabel: String = "Unknown"): String {
    if (usdAmount == null) return unknownLabel
    return when (currency) {
        "BDT" -> String.format(Locale.US, "৳%.2f", usdAmount * 120.0)
        "EUR" -> String.format(Locale.US, "€%.4f", usdAmount * 0.92)
        else -> String.format(Locale.US, "$%.4f", usdAmount)
    }
}
