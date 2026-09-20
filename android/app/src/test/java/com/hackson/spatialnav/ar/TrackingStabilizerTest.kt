package com.hackson.spatialnav.ar

import com.hackson.spatialnav.ar.TrackingStabilizer.State
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingStabilizerTest {

    private val stabilizer = TrackingStabilizer(requiredStableMillis = 1_000L)

    @Test
    fun `without tracking it keeps searching`() {
        assertEquals(State.SEARCHING, stabilizer.update(tracking = false, nowMillis = 0L))
        assertEquals(State.SEARCHING, stabilizer.update(tracking = false, nowMillis = 5_000L))
    }

    @Test
    fun `tracking must be held for the whole window before the room may be anchored`() {
        assertEquals(State.STABILIZING, stabilizer.update(true, 1_000L))
        assertEquals(State.STABILIZING, stabilizer.update(true, 1_900L))
        assertEquals(State.READY, stabilizer.update(true, 2_000L))
    }

    @Test
    fun `losing tracking restarts the countdown from zero`() {
        stabilizer.update(true, 0L)
        stabilizer.update(true, 900L)
        assertEquals(State.SEARCHING, stabilizer.update(false, 950L))
        assertEquals(State.STABILIZING, stabilizer.update(true, 1_000L))
        assertEquals(State.STABILIZING, stabilizer.update(true, 1_900L))
        assertEquals(State.READY, stabilizer.update(true, 2_000L))
    }

    @Test
    fun `progress runs from zero to one across the window`() {
        stabilizer.update(true, 0L)
        assertEquals(0f, stabilizer.progress(), 1e-4f)
        stabilizer.update(true, 500L)
        assertEquals(0.5f, stabilizer.progress(), 1e-4f)
        stabilizer.update(true, 4_000L)
        assertEquals(1f, stabilizer.progress(), 1e-4f)
    }

    @Test
    fun `the message tells the user what to do in each state`() {
        stabilizer.update(false, 0L)
        assertEquals("Move phone slowly to scan surroundings...", stabilizer.message())
        stabilizer.update(true, 0L)
        stabilizer.update(true, 2_000L)
        assertEquals("Spatial tracking ready.", stabilizer.message())
    }
}
