package com.hackson.spatialnav.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hackson.spatialnav.BuildConfig
import com.hackson.spatialnav.R
import com.hackson.spatialnav.ar.CloudAnchorStrategy
import com.hackson.spatialnav.ar.ManualOriginStrategy
import com.hackson.spatialnav.databinding.ActivityRoomListBinding
import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.persistence.RoomStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The app's entry point: create a room, or reopen one that was mapped earlier.
 *
 * Reopening a saved room is the whole point of P2, so the list is the first thing on screen.
 */
class RoomListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomListBinding
    private lateinit var store: RoomStore
    private var rooms: List<RoomMap> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RoomStore(RoomStore.defaultDirectory(this))

        binding.createRoomButton.setOnClickListener { askRoomName() }
        binding.hardwareCheckButton.setOnClickListener {
            startActivity(Intent(this, HardwareCheckActivity::class.java))
        }
        binding.arDiagnosticButton.setOnClickListener {
            startActivity(Intent(this, ArDiagnosticActivity::class.java))
        }
        binding.roomList.setOnItemClickListener { _, _, position, _ ->
            open(rooms[position])
        }
        binding.roomList.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(rooms[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rooms = store.list()
        binding.headerText.text = buildString {
            append("Spatial reference: ")
            append(
                if (BuildConfig.ARCORE_API_KEY_CONFIGURED) {
                    "${CloudAnchorStrategy.ID} (API key present)"
                } else {
                    "${ManualOriginStrategy.ID} (no ARCore API key in this build)"
                }
            )
            append("\nSaved rooms: ${rooms.size}")
        }
        binding.roomList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rooms.map(::describe),
        )
    }

    private fun describe(room: RoomMap): String = buildString {
        append(room.displayName)
        append("\n  ${room.destinations.size} destinations")
        append(" · ${room.spatialReference.strategy}")
        if (room.spatialReference.isExpired) append(" · REFERENCE EXPIRED")
        append("\n  saved ${TIMESTAMP.format(room.updatedAtEpochMs)}")
    }

    private fun askRoomName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText("Test Room")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.room_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Room" }
                startActivity(RoomActivity.createIntent(this, name))
            }
            .show()
    }

    private fun open(room: RoomMap) {
        startActivity(RoomActivity.openIntent(this, room.roomId))
    }

    private fun confirmDelete(room: RoomMap) {
        AlertDialog.Builder(this)
            .setTitle(room.displayName)
            .setMessage("Delete this room and its destinations?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(room.roomId)
                refresh()
            }
            .show()
    }

    private companion object {
        val TIMESTAMP = SimpleDateFormat("MMM d HH:mm", Locale.US)
    }
}
