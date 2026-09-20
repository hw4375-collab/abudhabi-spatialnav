package com.hackson.spatialnav.persistence

import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.model.SpatialReferenceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = RoomStore(folder.root)

    private fun room(id: String, name: String, updatedAt: Long) = RoomMap(
        roomId = id,
        displayName = name,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = updatedAt,
        spatialReference = SpatialReferenceRecord(strategy = "manual_origin"),
    )

    @Test
    fun `a saved room is readable again`() {
        val saved = room("r1", "Test Room", 10L)
        store().save(saved)
        assertEquals(saved, store().load("r1"))
    }

    @Test
    fun `saving the same room again replaces it instead of piling up files`() {
        val store = store()
        store.save(room("r1", "Test Room", 10L))
        store.save(room("r1", "Renamed", 20L))
        assertEquals(listOf("Renamed"), store.list().map { it.displayName })
    }

    @Test
    fun `rooms are listed most recently updated first`() {
        val store = store()
        store.save(room("old", "Old", 10L))
        store.save(room("new", "New", 99L))
        assertEquals(listOf("New", "Old"), store.list().map { it.displayName })
    }

    @Test
    fun `a corrupted room file is skipped rather than taking the list down with it`() {
        val store = store()
        store.save(room("good", "Good", 10L))
        folder.newFile("broken.json").writeText("{ not json")
        assertEquals(listOf("Good"), store.list().map { it.displayName })
    }

    @Test
    fun `an unknown room id reads back as nothing`() {
        assertNull(store().load("missing"))
    }
}
