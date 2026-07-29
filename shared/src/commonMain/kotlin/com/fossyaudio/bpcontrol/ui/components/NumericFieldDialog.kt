package com.fossyaudio.bpcontrol.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/**
 * Numeric entry for a single field, clamped to [range].
 *
 * Used by the band inspector's value cards so frequency and Q can be typed without opening the
 * filter list.
 */
@Composable
fun NumericFieldDialog(
    title: String,
    initialValue: Float,
    range: ClosedFloatingPointRange<Float>,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
    integerOnly: Boolean = false,
    suffix: String = "",
) {
    val format: (Float) -> String = { if (integerOnly) it.toInt().toString() else "%.2f".format(it) }
    var text by remember { mutableStateOf(format(initialValue)) }
    val parsed = text.toFloatOrNull()
    val isValid = parsed != null && parsed in range

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotEmpty() && !isValid,
                label = { Text(rangeLabel(range, integerOnly, suffix)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { parsed?.let { onConfirm(it) } },
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun rangeLabel(
    range: ClosedFloatingPointRange<Float>,
    integerOnly: Boolean,
    suffix: String,
): String {
    val low = if (integerOnly) range.start.toInt().toString() else "%.1f".format(range.start)
    val high =
        if (integerOnly) range.endInclusive.toInt().toString() else "%.1f".format(range.endInclusive)
    return if (suffix.isEmpty()) "$low to $high" else "$low to $high $suffix"
}
