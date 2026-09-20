package com.hackson.spatialnav.ar

/**
 * Initialization policy for "is this session good enough to define a room frame?".
 *
 * ARCore reports `TRACKING` the moment it has any solution at all, which on the first frame
 * is a pose built from almost no parallax. Anchoring a room to that pose bakes the early
 * error into every later measurement, so the app waits for tracking to hold continuously for
 * [requiredStableMillis] first.
 *
 * This is an initialization policy, not drift correction: a session that stabilizes cleanly
 * can still drift afterwards, which is what the persistent spatial reference is for.
 */
class TrackingStabilizer(
    private val requiredStableMillis: Long = DEFAULT_REQUIRED_STABLE_MILLIS,
) {

    enum class State {
        /** No usable tracking yet. */
        SEARCHING,

        /** Tracking, but not yet long enough to trust. */
        STABILIZING,

        /** Tracking has held continuously; a room frame may be created. */
        READY,
    }

    var state: State = State.SEARCHING
        private set

    private var stableSinceMillis: Long? = null
    private var progress = 0f

    /**
     * Feeds one frame. [tracking] is `camera.trackingState == TRACKING`.
     * Any interruption drops straight back to [State.SEARCHING]; a session that lost tracking
     * may have re-localized somewhere else entirely, so the countdown restarts.
     */
    fun update(tracking: Boolean, nowMillis: Long): State {
        if (!tracking) {
            stableSinceMillis = null
            progress = 0f
            state = State.SEARCHING
            return state
        }
        val since = stableSinceMillis ?: nowMillis.also { stableSinceMillis = it }
        val held = nowMillis - since
        progress = (held.toFloat() / requiredStableMillis).coerceIn(0f, 1f)
        state = if (held >= requiredStableMillis) State.READY else State.STABILIZING
        return state
    }

    /** 0..1, how far through the stabilization window the session is. */
    fun progress(): Float = progress

    /** Text for the status line; the phrasing doubles as the instruction to the user. */
    fun message(): String = when (state) {
        State.SEARCHING -> "Move phone slowly to scan surroundings..."
        State.STABILIZING -> "Stabilizing tracking... ${(progress * 100).toInt()}%"
        State.READY -> "Spatial tracking ready."
    }

    private companion object {
        const val DEFAULT_REQUIRED_STABLE_MILLIS = 3_000L
    }
}
