package com.hackson.spatialnav.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceAnnouncerTest {

    private fun fix(state: NavigationEngine.State, distance: Float) =
        NavigationEngine.Fix(state, distance, 0f, 0f)

    @Test
    fun `repeating the same instruction at the same distance stays silent`() {
        val announcer = GuidanceAnnouncer()
        assertEquals(
            "Bathroom is 4 meters ahead.",
            announcer.next(fix(NavigationEngine.State.GO_FORWARD, 4.0f), "Bathroom", 0L),
        )
        assertNull(announcer.next(fix(NavigationEngine.State.GO_FORWARD, 3.9f), "Bathroom", 500L))
    }

    @Test
    fun `crossing a whole metre speaks again`() {
        val announcer = GuidanceAnnouncer()
        announcer.next(fix(NavigationEngine.State.GO_FORWARD, 4.0f), "Bathroom", 0L)
        assertEquals(
            "Bathroom is 3 meters ahead.",
            announcer.next(fix(NavigationEngine.State.GO_FORWARD, 3.2f), "Bathroom", 4_000L),
        )
    }

    @Test
    fun `a changed instruction speaks immediately`() {
        val announcer = GuidanceAnnouncer()
        announcer.next(fix(NavigationEngine.State.GO_FORWARD, 4.0f), "Bathroom", 0L)
        assertEquals(
            "Turn left.",
            announcer.next(fix(NavigationEngine.State.TURN_LEFT, 4.0f), "Bathroom", 100L),
        )
    }

    @Test
    fun `arrival is announced exactly once`() {
        val announcer = GuidanceAnnouncer()
        assertEquals(
            "You have arrived at Bathroom.",
            announcer.next(fix(NavigationEngine.State.ARRIVED, 0.4f), "Bathroom", 0L),
        )
        assertNull(announcer.next(fix(NavigationEngine.State.ARRIVED, 0.4f), "Bathroom", 9_000L))
    }

    @Test
    fun `singular metre is not pluralised`() {
        val announcer = GuidanceAnnouncer()
        assertEquals(
            "Desk is 1 meter ahead.",
            announcer.next(fix(NavigationEngine.State.GO_FORWARD, 1.2f), "Desk", 0L),
        )
    }
}
