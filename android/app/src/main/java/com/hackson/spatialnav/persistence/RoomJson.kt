package com.hackson.spatialnav.persistence

import com.hackson.spatialnav.model.Destination
import com.hackson.spatialnav.model.Edge
import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.model.SpatialReferenceRecord
import com.hackson.spatialnav.model.Vec3
import com.hackson.spatialnav.model.Waypoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk representation of a [RoomMap].
 *
 * Written by hand against `org.json` rather than a serialization plugin: the schema is small,
 * it is the room's long-lived file format, and being explicit about every field makes the
 * migration path visible when P3 adds the navigation graph.
 */
object RoomJson {

    fun encode(room: RoomMap): String = JSONObject().apply {
        put("schemaVersion", room.schemaVersion)
        put("roomId", room.roomId)
        put("displayName", room.displayName)
        put("createdAtEpochMs", room.createdAtEpochMs)
        put("updatedAtEpochMs", room.updatedAtEpochMs)
        put("spatialReference", encodeReference(room.spatialReference))
        put("destinations", JSONArray(room.destinations.map(::encodeDestination)))
        put("waypoints", JSONArray(room.waypoints.map(::encodeWaypoint)))
        put("edges", JSONArray(room.edges.map(::encodeEdge)))
    }.toString(2)

    fun decode(text: String): RoomMap {
        val json = JSONObject(text)
        val version = json.optInt("schemaVersion", 1)
        require(version <= RoomMap.SCHEMA_VERSION) {
            "room was written by a newer app (schema $version)"
        }
        return RoomMap(
            schemaVersion = version,
            roomId = json.getString("roomId"),
            displayName = json.getString("displayName"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
            spatialReference = decodeReference(json.getJSONObject("spatialReference")),
            destinations = json.optJSONArray("destinations").mapObjects(::decodeDestination),
            waypoints = json.optJSONArray("waypoints").mapObjects(::decodeWaypoint),
            edges = json.optJSONArray("edges").mapObjects(::decodeEdge),
        )
    }

    private fun encodeReference(ref: SpatialReferenceRecord) = JSONObject().apply {
        put("strategy", ref.strategy)
        putOpt("cloudAnchorId", ref.cloudAnchorId)
        put("hostedAtEpochMs", ref.hostedAtEpochMs)
        put("ttlDays", ref.ttlDays)
        put("originDescription", ref.originDescription)
    }

    private fun decodeReference(json: JSONObject) = SpatialReferenceRecord(
        strategy = json.getString("strategy"),
        cloudAnchorId = json.optStringOrNull("cloudAnchorId"),
        hostedAtEpochMs = json.optLong("hostedAtEpochMs"),
        ttlDays = json.optInt("ttlDays"),
        originDescription = json.optString("originDescription"),
    )

    private fun encodeDestination(destination: Destination) = JSONObject().apply {
        put("id", destination.id)
        put("name", destination.name)
        put("position", encodeVec3(destination.position))
        putOpt("approachHeadingDeg", destination.approachHeadingDeg?.toDouble())
        putOpt("waypointId", destination.waypointId)
        put("createdAtEpochMs", destination.createdAtEpochMs)
    }

    private fun decodeDestination(json: JSONObject) = Destination(
        id = json.getString("id"),
        name = json.getString("name"),
        position = decodeVec3(json.getJSONObject("position")),
        approachHeadingDeg = if (json.has("approachHeadingDeg")) {
            json.getDouble("approachHeadingDeg").toFloat()
        } else {
            null
        },
        waypointId = json.optStringOrNull("waypointId"),
        createdAtEpochMs = json.optLong("createdAtEpochMs"),
    )

    private fun encodeWaypoint(waypoint: Waypoint) = JSONObject().apply {
        put("id", waypoint.id)
        put("position", encodeVec3(waypoint.position))
    }

    private fun decodeWaypoint(json: JSONObject) = Waypoint(
        id = json.getString("id"),
        position = decodeVec3(json.getJSONObject("position")),
    )

    private fun encodeEdge(edge: Edge) = JSONObject().apply {
        put("fromId", edge.fromId)
        put("toId", edge.toId)
        put("widthMeters", edge.widthMeters.toDouble())
    }

    private fun decodeEdge(json: JSONObject) = Edge(
        fromId = json.getString("fromId"),
        toId = json.getString("toId"),
        widthMeters = json.optDouble("widthMeters", 1.0).toFloat(),
    )

    private fun encodeVec3(v: Vec3) = JSONObject().apply {
        put("x", v.x.toDouble())
        put("y", v.y.toDouble())
        put("z", v.z.toDouble())
    }

    private fun decodeVec3(json: JSONObject) = Vec3(
        x = json.getDouble("x").toFloat(),
        y = json.getDouble("y").toFloat(),
        z = json.getDouble("z").toFloat(),
    )

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { transform(getJSONObject(it)) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
