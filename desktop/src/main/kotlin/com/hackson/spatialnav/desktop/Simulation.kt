package com.hackson.spatialnav.desktop

import com.hackson.spatialnav.model.Destination
import com.hackson.spatialnav.model.Vec3
import com.hackson.spatialnav.navigation.GuidanceAnnouncer
import com.hackson.spatialnav.navigation.NavigationEngine
import com.hackson.spatialnav.navigation.NavigationFrame
import com.hackson.spatialnav.perception.DepthClearanceAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The desktop stand-in for a phone: it produces a pose and three depth clearances, and
 * nothing else. Every decision made from them — direction, distance, arrival, whether the
 * way is blocked, which side to take, what to say — comes from the same shared core the
 * Android app runs against, so this window is evidence that the kernel is portable rather
 * than a second implementation of it.
 */
class Simulation {

    data class World(
        val user: NavigationFrame.Local,
        val destination: Destination,
        val obstacle: Vec3?,
        val fix: NavigationEngine.Fix,
        val clearance: DepthClearanceAnalyzer.Clearance,
        val obstacleState: DepthClearanceAnalyzer.State,
        val spoken: String?,
    )

    private val analyzer = DepthClearanceAnalyzer()
    private val announcer = GuidanceAnnouncer(minIntervalMillis = 0L)

    private var x = 0f
    private var z = 0f
    private var yaw = 0f
    private var obstacle: Vec3? = null
    private var spoken: String? = null
    private var tick = 0L

    private val destination = Destination(
        id = "bathroom",
        name = "Bathroom",
        position = Vec3(0f, 0f, 6f),
    )

    fun moveForward(meters: Float = 0.5f) = update {
        x += sin(yaw.toRadians()) * meters
        z += cos(yaw.toRadians()) * meters
    }

    fun turn(degrees: Float) = update { yaw = NavigationFrame.normalizeDegrees(yaw + degrees) }

    /**
     * Drops something in the way a metre ahead and slightly off-centre, which is the
     * situation the analyzer exists for — the simulator never says which way to go.
     */
    fun placeObstacle() = update {
        obstacle = Vec3(
            x = x + sin(yaw.toRadians()) * 1.0f - cos(yaw.toRadians()) * 0.25f,
            y = 0f,
            z = z + cos(yaw.toRadians()) * 1.0f + sin(yaw.toRadians()) * 0.25f,
        )
    }

    fun removeObstacle() = update { obstacle = null }

    fun reset() = update {
        x = 0f
        z = 0f
        yaw = 0f
        obstacle = null
        analyzer.reset()
        announcer.reset()
    }

    fun world(): World = update { }

    private fun update(change: () -> Unit): World {
        change()
        val user = NavigationFrame.Local(x = x, y = 0f, z = z, yawDeg = yaw)
        val fix = NavigationEngine.solve(user, destination.position)
        val clearance = senseDepth()
        val obstacleState = analyzer.update(clearance)
        val guidance = announcer.next(fix, destination.name, tick++)
        spoken = when (obstacleState) {
            DepthClearanceAnalyzer.State.MOVE_LEFT -> "Obstacle ahead. Move left."
            DepthClearanceAnalyzer.State.MOVE_RIGHT -> "Obstacle ahead. Move right."
            DepthClearanceAnalyzer.State.STOP -> "Stop. Obstacle ahead."
            else -> guidance ?: spoken
        }
        return World(user, destination, obstacle, fix, clearance, obstacleState, spoken)
    }

    /**
     * Stands in for the depth camera: three rays fanned out ahead of the user, measured
     * against the obstacle. Same units and same meaning as the values `DepthSampler` reads
     * out of an ARCore depth image on the phone.
     */
    private fun senseDepth(): DepthClearanceAnalyzer.Clearance {
        val here = obstacle ?: return DepthClearanceAnalyzer.Clearance(FAR, FAR, FAR)
        return DepthClearanceAnalyzer.Clearance(
            leftMeters = rayDistance(yaw - RAY_FAN_DEG, here),
            centerMeters = rayDistance(yaw, here),
            rightMeters = rayDistance(yaw + RAY_FAN_DEG, here),
        )
    }

    /** Distance along a ray to a disc of [OBSTACLE_RADIUS], or [FAR] if it misses. */
    private fun rayDistance(headingDeg: Float, target: Vec3): Float {
        val dx = sin(headingDeg.toRadians())
        val dz = cos(headingDeg.toRadians())
        val ox = target.x - x
        val oz = target.z - z
        val along = ox * dx + oz * dz
        if (along <= 0f) return FAR
        val perpendicular = sqrt((ox * ox + oz * oz - along * along).coerceAtLeast(0f))
        if (perpendicular > OBSTACLE_RADIUS) return FAR
        val half = sqrt(OBSTACLE_RADIUS * OBSTACLE_RADIUS - perpendicular * perpendicular)
        return (along - half).coerceAtLeast(0.1f).coerceAtMost(FAR)
    }

    private fun Float.toRadians(): Float = (this * PI / 180.0).toFloat()

    private companion object {
        /** Beyond the depth camera's useful range: as good as open space. */
        const val FAR = 5f
        const val OBSTACLE_RADIUS = 0.45f
        const val RAY_FAN_DEG = 22f
    }
}
