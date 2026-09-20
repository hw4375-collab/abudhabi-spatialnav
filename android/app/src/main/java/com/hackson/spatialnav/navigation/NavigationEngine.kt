package com.hackson.spatialnav.navigation

import com.hackson.spatialnav.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns "where I am" plus "where I want to be" into one of four instructions.
 *
 * Distance is measured in the horizontal plane only: a destination marked at chest height is
 * still reached by walking to the spot underneath it, and counting the vertical offset would
 * keep announcing a residual distance the user cannot walk off.
 *
 * Pure math, no Android or ARCore types, so the guidance rules are unit-testable.
 */
object NavigationEngine {

    /** Close enough that a person standing here would call it arrived. */
    const val ARRIVAL_RADIUS_METERS = 1.0f

    /** Heading error tolerated before the instruction becomes a turn. */
    const val FORWARD_CONE_DEG = 20f

    enum class State {
        GO_FORWARD,
        TURN_LEFT,
        TURN_RIGHT,
        ARRIVED,
    }

    /**
     * @param distanceMeters horizontal distance to the destination
     * @param bearingDeg direction of the destination in room coordinates
     * @param headingErrorDeg how far the user must turn, positive to the right
     */
    data class Fix(
        val state: State,
        val distanceMeters: Float,
        val bearingDeg: Float,
        val headingErrorDeg: Float,
    )

    fun solve(current: NavigationFrame.Local, target: Vec3): Fix {
        val dx = target.x - current.x
        val dz = target.z - current.z
        val distance = hypot(dx, dz)
        // Same convention as the local frame's yaw: zero along +Z, positive turning right.
        val bearing = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        val error = NavigationFrame.normalizeDegrees(bearing - current.yawDeg)
        val state = when {
            distance <= ARRIVAL_RADIUS_METERS -> State.ARRIVED
            abs(error) <= FORWARD_CONE_DEG -> State.GO_FORWARD
            error > 0f -> State.TURN_RIGHT
            else -> State.TURN_LEFT
        }
        return Fix(state, distance, bearing, error)
    }
}
