package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.Preset
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetNamingTest {

    private fun preset(name: String) = Preset(name, emptyList())

    @Test
    fun name_is_unchanged_when_there_is_no_collision() {
        assertEquals("Harman IE 2019", uniqueName("Harman IE 2019", emptyList()))
    }

    @Test
    fun first_collision_gets_suffix_2() {
        val existing = listOf(preset("Harman IE 2019"))
        assertEquals("Harman IE 2019 (2)", uniqueName("Harman IE 2019", existing))
    }

    @Test
    fun repeated_collisions_count_up() {
        val existing = listOf(
            preset("Harman IE 2019"),
            preset("Harman IE 2019 (2)"),
            preset("Harman IE 2019 (3)"),
        )
        assertEquals("Harman IE 2019 (4)", uniqueName("Harman IE 2019", existing))
    }

    @Test
    fun a_gap_in_the_sequence_is_not_reused() {
        // (2) was deleted, but (3) still exists — the next name must not collide with (3).
        val existing = listOf(preset("Harman IE 2019"), preset("Harman IE 2019 (3)"))
        assertEquals("Harman IE 2019 (2)", uniqueName("Harman IE 2019", existing))
    }
}
