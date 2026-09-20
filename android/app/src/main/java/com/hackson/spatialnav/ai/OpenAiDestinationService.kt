package com.hackson.spatialnav.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hackson.spatialnav.BuildConfig
import com.hackson.spatialnav.model.Destination
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single network call in the app: one tiny classification request to OpenAI.
 *
 * Deliberately built on HttpURLConnection — adding a networking stack for one request would
 * cost more than it saves. Every failure path (no key, no network, timeout, garbage
 * response) collapses into [DestinationResolver.Outcome.Failed], because the user still has
 * the destination buttons and must never be blocked by the AI layer.
 *
 * Hackathon demo configuration: the key is compiled into the APK and is therefore
 * extractable. Production must call a backend proxy that holds the key instead.
 */
class OpenAiDestinationService(
    private val apiKey: String = BuildConfig.OPENAI_API_KEY,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun resolve(
        request: String,
        destinations: List<Destination>,
        onResult: (DestinationResolver.Outcome) -> Unit,
    ) {
        Log.i(TAG, "request started (${destinations.size} destinations available)")
        if (!isConfigured) {
            onResult(DestinationResolver.Outcome.Failed("no API key in this build"))
            return
        }
        if (destinations.isEmpty()) {
            onResult(DestinationResolver.Outcome.NoMatch)
            return
        }
        executor.execute {
            val outcome = runCatching {
                val answer = callModel(DestinationResolver.prompt(request, destinations))
                DestinationResolver.resolve(answer, destinations)
            }.getOrElse { error ->
                Log.w(TAG, "network_error ${error.javaClass.simpleName}")
                DestinationResolver.Outcome.Failed("could not reach the assistant")
            }
            when (outcome) {
                is DestinationResolver.Outcome.Matched -> Log.i(TAG, "matched=${outcome.destination.name}")
                DestinationResolver.Outcome.NoMatch -> Log.i(TAG, "no_match")
                is DestinationResolver.Outcome.Failed -> Log.w(TAG, "failed=${outcome.reason}")
            }
            main.post { onResult(outcome) }
        }
    }

    fun shutdown() = executor.shutdownNow()

    /** Returns the assistant message content, or throws; never logs the key or raw body. */
    private fun callModel(prompt: String): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            .toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) {
                // The error body can echo request details; only the status code is safe.
                throw IOException("HTTP ${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "AIResolver"

        /** Small, fast and current: this is a one-label classification, not reasoning. */
        const val MODEL = "gpt-4o-mini"
        val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8).toInt()
    }
}
