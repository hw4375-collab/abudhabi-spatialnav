package com.hackson.spatialnav.navigation

import com.hackson.spatialnav.model.Vec3
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class NavigationEngineTest {

    private fun at(x: Float, z: Float, yawDeg: Float) =
        NavigationFrame.Local(x = x, y = 0f, z = z, yawDeg = yawDeg)

    @Test
    fun `target straight ahead goes forward`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 0f), Vec3(0f, 0f, 5f))
        assertEquals(NavigationEngine.State.GO_FORWARD, fix.state)
        assertEquals(5f, fix.distanceMeters, 1e-3f)
        assertEquals(0f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `target to the left turns left`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 0f), Vec3(-5f, 0f, 0f))
        assertEquals(NavigationEngine.State.TURN_LEFT, fix.state)
        assertEquals(-90f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `target to the right turns right`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 0f), Vec3(5f, 0f, 0f))
        assertEquals(NavigationEngine.State.TURN_RIGHT, fix.state)
        assertEquals(90f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `facing the target after turning goes forward`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 90f), Vec3(5f, 0f, 0f))
        assertEquals(NavigationEngine.State.GO_FORWARD, fix.state)
        assertEquals(0f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `inside the arrival radius arrives regardless of heading`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 170f), Vec3(0.4f, 0f, 0.4f))
        assertEquals(NavigationEngine.State.ARRIVED, fix.state)
    }

    @Test
    fun `height difference does not count towards the distance`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 0f), Vec3(0f, 1.5f, 0.5f))
        assertEquals(0.5f, fix.distanceMeters, 1e-3f)
        assertEquals(NavigationEngine.State.ARRIVED, fix.state)
    }

    @Test
    fun `heading error wraps the short way around 180 degrees`() {
        // Target behind and slightly right, user facing almost backwards: the short turn is
        // a few degrees to the left, not 350 degrees to the right.
        val fix = NavigationEngine.solve(at(0f, 0f, 175f), Vec3(1f, 0f, -5f))
        assertTrue(fix.headingErrorDeg < 0f, "expected a small left turn, got ${fix.headingErrorDeg}")
        assertTrue(fix.headingErrorDeg > -20f)
    }

    @Test
    fun `target directly behind needs a half turn`() {
        val fix = NavigationEngine.solve(at(0f, 0f, 0f), Vec3(0f, 0f, -5f))
        assertEquals(180f, kotlin.math.abs(fix.headingErrorDeg), 1e-3f)
        assertTrue(fix.state == NavigationEngine.State.TURN_LEFT ||
            fix.state == NavigationEngine.State.TURN_RIGHT)
    }
}
