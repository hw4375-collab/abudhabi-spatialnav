package com.hackson.spatialnav.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceGuidancePolicyTest {

    private fun fix(state: NavigationState, distance: Float) =
        NavigationFix(state, distance, 0f)

    @Test
    fun `the same instruction is not repeated frame after frame`() {
        val policy = VoiceGuidancePolicy("Bathroom")
        assertEquals("Bathroom is 3 meters ahead.", policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.4f)))
        assertNull(policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.3f)))
        assertNull(policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.1f)))
    }

    @Test
    fun `a changed instruction is spoken`() {
        val policy = VoiceGuidancePolicy("Bathroom")
        policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.4f))
        assertEquals("Turn left.", policy.phraseFor(fix(NavigationState.TURN_LEFT, 3.4f)))
        assertNull(policy.phraseFor(fix(NavigationState.TURN_LEFT, 3.4f)))
        assertEquals("Turn right.", policy.phraseFor(fix(NavigationState.TURN_RIGHT, 3.4f)))
    }

    @Test
    fun `crossing a whole metre while walking is worth saying`() {
        val policy = VoiceGuidancePolicy("Desk")
        policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.4f))
        assertNull(policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.02f)))
        assertEquals("3 meters to Desk.", policy.phraseFor(fix(NavigationState.GO_FORWARD, 2.8f)))
        assertEquals("2 meters to Desk.", policy.phraseFor(fix(NavigationState.GO_FORWARD, 1.9f)))
    }

    @Test
    fun `drifting back away from the destination stays quiet`() {
        val policy = VoiceGuidancePolicy("Door")
        policy.phraseFor(fix(NavigationState.GO_FORWARD, 2.4f))
        assertNull(policy.phraseFor(fix(NavigationState.GO_FORWARD, 3.1f)))
    }

    @Test
    fun `arrival is announced exactly once`() {
        val policy = VoiceGuidancePolicy("Bathroom")
        policy.phraseFor(fix(NavigationState.GO_FORWARD, 2.4f))
        assertEquals(
            "You have arrived at Bathroom.",
            policy.phraseFor(fix(NavigationState.ARRIVED, 0.8f)),
        )
        assertNull(policy.phraseFor(fix(NavigationState.ARRIVED, 0.7f)))
    }
}
