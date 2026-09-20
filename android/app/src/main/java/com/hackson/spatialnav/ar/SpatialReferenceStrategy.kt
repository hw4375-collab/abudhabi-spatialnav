package com.hackson.spatialnav.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.hackson.spatialnav.model.SpatialReferenceRecord

/**
 * Establishes the one thing a saved room needs and a session cannot provide by itself: a
 * physical origin that means the same place tomorrow as it did today.
 *
 * ARCore world coordinates are session-local and arbitrary, so a room saved in session A is
 * meaningless in session B without a shared reference. Which mechanism provides that
 * reference is a deployment question — Cloud Anchors need a Google Cloud project and a
 * network, a physical marker needs neither — so it sits behind this interface and the
 * `RoomMap` records only *which* strategy was used.
 *
 * Every method is called on the main thread; results arrive on the main thread too.
 */
interface SpatialReferenceStrategy {

    /** Stable identifier written into [SpatialReferenceRecord.strategy]. */
    val id: String

    val displayName: String

    /** Why this strategy cannot be used right now, or null when it can. */
    fun unavailableReason(): String?

    /**
     * Creates a persistent reference at [pose] (session world coordinates).
     *
     * The returned record is what gets saved into the room; the anchor that defines the live
     * origin is exposed through [originAnchor].
     */
    fun create(
        session: Session,
        pose: Pose,
        originDescription: String,
        onResult: (Result<SpatialReferenceRecord>) -> Unit,
    )

    /**
     * Recovers the reference described by [record] in the current session.
     *
     * [currentCameraPose] is the most recent camera pose, which a marker-based strategy needs
     * and a cloud-based one ignores.
     */
    fun resolve(
        session: Session,
        record: SpatialReferenceRecord,
        currentCameraPose: Pose,
        onResult: (Result<Unit>) -> Unit,
    )

    /**
     * The room origin in the current session, or null while unresolved. Read every frame:
     * ARCore keeps correcting anchor poses as it learns the space.
     */
    val originAnchor: Anchor?

    /** Human-readable progress for the status overlay. */
    fun status(): String

    fun close()
}
