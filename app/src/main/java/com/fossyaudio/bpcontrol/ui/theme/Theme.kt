package com.fossyaudio.bpcontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF6750A4)
private val OnPrimary = Color(0xFFFFFFFF)
private val Surface = Color(0xFFFDFBFF)
private val SurfaceVariant = Color(0xFFE7E0EB)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
)

@Composable
fun BpControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
