package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType

@Composable
fun EqBandRow(
    band: FilterBand,
    onUpdate: (FilterBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    var typeExpanded by remember { mutableStateOf(false) }
    var freqText by remember(band.freq) { mutableStateOf(band.freq.toString()) }
    var gainText by remember(band.gain) { mutableStateOf("%.2f".format(band.gain)) }
    var qText by remember(band.q) { mutableStateOf("%.2f".format(band.q)) }

    val fieldTextStyle = TextStyle(fontSize = 12.sp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Enable checkbox
        Checkbox(
            checked = band.enabled,
            onCheckedChange = { checked ->
                onUpdate(band.copy(enabled = checked))
            },
        )

        // Filter type dropdown
        Box(modifier = Modifier.weight(1.4f)) {
            OutlinedTextField(
                value = band.type.name,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = fieldTextStyle,
                label = { Text("Type", fontSize = 10.sp) },
                trailingIcon = { Text(if (typeExpanded) "▲" else "▼") },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { typeExpanded = true })
                    },
            )
            DropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                FilterType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.name, fontSize = 12.sp) },
                        onClick = {
                            typeExpanded = false
                            onUpdate(band.copy(type = type))
                        },
                    )
                }
            }
        }

        // Freq field
        OutlinedTextField(
            value = freqText,
            onValueChange = { freqText = it },
            textStyle = fieldTextStyle,
            label = { Text("Freq", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                val v = freqText.toIntOrNull()?.coerceIn(20, 20000) ?: band.freq
                freqText = v.toString()
                onUpdate(band.copy(freq = v))
            }),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        // Gain field — clamped to ±12 dB (matches Walkplay PEQ range; Q8.8 encoding supports it)
        OutlinedTextField(
            value = gainText,
            onValueChange = { gainText = it },
            textStyle = fieldTextStyle,
            label = { Text("Gain", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                val v = gainText.toFloatOrNull()?.coerceIn(-12f, 12f) ?: band.gain
                gainText = "%.2f".format(v)
                onUpdate(band.copy(gain = v))
            }),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        // Q field
        OutlinedTextField(
            value = qText,
            onValueChange = { qText = it },
            textStyle = fieldTextStyle,
            label = { Text("Q", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                val v = qText.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: band.q
                qText = "%.2f".format(v)
                onUpdate(band.copy(q = v))
            }),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}
