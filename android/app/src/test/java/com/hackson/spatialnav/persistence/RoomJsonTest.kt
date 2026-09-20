package com.hackson.spatialnav.persistence

import com.hackson.spatialnav.model.Destination
import com.hackson.spatialnav.model.Edge
import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.model.SpatialReferenceRecord
import com.hackson.spatialnav.model.Vec3
import com.hackson.spatialnav.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomJsonTest {

    private val room = RoomMap(
        roomId = "abc123",
        displayName = "Test Room",
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_100_000L,
        spatialReference = SpatialReferenceRecord(
            strategy = "cloud_anchor",
            cloudAnchorId = "ua-123",
            hostedAtEpochMs = 1_700_000_000_000L,
            ttlDays = 1,
            originDescription = "by the door",
        ),
        destinations = listOf(
            Destination(
                id = "bathroom",
                name = "Bathroom",
                position = Vec3(2.13f, 0.04f, 4.72f),
                approachHeadingDeg = -37.5f,
                createdAtEpochMs = 1_700_000_050_000L,
            ),
            Destination(id = "door", name = "Door", position = Vec3(0f, 0f, 0f)),
        ),
        waypoints = listOf(Waypoint("w1", Vec3(1f, 0f, 1f))),
        edges = listOf(Edge("w1", "bathroom", 0.9f)),
    )

    @Test
    fun `a room survives a round trip through its file format`() {
        assertEquals(room, RoomJson.decode(RoomJson.encode(room)))
    }

    @Test
    fun `optional destination fields stay absent rather than becoming zero`() {
        val decoded = RoomJson.decode(RoomJson.encode(room))
        val door = decoded.destinations.first { it.id == "door" }
        assertNull(door.approachHeadingDeg)
        assertNull(door.waypointId)
    }

    @Test
    fun `a room written by a newer app is rejected instead of silently misread`() {
        val newer = RoomJson.encode(room).replace(
            "\"schemaVersion\": ${RoomMap.SCHEMA_VERSION}",
            "\"schemaVersion\": ${RoomMap.SCHEMA_VERSION + 1}",
        )
        val error = runCatching { RoomJson.decode(newer) }.exceptionOrNull()
        assertTrue("expected a rejection, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `marking the same destination twice replaces it`() {
        val moved = Destination(
            id = "bathroom",
            name = "bathroom",
            position = Vec3(9f, 0f, 9f),
        )
        val updated = room.withDestination(moved, nowEpochMs = 1_700_000_200_000L)
        assertEquals(2, updated.destinations.size)
        assertEquals(Vec3(9f, 0f, 9f), updated.destinations.first { it.id == "bathroom" }.position)
        assertEquals(1_700_000_200_000L, updated.updatedAtEpochMs)
    }
}
