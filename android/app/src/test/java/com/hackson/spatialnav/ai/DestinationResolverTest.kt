package com.hackson.spatialnav.ai

import com.hackson.spatialnav.model.Destination
import com.hackson.spatialnav.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationResolverTest {

    private fun destination(name: String) = Destination(
        id = name.lowercase(),
        name = name,
        position = Vec3(0f, 0f, 0f),
        approachHeadingDeg = 0f,
        createdAtEpochMs = 0L,
    )

    private val room = listOf(destination("Bathroom"), destination("Door"))

    @Test
    fun `accepts a destination that exists in the room`() {
        val outcome = DestinationResolver.resolve("""{"destination": "Bathroom"}""", room)
        assertEquals("Bathroom", (outcome as DestinationResolver.Outcome.Matched).destination.name)
    }

    @Test
    fun `matching ignores case and stray whitespace`() {
        val outcome = DestinationResolver.resolve("""{"destination": " bathroom "}""", room)
        assertTrue(outcome is DestinationResolver.Outcome.Matched)
    }

    @Test
    fun `reads json out of a fenced or chatty reply`() {
        val outcome = DestinationResolver.resolve(
            "Sure!\n```json\n{\"destination\": \"Door\"}\n```",
            room,
        )
        assertEquals("Door", (outcome as DestinationResolver.Outcome.Matched).destination.name)
    }

    @Test
    fun `rejects a destination the room does not have`() {
        val outcome = DestinationResolver.resolve("""{"destination": "Kitchen"}""", room)
        assertEquals(DestinationResolver.Outcome.NoMatch, outcome)
    }

    @Test
    fun `explicit null is no match`() {
        val outcome = DestinationResolver.resolve("""{"destination": null}""", room)
        assertEquals(DestinationResolver.Outcome.NoMatch, outcome)
    }

    @Test
    fun `malformed response fails instead of guessing`() {
        val outcome = DestinationResolver.resolve("I think the bathroom?", room)
        assertTrue(outcome is DestinationResolver.Outcome.Failed)
    }

    @Test
    fun `prompt lists only the destinations that exist`() {
        val prompt = DestinationResolver.prompt("take me to the restroom", room)
        assertTrue(prompt.contains("- Bathroom"))
        assertTrue(prompt.contains("- Door"))
        assertTrue(!prompt.contains("Desk"))
    }
}
