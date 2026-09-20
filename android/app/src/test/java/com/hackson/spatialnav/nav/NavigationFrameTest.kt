package com.hackson.spatialnav.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pose readout is the only P1 output the tester can judge, so the frame conversion is
 * pinned down here rather than on the phone.
 */
class NavigationFrameTest {

    /** Rotation of [degrees] about the world's up axis, as an ARCore-ordered quaternion. */
    private fun yawQuaternion(degrees: Float): FloatArray {
        val half = (degrees * PI / 180.0 / 2.0)
        return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    private val identity = yawQuaternion(0f)

    @Test
    fun `standing at the origin reads zero`() {
        val frame = NavigationFrame(floatArrayOf(1f, 2f, 3f), identity)
        val local = frame.localize(floatArrayOf(1f, 2f, 3f), identity)
        assertEquals(0f, local.x, TOLERANCE)
        assertEquals(0f, local.y, TOLERANCE)
        assertEquals(0f, local.z, TOLERANCE)
        assertEquals(0f, local.yawDeg, TOLERANCE)
    }

    @Test
    fun `walking two metres along the start heading is positive Z`() {
        val frame = NavigationFrame(floatArrayOf(0f, 0f, 0f), identity)
        // The camera starts looking down world -Z, so two metres forward is world z = -2.
        val local = frame.localize(floatArrayOf(0f, 0f, -2f), identity)
        assertEquals(2f, local.z, TOLERANCE)
        assertEquals(0f, local.x, TOLERANCE)
    }

    @Test
    fun `the local frame follows the start heading, not the world axes`() {
        // Session started facing world +X: stepping to world +X must read as forward.
        val start = yawQuaternion(-90f)
        val frame = NavigationFrame(floatArrayOf(0f, 0f, 0f), start)
        val forward = frame.localize(floatArrayOf(2f, 0f, 0f), start)
        assertEquals(2f, forward.z, TOLERANCE)
        assertEquals(0f, forward.x, TOLERANCE)
        assertEquals(0f, forward.yawDeg, TOLERANCE)
    }

    @Test
    fun `sidestepping right is positive X`() {
        val frame = NavigationFrame(floatArrayOf(0f, 0f, 0f), identity)
        val local = frame.localize(floatArrayOf(1.5f, 0f, 0f), identity)
        assertEquals(1.5f, local.x, TOLERANCE)
        assertEquals(0f, local.z, TOLERANCE)
    }

    @Test
    fun `turning right is positive yaw and wraps at 180`() {
        val frame = NavigationFrame(floatArrayOf(0f, 0f, 0f), identity)
        assertEquals(90f, frame.localize(ORIGIN, yawQuaternion(-90f)).yawDeg, TOLERANCE)
        assertEquals(-90f, frame.localize(ORIGIN, yawQuaternion(90f)).yawDeg, TOLERANCE)
        assertEquals(180f, frame.localize(ORIGIN, yawQuaternion(180f)).yawDeg, TOLERANCE)
    }

    @Test
    fun `raising the phone is positive Y`() {
        val frame = NavigationFrame(floatArrayOf(0f, 1f, 0f), identity)
        assertEquals(0.4f, frame.localize(floatArrayOf(0f, 1.4f, 0f), identity).y, TOLERANCE)
    }

    @Test
    fun `a phone pointed at the floor still has a heading`() {
        // Pitched 90 degrees down: the look axis is vertical and carries no heading.
        val pitchedDown = floatArrayOf(-sin(PI / 4).toFloat(), 0f, 0f, cos(PI / 4).toFloat())
        val heading = NavigationFrame.horizontalHeading(pitchedDown)
        assertEquals(0f, heading[0], TOLERANCE)
        assertEquals(0f, heading[1], TOLERANCE)
        assertEquals(-1f, heading[2], TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-3f
        val ORIGIN = floatArrayOf(0f, 0f, 0f)
    }
}
