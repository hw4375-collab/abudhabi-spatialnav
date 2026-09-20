package com.hackson.spatialnav.persistence

import android.content.Context
import android.util.Log
import com.hackson.spatialnav.model.RoomMap
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Rooms on local storage, one JSON file per room.
 *
 * No database and no backend: a handful of rooms, written whole, is exactly the amount of
 * machinery this needs. Writes go through a temporary file so a crash mid-save cannot leave
 * a half-written room behind — losing a mapped room to a truncated file would cost a remap.
 */
class RoomStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    fun list(): List<RoomMap> = directory
        .listFiles { file -> file.extension == EXTENSION }
        .orEmpty()
        .mapNotNull { file ->
            runCatching { RoomJson.decode(file.readText()) }
                .onFailure { Log.w(TAG, "skipping unreadable room ${file.name}", it) }
                .getOrNull()
        }
        .sortedByDescending { it.updatedAtEpochMs }

    fun load(roomId: String): RoomMap? =
        fileFor(roomId).takeIf { it.exists() }?.let { RoomJson.decode(it.readText()) }

    fun save(room: RoomMap) {
        val target = fileFor(room.roomId)
        val temp = File(directory, "${room.roomId}.tmp")
        temp.writeText(RoomJson.encode(room))
        // renameTo does not overwrite on every filesystem; re-saving a room must not fail.
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Log.i(TAG, "saved room ${room.roomId} (${room.destinations.size} destinations)")
    }

    fun delete(roomId: String): Boolean = fileFor(roomId).delete()

    private fun fileFor(roomId: String) = File(directory, "$roomId.$EXTENSION")

    companion object {
        private const val TAG = "RoomStore"
        private const val EXTENSION = "json"

        /** App-private storage: no permissions, and it survives app restarts. */
        fun defaultDirectory(context: Context) = File(context.filesDir, "rooms")
    }
}
