package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.ui.theme.AppType
import com.fossyaudio.bpcontrol.ui.theme.Sp

private val selectedRowColor = Color(0xFF221E2B)

/**
 * One row of the filter list.
 *
 * Secondary to the graph and the inspector since the redesign, so it is tight: the fields are
 * 40 dp tall with 8 dp of padding rather than [androidx.compose.material3.OutlinedTextField]'s
 * 16 dp per side, which left only 32 dp of a 64 dp field for text and clipped "16000" at 360 dp.
 */
@Composable
fun EqBandRow(
    band: FilterBand,
    onUpdate: (FilterBand) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
) {
    var typeExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) selectedRowColor else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = Sp.l, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(Sp.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = band.enabled,
            onCheckedChange = { onUpdate(band.copy(enabled = it)) },
            modifier = Modifier.size(22.dp),
        )

        Box(modifier = Modifier.width(46.dp)) {
            Text(
                text = band.type.name,
                style = AppType.fieldValue,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { typeExpanded = true }
                    .padding(vertical = Sp.s),
            )
            DropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                FilterType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.name, style = AppType.fieldValue) },
                        onClick = {
                            typeExpanded = false
                            onUpdate(band.copy(type = type))
                        },
                    )
                }
            }
        }

        CompactField(
            label = "Freq",
            value = band.freq.toString(),
            key = band.freq,
            integerOnly = true,
            onCommit = { text ->
                val v = text.toIntOrNull()?.coerceIn(20, 20000) ?: band.freq
                onUpdate(band.copy(freq = v))
                v.toString()
            },
            modifier = Modifier.weight(1f),
        )

        // Clamped to +/-12 dB (matches Walkplay PEQ range; Q8.8 encoding supports it)
        CompactField(
            label = "Gain",
            value = "%.2f".format(band.gain),
            key = band.gain,
            onCommit = { text ->
                val v = text.toFloatOrNull()?.coerceIn(-12f, 12f) ?: band.gain
                onUpdate(band.copy(gain = v))
                "%.2f".format(v)
            },
            modifier = Modifier.weight(1f),
        )

        CompactField(
            label = "Q",
            value = "%.2f".format(band.q),
            key = band.q,
            onCommit = { text ->
                val v = text.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: band.q
                onUpdate(band.copy(q = v))
                "%.2f".format(v)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Label above a bare text field.
 *
 * [onCommit] applies the typed text and returns the canonical string to show, so an unparseable
 * entry reverts. It runs on Done *and* on focus loss — previously, tapping away left typed text
 * visible but unsent, which reads as data loss.
 */
@Composable
private fun CompactField(
    label: String,
    value: String,
    key: Any,
    onCommit: (String) -> String,
    modifier: Modifier = Modifier,
    integerOnly: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(key) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = AppType.fieldLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = Sp.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = AppType.fieldValue.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    text = onCommit(text)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (focused && !it.isFocused) text = onCommit(text)
                        focused = it.isFocused
                    },
            )
        }
    }
}
