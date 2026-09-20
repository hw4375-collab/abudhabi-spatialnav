package com.hackson.spatialnav.navigation

import com.hackson.spatialnav.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** What the person should do right now. */
enum class NavigationState {
    GO_FORWARD,
    TURN_LEFT,
    TURN_RIGHT,
    ARRIVED,
}

/**
 * One instant of guidance towards a destination.
 *
 * Distance ignores height: a destination marked at phone height is not "1.4 m away" when the
 * person is standing on top of it.
 */
data class NavigationFix(
    val state: NavigationState,
    val distanceMeters: Float,
    /** Degrees the person must turn; positive to the right. */
    val headingErrorDeg: Float,
)

/**
 * Deterministic guidance from the live pose to a saved destination, in the room frame.
 *
 * Pure math on plain data — no ARCore and no Android types — so the whole decision table is
 * unit-testable on the JVM.
 */
object NavigationGuide {

    /** Close enough that further guidance would be noise rather than help. */
    const val ARRIVAL_RADIUS_M = 1.0f

    /** Half-angle of the cone in which walking straight makes progress. */
    const val FORWARD_CONE_DEG = 20f

    fun guide(current: NavigationFrame.Local, target: Vec3): NavigationFix {
        val dx = target.x - current.x
        val dz = target.z - current.z
        val distance = sqrt(dx * dx + dz * dz)
        val bearing = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        val error = NavigationFrame.normalizeDegrees(bearing - current.yawDeg)
        val state = when {
            distance <= ARRIVAL_RADIUS_M -> NavigationState.ARRIVED
            abs(error) <= FORWARD_CONE_DEG -> NavigationState.GO_FORWARD
            error > 0f -> NavigationState.TURN_RIGHT
            else -> NavigationState.TURN_LEFT
        }
        return NavigationFix(state, distance, error)
    }
}
