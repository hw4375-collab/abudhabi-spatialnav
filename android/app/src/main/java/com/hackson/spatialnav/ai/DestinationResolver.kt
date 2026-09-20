package com.hackson.spatialnav.ai

import com.hackson.spatialnav.model.Destination
import org.json.JSONObject

/**
 * Turns "take me to the restroom" into one of the destinations the user actually saved.
 *
 * The model only ever picks a name from a list we give it; it never produces coordinates,
 * distances or instructions. Everything it returns is matched back against the local
 * RoomMap, so a hallucinated destination cannot reach the navigation code — the worst a bad
 * response can do is fall through to [Outcome.NoMatch] and leave the user on the buttons.
 */
object DestinationResolver {

    sealed interface Outcome {
        data class Matched(val destination: Destination) : Outcome

        /** The request made sense but names nothing in this room. */
        data object NoMatch : Outcome

        data class Failed(val reason: String) : Outcome
    }

    fun prompt(request: String, destinations: List<Destination>): String = buildString {
        append("The user of an indoor navigation app said: \"")
        append(request.replace("\"", "'"))
        append("\".\nAvailable destinations in this room:\n")
        destinations.forEach { append("- ").append(it.name).append('\n') }
        append(
            "Reply with JSON only: {\"destination\": \"<exact name from the list>\"} " +
                "or {\"destination\": null} if the request does not refer to any of them. " +
                "Never invent a destination that is not listed."
        )
    }

    /**
     * Reads the model's answer and resolves it locally. Matching is case- and
     * whitespace-insensitive because the model echoes a name it was given, not an id.
     */
    fun resolve(modelAnswer: String, destinations: List<Destination>): Outcome {
        val json = extractJsonObject(modelAnswer)
            ?: return Outcome.Failed("response was not JSON")
        if (!json.has(FIELD) || json.isNull(FIELD)) return Outcome.NoMatch
        val name = json.optString(FIELD).trim()
        if (name.isEmpty() || name.equals("null", ignoreCase = true)) return Outcome.NoMatch
        val match = destinations.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
        return if (match == null) Outcome.NoMatch else Outcome.Matched(match)
    }

    /** Models like to wrap JSON in prose or a ```json fence; take the first object. */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private const val FIELD = "destination"
}
