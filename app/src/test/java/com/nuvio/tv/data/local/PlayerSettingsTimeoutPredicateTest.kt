package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsTimeoutPredicateTest {

    @Test
    fun `value list last entry equals unlimited sentinel`() {
        assertEquals(
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED,
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.last()
        )
    }

    @Test
    fun `value list is sorted ascending`() {
        val list = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES
        assertEquals(list, list.sorted())
    }

    @Test
    fun `value list contains zero (instant)`() {
        assertTrue(0 in PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES)
    }

    @Test
    fun `unlimited sentinel does not collide with a real value`() {
        val withoutSentinel = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.dropLast(1)
        assertFalse(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED in withoutSentinel)
    }

    @Test
    fun `value list contains expected ten-to-thirty entries`() {
        val list = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES
        assertTrue(10 in list)
        assertTrue(15 in list)
        assertTrue(20 in list)
        assertTrue(25 in list)
        assertTrue(30 in list)
    }
}
