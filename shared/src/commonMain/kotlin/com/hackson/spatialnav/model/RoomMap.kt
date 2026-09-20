package com.hackson.spatialnav.model

/**
 * Persistent, navigable model of one indoor space.
 *
 * Pure data: no ARCore and no Android types, so it is unit-testable on the JVM and could be
 * moved to a backend later without touching the AR code. The room's persistent spatial
 * reference is *referenced* here ([spatialReference]) but is deliberately not modelled here:
 * relocalization is a runtime concern, this file is application semantics.
 *
 * All positions are expressed in the **room frame**: right-handed, metres, Y up,
 * +Z along the heading the room was created with, +X to its right. The frame is defined by
 * the spatial reference, so it is the same physical frame in every later session.
 */
data class RoomMap(
    val schemaVersion: Int = SCHEMA_VERSION,
    val roomId: String,
    val displayName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val spatialReference: SpatialReferenceRecord,
    val destinations: List<Destination> = emptyList(),
    /** Placeholders for P3 navigation; empty until the route graph is built. */
    val waypoints: List<Waypoint> = emptyList(),
    val edges: List<Edge> = emptyList(),
) {
    fun withDestination(destination: Destination, nowEpochMs: Long): RoomMap =
        copy(
            destinations = destinations.filterNot { it.name.equals(destination.name, true) } +
                destination,
            updatedAtEpochMs = nowEpochMs,
        )

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/**
 * How the room frame is recovered in a future session.
 *
 * Which strategy produced it is stored, not assumed, so a room hosted with Cloud Anchors and
 * a room aligned by hand can coexist and be reopened by the right mechanism.
 */
data class SpatialReferenceRecord(
    /** Matches `SpatialReferenceStrategy.id`, e.g. `cloud_anchor` or `manual_origin`. */
    val strategy: String,
    /** ARCore Cloud Anchor id, when [strategy] is `cloud_anchor`. */
    val cloudAnchorId: String? = null,
    val hostedAtEpochMs: Long = 0L,
    /** Cloud Anchor time-to-live; an API-key project is capped at 1 day. */
    val ttlDays: Int = 0,
    /**
     * Where the person must physically stand to re-establish the frame, in plain words.
     * Required by `manual_origin`, and useful for a Cloud Anchor too because the resolver
     * needs to see roughly the same scene.
     */
    val originDescription: String = "",
) {
    val isExpired: Boolean
        get() = ttlDays > 0 && hostedAtEpochMs > 0L &&
            nowEpochMs() - hostedAtEpochMs > ttlDays * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

/** A semantic target the user can ask for by name. */
data class Destination(
    val id: String,
    /** Spoken name, e.g. "the door". */
    val name: String,
    val position: Vec3,
    /** Heading in degrees the user should face on arrival, or null if it does not matter. */
    val approachHeadingDeg: Float? = null,
    /** The waypoint a P3 route terminates at before the final approach; null until P3. */
    val waypointId: String? = null,
    val createdAtEpochMs: Long = 0L,
)

/** A node of the navigable graph. Unused until P3. */
data class Waypoint(
    val id: String,
    val position: Vec3,
)

/** A directly walkable connection between two waypoints. Unused until P3. */
data class Edge(
    val fromId: String,
    val toId: String,
    /** Narrowest clearance along this edge, in metres; used to reject unsafe detours. */
    val widthMeters: Float = 1.0f,
)

data class Vec3(val x: Float, val y: Float, val z: Float)
