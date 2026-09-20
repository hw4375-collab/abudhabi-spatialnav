package com.hackson.spatialnav.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hackson.spatialnav.navigation.NavigationEngine
import com.hackson.spatialnav.perception.DepthClearanceAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Background = Color(0xFF101418)
private val Panel = Color(0xFF1B2127)
private val Accent = Color(0xFF69F0AE)
private val Danger = Color(0xFFFF5252)
private val Muted = Color(0xFF8A97A5)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SpatialNav Simulator",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        MaterialTheme(colors = MaterialTheme.colors.copy(isLight = false)) {
            SimulatorScreen()
        }
    }
}

@Composable
private fun SimulatorScreen() {
    val simulation = remember { Simulation() }
    var world by remember { mutableStateOf(simulation.world()) }

    Row(Modifier.fillMaxSize().background(Background).padding(28.dp)) {
        Column(Modifier.weight(1f).fillMaxSize()) {
            Text("SpatialNav", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text("Spatial Navigation Simulator", color = Muted, fontSize = 20.sp)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel),
            ) {
                TopDownView(world)
            }
            Spacer(Modifier.height(18.dp))
            Controls(
                onForward = { world = simulation.moveForward() },
                onLeft = { world = simulation.turn(-15f) },
                onRight = { world = simulation.turn(15f) },
                onObstacle = {
                    world = if (world.obstacle == null) {
                        simulation.placeObstacle()
                    } else {
                        simulation.removeObstacle()
                    }
                },
                onReset = { world = simulation.reset() },
                obstaclePlaced = world.obstacle != null,
            )
        }
        Spacer(Modifier.width(28.dp))
        GuidancePanel(world)
    }
}

@Composable
private fun GuidancePanel(world: Simulation.World) {
    val blocked = world.obstacleState.isObstacle
    Column(
        Modifier.width(360.dp).fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(Panel).padding(28.dp),
    ) {
        Label("Destination")
        Text(world.destination.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        Label("Distance")
        Text(
            "%.1f m".format(world.fix.distanceMeters),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(30.dp))
        Label("Guidance")
        Text(
            arrowFor(world),
            color = if (blocked) Danger else Accent,
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            instructionFor(world),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        Label("Status")
        Text(
            if (blocked) "OBSTACLE AHEAD" else "Path clear",
            color = if (blocked) Danger else Accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        world.spoken?.let {
            Spacer(Modifier.height(18.dp))
            Label("Voice")
            Text("\u201C$it\u201D", color = Color(0xFFCFD8DC), fontSize = 17.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "left %s   center %s   right %s".format(
                meters(world.clearance.leftMeters),
                meters(world.clearance.centerMeters),
                meters(world.clearance.rightMeters),
            ),
            color = Muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("Kotlin Multiplatform • Shared Spatial Core", color = Accent, fontSize = 13.sp)
    }
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Controls(
    onForward: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onObstacle: () -> Unit,
    onReset: () -> Unit,
    obstaclePlaced: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SimButton("Move Forward", Modifier.weight(2f), onForward)
            SimButton("Turn Left", Modifier.weight(1f), onLeft)
            SimButton("Turn Right", Modifier.weight(1f), onRight)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SimButton(
                if (obstaclePlaced) "Remove Obstacle" else "Place Obstacle",
                Modifier.weight(1f),
                onObstacle,
            )
            SimButton("Reset", Modifier.weight(1f), onReset)
        }
    }
}

@Composable
private fun SimButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2A333C)),
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Plan view of the room: where the user is, where the destination is, what is in between. */
@Composable
private fun TopDownView(world: Simulation.World) {
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.height / 9f
        fun point(x: Float, z: Float) = Offset(
            x = size.width / 2f + x * scale,
            y = size.height - 1.2f * scale - z * scale,
        )

        for (metre in 0..8) {
            val y = point(0f, metre.toFloat()).y
            drawLine(Color(0xFF232B33), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val user = point(world.user.x, world.user.z)
        val destination = point(world.destination.position.x, world.destination.position.z)

        drawLine(
            color = Color(0xFF2E3B46),
            start = user,
            end = destination,
            strokeWidth = 3f,
        )

        drawCircle(Accent, radius = 14f, center = destination, style = Stroke(width = 4f))
        drawCircle(
            color = Accent.copy(alpha = 0.22f),
            radius = NavigationEngine.ARRIVAL_RADIUS_METERS * scale,
            center = destination,
        )

        world.obstacle?.let {
            drawCircle(Danger, radius = 0.45f * scale, center = point(it.x, it.z))
        }

        drawUser(user, world.user.yawDeg, scale, world.obstacleState.isObstacle)
    }
}

private fun DrawScope.drawUser(at: Offset, yawDeg: Float, scale: Float, blocked: Boolean) {
    val radians = (yawDeg * PI / 180.0).toFloat()
    val nose = Offset(
        x = at.x + sin(radians) * 0.75f * scale,
        y = at.y - cos(radians) * 0.75f * scale,
    )
    drawLine(
        color = if (blocked) Danger else Color.White,
        start = at,
        end = nose,
        strokeWidth = 5f,
    )
    drawCircle(if (blocked) Danger else Color.White, radius = 11f, center = at)
}

private fun arrowFor(world: Simulation.World): String = when {
    world.obstacleState == DepthClearanceAnalyzer.State.MOVE_LEFT -> "\u2190"
    world.obstacleState == DepthClearanceAnalyzer.State.MOVE_RIGHT -> "\u2192"
    world.obstacleState == DepthClearanceAnalyzer.State.STOP -> "\u2715"
    world.fix.state == NavigationEngine.State.TURN_LEFT -> "\u21B0"
    world.fix.state == NavigationEngine.State.TURN_RIGHT -> "\u21B1"
    world.fix.state == NavigationEngine.State.ARRIVED -> "\u2713"
    else -> "\u2191"
}

private fun instructionFor(world: Simulation.World): String = when (world.obstacleState) {
    DepthClearanceAnalyzer.State.MOVE_LEFT -> "MOVE LEFT"
    DepthClearanceAnalyzer.State.MOVE_RIGHT -> "MOVE RIGHT"
    DepthClearanceAnalyzer.State.STOP -> "STOP"
    else -> world.fix.state.name.replace('_', ' ')
}

private fun meters(value: Float) = if (value.isNaN()) "-" else "%.1f".format(value)
