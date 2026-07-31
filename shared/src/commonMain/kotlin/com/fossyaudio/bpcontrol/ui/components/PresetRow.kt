package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.model.PresetSource
import com.fossyaudio.bpcontrol.ui.theme.Sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val activeRowColor = Color(0xFF221E2B)
private val activeTileColor = Color(0xFF2A2338)
private val defaultSparkStroke = Color(0xFF00BFFF)

/** One row of the preset library: source tile, name, metadata, sparkline. Tap to load. */
@Composable
fun PresetRow(
    preset: Preset,
    active: Boolean,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (active) activeRowColor else Color.Transparent)
            .clickable(enabled = enabled, onClick = onLoad)
            .padding(horizontal = Sp.l, vertical = Sp.m),
        horizontalArrangement = Arrangement.spacedBy(Sp.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceTile(preset = preset, active = active)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = presetMeta(preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 28.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp)),
        ) {
            PresetSparkline(
                bands = preset.bands,
                stroke = if (active) MaterialTheme.colorScheme.primary else defaultSparkStroke,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SourceTile(preset: Preset, active: Boolean) {
    val icon: ImageVector = when {
        preset.name == "None" -> Icons.Filled.Cable
        preset.source == PresetSource.BUILT_IN -> Icons.Filled.HorizontalRule
        preset.source == PresetSource.IMPORTED -> Icons.Filled.Description
        else -> Icons.Filled.Edit
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                if (active) activeTileColor else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun presetMeta(preset: Preset): String {
    if (preset.name == "None") return "Live hardware read"
    return when (preset.source) {
        PresetSource.BUILT_IN -> "Built in · ${preset.bands.size} bands"
        PresetSource.IMPORTED -> "Imported file" + savedAtSuffix(preset.savedAt)
        PresetSource.MANUAL -> "Manual" + savedAtSuffix(preset.savedAt, prefix = " · edited ")
    }
}

private fun savedAtSuffix(savedAt: Long, prefix: String = " · "): String {
    if (savedAt <= 0L) return ""
    return prefix + formatSavedAt(savedAt)
}

private val monthAbbrev = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun formatSavedAt(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.dayOfMonth} ${monthAbbrev[dt.monthNumber - 1]}"
}
