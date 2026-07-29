package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.ui.theme.Sp

private const val MAX_GAIN_DB = 12f
private val freqRange = 20f..20000f
private val qRange = 0.1f..10f

private val typeLabels = listOf(
    FilterType.PK to "Peak",
    FilterType.LS to "Low shelf",
    FilterType.HS to "High shelf",
)

/** Which value card has a numeric entry dialog open. */
private enum class EditingField { GAIN, Q, FREQ }

/**
 * Full parametric control for one band: type, gain, Q and frequency.
 *
 * Exists so the ten-row filter list can stay collapsed — every field a band has is reachable
 * here, gain by slider and the rest by tapping a value card.
 */
@Composable
fun BandInspector(
    band: FilterBand,
    index: Int,
    onUpdate: (FilterBand) -> Unit,
    onGainDrag: (Float) -> Unit,
    onGainDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf<EditingField?>(null) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Sp.l)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Band ${index + 1} · ${formatFreq(band.freq)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Filter type",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Sp.s))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            typeLabels.forEachIndexed { position, (type, label) ->
                SegmentedButton(
                    selected = band.type == type,
                    onClick = { onUpdate(band.copy(type = type)) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(position, typeLabels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color(0xFF4F378B),
                        activeContentColor = Color(0xFFEADDFF),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(label, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(Sp.m))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Sp.s),
        ) {
            ValueCard(
                caption = "GAIN",
                value = "%+.1f".format(band.gain),
                valueColor = MaterialTheme.colorScheme.primary,
                enabled = enabled,
                onClick = { editing = EditingField.GAIN },
                modifier = Modifier.weight(1f),
            )
            ValueCard(
                caption = "Q",
                value = "%.2f".format(band.q),
                valueColor = MaterialTheme.colorScheme.onSurface,
                enabled = enabled,
                onClick = { editing = EditingField.Q },
                modifier = Modifier.weight(1f),
            )
            ValueCard(
                caption = "FREQ",
                value = formatFreq(band.freq),
                valueColor = MaterialTheme.colorScheme.onSurface,
                enabled = enabled,
                onClick = { editing = EditingField.FREQ },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Sp.s))

        BipolarSlider(
            value = band.gain.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
            onValueChange = { raw ->
                onGainDrag((raw * 10f).toInt() / 10f)
            },
            onValueChangeFinished = { onGainDragEnd(band.gain) },
            valueRange = -MAX_GAIN_DB..MAX_GAIN_DB,
            enabled = enabled,
            trackHeight = 6.dp,
            thumbSize = 22.dp,
            modifier = Modifier.doubleTapToReset(enabled) { onUpdate(band.copy(gain = 0f)) },
        )
    }

    when (editing) {
        EditingField.GAIN -> NumericFieldDialog(
            title = "Band ${index + 1} gain",
            initialValue = band.gain,
            range = -MAX_GAIN_DB..MAX_GAIN_DB,
            suffix = "dB",
            onDismiss = { editing = null },
            onConfirm = { editing = null; onUpdate(band.copy(gain = it)) },
        )
        EditingField.Q -> NumericFieldDialog(
            title = "Band ${index + 1} Q",
            initialValue = band.q,
            range = qRange,
            onDismiss = { editing = null },
            onConfirm = { editing = null; onUpdate(band.copy(q = it)) },
        )
        EditingField.FREQ -> NumericFieldDialog(
            title = "Band ${index + 1} frequency",
            initialValue = band.freq.toFloat(),
            range = freqRange,
            integerOnly = true,
            suffix = "Hz",
            onDismiss = { editing = null },
            onConfirm = { editing = null; onUpdate(band.copy(freq = it.toInt())) },
        )
        null -> Unit
    }
}

@Composable
private fun ValueCard(
    caption: String,
    value: String,
    valueColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 11.dp),
    ) {
        Column {
            Text(
                text = caption,
                fontSize = 10.sp,
                letterSpacing = 0.08.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor,
            )
        }
    }
}

private fun formatFreq(freq: Int): String =
    if (freq >= 1000) "%.1f kHz".format(freq / 1000f) else "$freq Hz"
