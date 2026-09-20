package com.hackson.spatialnav.perception

import com.hackson.spatialnav.perception.DepthClearanceAnalyzer.Clearance
import com.hackson.spatialnav.perception.DepthClearanceAnalyzer.State
import kotlin.test.assertEquals
import kotlin.test.Test

class DepthClearanceAnalyzerTest {

    private val analyzer = DepthClearanceAnalyzer()

    @Test
    fun `open space ahead is clear`() {
        assertEquals(State.CLEAR, analyzer.update(Clearance(3f, 3f, 3f)))
    }

    @Test
    fun `blocked ahead with the left open moves left`() {
        assertEquals(State.MOVE_LEFT, analyzer.update(Clearance(2.5f, 0.8f, 0.9f)))
    }

    @Test
    fun `blocked ahead with the right open moves right`() {
        assertEquals(State.MOVE_RIGHT, analyzer.update(Clearance(0.9f, 0.8f, 2.5f)))
    }

    @Test
    fun `blocked on every side stops`() {
        assertEquals(State.STOP, analyzer.update(Clearance(0.7f, 0.6f, 0.8f)))
    }

    @Test
    fun `a chosen side is kept while the sides stay comparable`() {
        assertEquals(State.MOVE_RIGHT, analyzer.update(Clearance(1.3f, 0.7f, 2.0f)))
        // Left is now nominally wider, but not by enough to justify sending the user back.
        assertEquals(State.MOVE_RIGHT, analyzer.update(Clearance(2.0f, 0.7f, 1.9f)))
        // A decisive difference does switch.
        assertEquals(State.MOVE_LEFT, analyzer.update(Clearance(3.0f, 0.7f, 1.3f)))
    }

    @Test
    fun `clearing requires more room than blocking did`() {
        analyzer.update(Clearance(2.5f, 0.8f, 0.9f))
        // Just past the blocking threshold: still treated as an obstacle, so the
        // instruction does not flicker while the user edges around it.
        assertEquals(State.MOVE_LEFT, analyzer.update(Clearance(2.5f, 1.3f, 0.9f)))
        assertEquals(State.CLEAR, analyzer.update(Clearance(2.5f, 1.8f, 0.9f)))
    }

    @Test
    fun `missing depth holds the last verdict briefly then admits it is unknown`() {
        analyzer.update(Clearance(0.9f, 0.8f, 2.5f))
        repeat(5) { assertEquals(State.MOVE_RIGHT, analyzer.update(Clearance(Float.NaN, Float.NaN, Float.NaN))) }
        repeat(10) { analyzer.update(Clearance(Float.NaN, Float.NaN, Float.NaN)) }
        assertEquals(State.UNKNOWN, analyzer.state)
    }

    @Test
    fun `an unreadable side counts as unusable rather than open`() {
        assertEquals(
            State.MOVE_RIGHT,
            analyzer.update(Clearance(Float.NaN, 0.7f, 2.4f)),
        )
    }

    @Test
    fun `depth returning stops the unknown state`() {
        repeat(20) { analyzer.update(Clearance(Float.NaN, Float.NaN, Float.NaN)) }
        assertEquals(State.CLEAR, analyzer.update(Clearance(3f, 3f, 3f)))
    }
}
