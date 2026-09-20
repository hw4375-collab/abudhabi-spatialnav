package com.hackson.spatialnav.navigation

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Decides *when* to talk, which is the difference between voice guidance and audio spam.
 *
 * A phrase is produced only when the instruction changes, when a whole-metre milestone is
 * crossed while walking forward, or on arrival. Everything else is silence. Pure Kotlin, so
 * the throttling rules are unit-testable without a TextToSpeech engine.
 */
class VoiceGuidancePolicy(private val destinationName: String) {

    private var lastState: NavigationState? = null
    private var lastSpokenMilestone: Int = Int.MAX_VALUE
    private var arrivalAnnounced = false

    /** The sentence to speak for this fix, or null to stay quiet. */
    fun phraseFor(fix: NavigationFix): String? {
        if (fix.state == NavigationState.ARRIVED) {
            if (arrivalAnnounced) return null
            arrivalAnnounced = true
            lastState = fix.state
            return "You have arrived at $destinationName."
        }
        arrivalAnnounced = false
        val milestone = floor(fix.distanceMeters).toInt()
        if (fix.state != lastState) {
            lastState = fix.state
            lastSpokenMilestone = milestone
            return when (fix.state) {
                NavigationState.TURN_LEFT -> "Turn left."
                NavigationState.TURN_RIGHT -> "Turn right."
                NavigationState.GO_FORWARD ->
                    "$destinationName is ${distancePhrase(fix.distanceMeters)} ahead."
                NavigationState.ARRIVED -> null
            }
        }
        if (fix.state == NavigationState.GO_FORWARD && milestone < lastSpokenMilestone) {
            lastSpokenMilestone = milestone
            return "${distancePhrase(fix.distanceMeters)} to $destinationName."
        }
        return null
    }

    private fun distancePhrase(distanceMeters: Float): String {
        val metres = distanceMeters.roundToInt().coerceAtLeast(1)
        return if (metres == 1) "1 meter" else "$metres meters"
    }
}
