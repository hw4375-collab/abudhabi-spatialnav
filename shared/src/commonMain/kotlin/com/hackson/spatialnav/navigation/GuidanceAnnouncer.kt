package com.hackson.spatialnav.navigation

import kotlin.math.roundToInt

/**
 * Decides *when* there is something worth saying, so the phone does not talk over itself.
 *
 * A blind user needs the next instruction, not a running commentary: speech happens when the
 * instruction actually changes, when another whole metre has been covered, and on arrival.
 * Everything else is silence.
 */
class GuidanceAnnouncer(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
) {

    private var lastState: NavigationEngine.State? = null
    private var lastSpokenMeters: Int? = null
    private var lastSpokenAtMillis = Long.MIN_VALUE
    private var arrivalAnnounced = false

    /** The phrase to speak now, or null to stay quiet. */
    fun next(
        fix: NavigationEngine.Fix,
        destinationName: String,
        nowMillis: Long,
    ): String? {
        if (fix.state == NavigationEngine.State.ARRIVED) {
            if (arrivalAnnounced) return null
            arrivalAnnounced = true
            lastState = fix.state
            lastSpokenAtMillis = nowMillis
            return "You have arrived at $destinationName."
        }
        arrivalAnnounced = false

        val meters = fix.distanceMeters.roundToInt()
        val stateChanged = fix.state != lastState
        val milestone = lastSpokenMeters?.let { meters < it } ?: true
        if (!stateChanged && !milestone) return null
        if (!stateChanged && nowMillis - lastSpokenAtMillis < minIntervalMillis) return null

        lastState = fix.state
        lastSpokenMeters = meters
        lastSpokenAtMillis = nowMillis
        return when (fix.state) {
            NavigationEngine.State.TURN_LEFT -> "Turn left."
            NavigationEngine.State.TURN_RIGHT -> "Turn right."
            NavigationEngine.State.GO_FORWARD ->
                "$destinationName is $meters ${if (meters == 1) "meter" else "meters"} ahead."

            NavigationEngine.State.ARRIVED -> null
        }
    }

    fun reset() {
        lastState = null
        lastSpokenMeters = null
        lastSpokenAtMillis = Long.MIN_VALUE
        arrivalAnnounced = false
    }

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 2_500L
    }
}
