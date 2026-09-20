package com.hackson.spatialnav.navigation

import com.hackson.spatialnav.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuideTest {

    private fun at(x: Float, z: Float, yawDeg: Float) =
        NavigationFrame.Local(x = x, y = 0f, z = z, yawDeg = yawDeg)

    @Test
    fun `a target straight ahead is walked towards`() {
        val fix = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(0f, 0f, 5f))
        assertEquals(NavigationState.GO_FORWARD, fix.state)
        assertEquals(5f, fix.distanceMeters, 1e-3f)
        assertEquals(0f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `a target to the left asks for a left turn`() {
        val fix = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(-5f, 0f, 0f))
        assertEquals(NavigationState.TURN_LEFT, fix.state)
        assertEquals(-90f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `a target to the right asks for a right turn`() {
        val fix = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(5f, 0f, 0f))
        assertEquals(NavigationState.TURN_RIGHT, fix.state)
        assertEquals(90f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `inside the arrival radius the person has arrived`() {
        val fix = NavigationGuide.guide(at(0f, 0f, 180f), Vec3(0.5f, 0f, 0.5f))
        assertEquals(NavigationState.ARRIVED, fix.state)
    }

    @Test
    fun `arrival ignores height so a destination marked at phone height still counts`() {
        val fix = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(0f, 1.4f, 0.3f))
        assertEquals(NavigationState.ARRIVED, fix.state)
        assertEquals(0.3f, fix.distanceMeters, 1e-3f)
    }

    @Test
    fun `heading error wraps around the back of the user instead of blowing up`() {
        // Facing just left of straight back, with the target straight back: the short way
        // round is a few degrees to the right, not 350 degrees to the left.
        val fix = NavigationGuide.guide(at(0f, 0f, 175f), Vec3(0f, 0f, -5f))
        assertEquals(NavigationState.GO_FORWARD, fix.state)
        assertEquals(5f, fix.headingErrorDeg, 1e-3f)
    }

    @Test
    fun `a target just outside the forward cone is a turn, just inside it is forward`() {
        val justOutside = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(2f, 0f, 4f))
        assertTrue(justOutside.headingErrorDeg > NavigationGuide.FORWARD_CONE_DEG)
        assertEquals(NavigationState.TURN_RIGHT, justOutside.state)

        val justInside = NavigationGuide.guide(at(0f, 0f, 0f), Vec3(1f, 0f, 4f))
        assertTrue(justInside.headingErrorDeg < NavigationGuide.FORWARD_CONE_DEG)
        assertEquals(NavigationState.GO_FORWARD, justInside.state)
    }
}
