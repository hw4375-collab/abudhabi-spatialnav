package com.hackson.spatialnav.perception

/**
 * Turns three clearance measurements into one safety instruction.
 *
 * This is *reactive* avoidance, not planning: it answers "is the space straight ahead
 * blocked, and which way is more open right now?" and nothing else. The destination logic
 * in NavigationEngine is untouched — once the user has stepped around the obstacle, the
 * bearing to the destination takes over again by itself.
 *
 * Pure Kotlin so the decision rules, including the anti-oscillation behaviour, can be
 * tested without a phone.
 */
class DepthClearanceAnalyzer(private val config: Config = Config()) {

    /**
     * Distances in metres. Tune these after device QA; nothing else in the app hard-codes
     * an obstacle distance.
     */
    data class Config(
        /** Below this, the way ahead counts as blocked. */
        val blockedMeters: Float = 1.2f,
        /** How much further than [blockedMeters] it must clear before we say CLEAR again. */
        val clearHysteresisMeters: Float = 0.3f,
        /** A side must be at least this open to be worth stepping into. */
        val sideUsableMeters: Float = 1.2f,
        /** A new side must beat the current one by this much before we switch direction. */
        val sideSwitchMarginMeters: Float = 0.3f,
        /** How many consecutive unusable readings to ride out before admitting we are blind. */
        val missingReadingGrace: Int = 10,
    )

    /** Clearance per region; [Float.NaN] means "no usable depth samples there". */
    data class Clearance(val leftMeters: Float, val centerMeters: Float, val rightMeters: Float)

    enum class State {
        CLEAR,
        MOVE_LEFT,
        MOVE_RIGHT,
        STOP,

        /** Depth is not usable right now — the app must not pretend the way is clear. */
        UNKNOWN,
        ;

        val isObstacle: Boolean get() = this == MOVE_LEFT || this == MOVE_RIGHT || this == STOP
    }

    var state: State = State.UNKNOWN
        private set

    private var missingReadings = 0

    fun reset() {
        state = State.UNKNOWN
        missingReadings = 0
    }

    fun update(clearance: Clearance): State {
        val center = clearance.centerMeters
        if (center.isNaN()) {
            // Depth drops out for a frame or two all the time. Holding the last verdict
            // briefly avoids flicker; after that we say UNKNOWN rather than "clear".
            missingReadings++
            if (missingReadings > config.missingReadingGrace) state = State.UNKNOWN
            return state
        }
        missingReadings = 0

        val blocked = if (state.isObstacle) {
            center < config.blockedMeters + config.clearHysteresisMeters
        } else {
            center < config.blockedMeters
        }
        state = if (!blocked) State.CLEAR else chooseSide(clearance)
        return state
    }

    private fun chooseSide(clearance: Clearance): State {
        val left = clearance.leftMeters.takeUnless { it.isNaN() } ?: 0f
        val right = clearance.rightMeters.takeUnless { it.isNaN() } ?: 0f
        val leftUsable = left >= config.sideUsableMeters
        val rightUsable = right >= config.sideUsableMeters
        if (!leftUsable && !rightUsable) return State.STOP

        // Committing to a direction matters more than picking the optimal one: swapping
        // "move left" and "move right" every frame is worse than useless to a blind user.
        val margin = config.sideSwitchMarginMeters
        return when {
            state == State.MOVE_LEFT && leftUsable && left >= right - margin -> State.MOVE_LEFT
            state == State.MOVE_RIGHT && rightUsable && right >= left - margin -> State.MOVE_RIGHT
            !rightUsable -> State.MOVE_LEFT
            !leftUsable -> State.MOVE_RIGHT
            left >= right -> State.MOVE_LEFT
            else -> State.MOVE_RIGHT
        }
    }
}
