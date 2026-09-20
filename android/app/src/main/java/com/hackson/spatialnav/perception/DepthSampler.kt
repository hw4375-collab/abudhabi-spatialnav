package com.hackson.spatialnav.perception

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reduces an ARCore depth image to three numbers: how far away the nearest thing is to the
 * left, straight ahead, and to the right.
 *
 * No object recognition — for "can I walk forward?" the distance is the whole answer, and
 * a few thousand sampled pixels cost far less than any detector would.
 */
object DepthSampler {

    /** The horizontal band around the middle of the image that a walking person cares about. */
    private const val BAND_TOP = 0.35f
    private const val BAND_BOTTOM = 0.65f

    private val LEFT = 0.10f..0.33f
    private const val CENTER_HALF_WIDTH = 0.12f
    private val RIGHT = 0.67f..0.90f

    /** Every Nth pixel: the depth map is smooth, so full resolution buys nothing. */
    private const val STRIDE = 3

    /** ARCore packs depth in the low 13 bits of each 16-bit sample. */
    private const val DEPTH_MASK = 0x1FFF

    private const val MIN_VALID_MM = 150
    private const val MAX_VALID_MM = 8000
    private const val MIN_SAMPLES = 24

    /**
     * A low percentile rather than the median: the obstacle is what sticks out of the
     * scene, so the safe answer is "how close is the nearest sizeable thing", while still
     * ignoring the handful of noisy pixels a single-pixel minimum would trip over.
     */
    private const val PERCENTILE = 0.20f

    /**
     * The image is in sensor orientation, which on a phone held upright is rotated 90°, so
     * the user's left/right runs along the image's *rows* — hence [rotatedLandscape],
     * which the caller sets from the display rotation.
     */
    fun sample(image: Image, rotatedLandscape: Boolean = true): DepthClearanceAnalyzer.Clearance {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        fun region(from: Float, to: Float): Float = clearanceMeters(
            buffer, rowStride, pixelStride, width, height,
            if (rotatedLandscape) BAND_TOP..BAND_BOTTOM else from..to,
            if (rotatedLandscape) from..to else BAND_TOP..BAND_BOTTOM,
        )

        // With the phone upright the sensor's "up" is the user's left, so the left region
        // of the world sits at the *bottom* of the image rows.
        val first = region(LEFT.start, LEFT.endInclusive)
        val last = region(RIGHT.start, RIGHT.endInclusive)
        val center = region(0.5f - CENTER_HALF_WIDTH, 0.5f + CENTER_HALF_WIDTH)
        return if (rotatedLandscape) {
            DepthClearanceAnalyzer.Clearance(leftMeters = last, centerMeters = center, rightMeters = first)
        } else {
            DepthClearanceAnalyzer.Clearance(leftMeters = first, centerMeters = center, rightMeters = last)
        }
    }

    private fun clearanceMeters(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        columns: ClosedFloatingPointRange<Float>,
        rows: ClosedFloatingPointRange<Float>,
    ): Float {
        val x0 = (columns.start * width).toInt().coerceIn(0, width - 1)
        val x1 = (columns.endInclusive * width).toInt().coerceIn(0, width - 1)
        val y0 = (rows.start * height).toInt().coerceIn(0, height - 1)
        val y1 = (rows.endInclusive * height).toInt().coerceIn(0, height - 1)

        val samples = ArrayList<Int>((x1 - x0) * (y1 - y0) / (STRIDE * STRIDE) + 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val millimetres = buffer.getShort(y * rowStride + x * pixelStride).toInt() and DEPTH_MASK
                if (millimetres in MIN_VALID_MM..MAX_VALID_MM) samples.add(millimetres)
                x += STRIDE
            }
            y += STRIDE
        }
        if (samples.size < MIN_SAMPLES) return Float.NaN
        samples.sort()
        val index = ((samples.size - 1) * PERCENTILE).toInt()
        return samples[index] / 1000f
    }
}
