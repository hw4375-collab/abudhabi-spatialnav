package com.hackson.spatialnav.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A human-intuitive local frame anchored at the pose where tracking first became reliable.
 *
 * ARCore's world frame is gravity-aligned but its horizontal axes point wherever the session
 * happened to start, so raw world coordinates are useless to read out loud. This frame
 * re-expresses a world pose as:
 *
 * * `x` — metres to the **right** of the origin heading
 * * `y` — metres **up** (shared with ARCore, gravity-aligned)
 * * `z` — metres **forward** along the origin heading
 * * `yaw` — degrees turned relative to the origin heading, positive when turning right
 *
 * Pure math on plain arrays: no ARCore or Android types, so it is unit-testable on the JVM.
 * Quaternions are ARCore-ordered `[x, y, z, w]`.
 */
class NavigationFrame(originTranslation: FloatArray, originQuaternion: FloatArray) {

    private val origin = originTranslation.copyOf()

    /** Horizontal unit vector the camera pointed at when the frame was established. */
    private val forward: FloatArray = horizontalHeading(originQuaternion)

    /** Horizontal unit vector 90 degrees to the right of [forward]. */
    private val right: FloatArray = crossWithUp(forward)

    private val originYawDeg: Float = headingDegrees(forward)

    data class Local(
        val x: Float,
        val y: Float,
        val z: Float,
        val yawDeg: Float,
    )

    fun localize(translation: FloatArray, quaternion: FloatArray): Local {
        val dx = translation[0] - origin[0]
        val dy = translation[1] - origin[1]
        val dz = translation[2] - origin[2]
        return Local(
            x = dx * right[0] + dz * right[2],
            y = dy,
            z = dx * forward[0] + dz * forward[2],
            yawDeg = normalizeDegrees(headingDegrees(horizontalHeading(quaternion)) - originYawDeg),
        )
    }

    companion object {

        /**
         * Rotates the vector [v] by the quaternion [q] (`[x, y, z, w]`).
         */
        fun rotate(q: FloatArray, v: FloatArray): FloatArray {
            val (qx, qy, qz) = Triple(q[0], q[1], q[2])
            val qw = q[3]
            // t = 2 * cross(q.xyz, v); result = v + qw * t + cross(q.xyz, t)
            val tx = 2f * (qy * v[2] - qz * v[1])
            val ty = 2f * (qz * v[0] - qx * v[2])
            val tz = 2f * (qx * v[1] - qy * v[0])
            return floatArrayOf(
                v[0] + qw * tx + (qy * tz - qz * ty),
                v[1] + qw * ty + (qz * tx - qx * tz),
                v[2] + qw * tz + (qx * ty - qy * tx),
            )
        }

        /**
         * The horizontal direction the camera is pointing at, as a unit vector in the world's
         * XZ plane.
         *
         * The camera looks down its own -Z axis. When the phone is held flat (pointing at the
         * floor or the ceiling) that axis is vertical and carries no heading, so the camera's
         * own "up" axis — which then lies horizontally — is used instead.
         */
        fun horizontalHeading(q: FloatArray): FloatArray {
            val look = rotate(q, floatArrayOf(0f, 0f, -1f))
            val flat = normalizeXZ(look)
            if (flat != null) return flat
            val up = rotate(q, floatArrayOf(0f, 1f, 0f))
            return normalizeXZ(up) ?: floatArrayOf(0f, 0f, -1f)
        }

        private fun normalizeXZ(v: FloatArray): FloatArray? {
            val length = sqrt(v[0] * v[0] + v[2] * v[2])
            if (length < 1e-4f) return null
            return floatArrayOf(v[0] / length, 0f, v[2] / length)
        }

        /** Rotates a horizontal unit vector 90 degrees clockwise seen from above. */
        private fun crossWithUp(f: FloatArray): FloatArray =
            floatArrayOf(-f[2], 0f, f[0])

        /**
         * Compass-like heading of a horizontal vector, in degrees, zero along -Z and growing
         * clockwise seen from above (i.e. turning right is positive).
         */
        fun headingDegrees(f: FloatArray): Float =
            Math.toDegrees(atan2(f[0].toDouble(), -f[2].toDouble())).toFloat()

        /** Wraps an angle into (-180, 180]. */
        fun normalizeDegrees(deg: Float): Float {
            var d = deg
            while (d <= -180f) d += 360f
            while (d > 180f) d -= 360f
            return if (abs(d) < 1e-4f) 0f else d
        }

        /** Pitch (nose up positive) and roll (right side down positive), for debugging only. */
        fun pitchRollDegrees(q: FloatArray): Pair<Float, Float> {
            val look = rotate(q, floatArrayOf(0f, 0f, -1f))
            val horizontal = sqrt(look[0] * look[0] + look[2] * look[2])
            val pitch = Math.toDegrees(atan2(look[1].toDouble(), horizontal.toDouble())).toFloat()
            val up = rotate(q, floatArrayOf(0f, 1f, 0f))
            val rightAxis = rotate(q, floatArrayOf(1f, 0f, 0f))
            val roll = Math.toDegrees(atan2(-rightAxis[1].toDouble(), up[1].toDouble())).toFloat()
            return pitch to roll
        }
    }
}
