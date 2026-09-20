package com.hackson.spatialnav.map

/**
 * Persistent, navigable model of one indoor space.
 *
 * Pure data: no ARCore and no Android types, so it is unit-testable on the JVM and
 * reusable by any future backend. Cloud Anchors are referenced here but are only a
 * coordinate bridge; all semantics (destinations, walkability, connectivity) live here.
 *
 * All poses are expressed in the room frame: right-handed, Y up, metres.
 */
data class RoomMap(
    val schemaVersion: Int = 1,
    val roomId: String,
    val displayName: String,
    val anchors: List<AnchorRef>,
    val destinations: List<Destination>,
    val waypoints: List<Waypoint>,
    val edges: List<Edge>,
    val occupancy: OccupancyGrid? = null,
)

/** A hosted Cloud Anchor and its known pose in the room frame. */
data class AnchorRef(
    val cloudAnchorId: String,
    val pose: Pose,
    val note: String = "",
)

/** A semantic target the user can ask for by name. */
data class Destination(
    val id: String,
    /** Spoken name, e.g. "the door". */
    val name: String,
    val position: Vec3,
    /** Heading in degrees the user should face on arrival, or null if it does not matter. */
    val approachHeadingDeg: Float? = null,
    /** The waypoint the route terminates at before the final approach. */
    val waypointId: String,
)

/** A node of the navigable graph. */
data class Waypoint(
    val id: String,
    val position: Vec3,
)

/** A directly walkable connection between two waypoints. */
data class Edge(
    val fromId: String,
    val toId: String,
    /** Narrowest clearance along this edge, in metres; used to reject unsafe detours. */
    val widthMeters: Float = 1.0f,
)

/**
 * Coarse top-down static occupancy of the room: walls and permanent furniture.
 * Used for clearance checks and for snapping an off-graph pose back onto the graph.
 * Row-major, [cells.size] == [rows] * [cols]; true means blocked.
 */
data class OccupancyGrid(
    val originXZ: Vec2,
    val cellSizeMeters: Float,
    val rows: Int,
    val cols: Int,
    val cells: BooleanArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OccupancyGrid &&
            originXZ == other.originXZ &&
            cellSizeMeters == other.cellSizeMeters &&
            rows == other.rows &&
            cols == other.cols &&
            cells.contentEquals(other.cells))

    override fun hashCode(): Int {
        var result = originXZ.hashCode()
        result = 31 * result + cellSizeMeters.hashCode()
        result = 31 * result + rows
        result = 31 * result + cols
        result = 31 * result + cells.contentHashCode()
        return result
    }
}

data class Vec2(val x: Float, val z: Float)

data class Vec3(val x: Float, val y: Float, val z: Float)

/** Position plus orientation as a quaternion, in the room frame. */
data class Pose(
    val position: Vec3,
    val rotation: Quaternion,
)

data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float)
