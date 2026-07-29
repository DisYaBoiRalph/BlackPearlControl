package com.fossyaudio.bpcontrol.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spacing scale. These five values cover the whole app. */
object Sp {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

/**
 * The two type sizes that sit below the Material scale's 12 sp floor.
 *
 * They are not slots on [androidx.compose.material3.Typography], which has a fixed set of names,
 * so they live here. 11 sp is the floor for the app, and only for field labels.
 */
object AppType {
    val fieldValue = TextStyle(fontSize = 13.sp)
    val fieldLabel = TextStyle(fontSize = 11.sp)
}
