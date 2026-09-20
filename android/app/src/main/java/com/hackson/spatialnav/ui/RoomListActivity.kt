package com.hackson.spatialnav.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hackson.spatialnav.R
import com.hackson.spatialnav.databinding.ActivityRoomListBinding
import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.persistence.RoomStore

/**
 * Home: navigate a space that was mapped earlier, or map a new one.
 *
 * Diagnostics live behind a single small entry at the bottom — the hardware QA screens are
 * still one tap away without turning the first thing a user sees into a tool.
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

        binding.navigateButton.setOnClickListener { navigate() }
        binding.createRoomButton.setOnClickListener { askRoomName() }
        binding.diagnosticsButton.setOnClickListener { showDiagnostics() }
        binding.roomList.setOnItemClickListener { _, _, position, _ -> open(rooms[position]) }
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
        binding.emptyText.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
        binding.navigateButton.isEnabled = rooms.isNotEmpty()
        binding.roomList.adapter = ArrayAdapter(this, R.layout.item_space, rooms.map(::describe))
    }

    private fun describe(room: RoomMap): String = buildString {
        append(room.displayName)
        append("\n")
        append(
            when (room.destinations.size) {
                0 -> "no destinations yet"
                1 -> "1 destination"
                else -> "${room.destinations.size} destinations"
            }
        )
    }

    /** One saved space goes straight in; several ask which one. */
    private fun navigate() {
        when (rooms.size) {
            0 -> Toast.makeText(this, R.string.no_spaces_yet, Toast.LENGTH_LONG).show()
            1 -> open(rooms.first())
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.saved_spaces)
                .setItems(rooms.map { it.displayName }.toTypedArray()) { _, index ->
                    open(rooms[index])
                }
                .show()
        }
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
            .setMessage("Delete this space and its destinations?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(room.roomId)
                refresh()
            }
            .show()
    }

    private fun showDiagnostics() {
        val screens = arrayOf(
            getString(R.string.hardware_check),
            getString(R.string.open_ar),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setItems(screens) { _, index ->
                startActivity(
                    when (index) {
                        0 -> Intent(this, HardwareCheckActivity::class.java)
                        else -> Intent(this, ArDiagnosticActivity::class.java)
                    }
                )
            }
            .show()
    }
}
