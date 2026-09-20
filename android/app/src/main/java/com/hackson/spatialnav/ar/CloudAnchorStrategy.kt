package com.hackson.spatialnav.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.hackson.spatialnav.model.SpatialReferenceRecord

/**
 * The room origin is an ARCore Cloud Anchor: feature data uploaded to Google, resolved again
 * by any session that sees the same physical place.
 *
 * This is the mechanism the product wants — the person walks in, points the phone around,
 * and the room comes back with no marker to find. It costs a Google Cloud project with the
 * ARCore API enabled, an API key in the manifest, a network connection, and a physical space
 * with enough visual texture to match against. With an API key (rather than OAuth) the
 * hosted anchor lives for one day, which is exactly the hackathon's horizon.
 *
 * @param apiKeyConfigured whether a key was injected at build time; without it hosting fails
 *   with `ERROR_NOT_AUTHORIZED`, so the strategy reports itself unavailable instead.
 */
class CloudAnchorStrategy(private val apiKeyConfigured: Boolean) : SpatialReferenceStrategy {

    override val id = ID
    override val displayName = "Cloud Anchor (ARCore)"

    override var originAnchor: Anchor? = null
        private set

    private var statusText = "idle"

    override fun unavailableReason(): String? = when {
        apiKeyConfigured -> null
        else -> "no ARCore API key in this build (see docs/07-p2-device-test.md)"
    }

    override fun create(
        session: Session,
        pose: Pose,
        originDescription: String,
        onResult: (Result<SpatialReferenceRecord>) -> Unit,
    ) {
        unavailableReason()?.let {
            onResult(Result.failure(IllegalStateException(it)))
            return
        }
        val anchor = session.createAnchor(pose)
        statusText = "hosting..."
        Log.i(TAG, "HOST=started ttlDays=$TTL_DAYS")
        session.hostCloudAnchorAsync(anchor, TTL_DAYS) { cloudId, state ->
            if (state == Anchor.CloudAnchorState.SUCCESS && cloudId != null) {
                originAnchor = anchor
                statusText = "hosted ($cloudId)"
                Log.i(TAG, "HOST=SUCCESS id=$cloudId")
                onResult(
                    Result.success(
                        SpatialReferenceRecord(
                            strategy = ID,
                            cloudAnchorId = cloudId,
                            hostedAtEpochMs = System.currentTimeMillis(),
                            ttlDays = TTL_DAYS,
                            originDescription = originDescription,
                        )
                    )
                )
            } else {
                anchor.detach()
                statusText = "host failed: $state"
                Log.w(TAG, "HOST=FAILED state=$state")
                onResult(Result.failure(IllegalStateException(explain(state))))
            }
        }
    }

    override fun resolve(
        session: Session,
        record: SpatialReferenceRecord,
        currentCameraPose: Pose,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val cloudId = record.cloudAnchorId
        if (cloudId.isNullOrEmpty()) {
            onResult(Result.failure(IllegalStateException("room has no cloud anchor id")))
            return
        }
        unavailableReason()?.let {
            onResult(Result.failure(IllegalStateException(it)))
            return
        }
        statusText = "resolving..."
        Log.i(TAG, "RESOLVE=started id=$cloudId")
        session.resolveCloudAnchorAsync(cloudId) { anchor, state ->
            if (state == Anchor.CloudAnchorState.SUCCESS && anchor != null) {
                originAnchor = anchor
                statusText = "resolved"
                Log.i(TAG, "RESOLVE=SUCCESS id=$cloudId")
                onResult(Result.success(Unit))
            } else {
                anchor?.detach()
                statusText = "resolve failed: $state"
                Log.w(TAG, "RESOLVE=FAILED state=$state")
                onResult(Result.failure(IllegalStateException(explain(state))))
            }
        }
    }

    /**
     * Turns the SDK's enum into something the person holding the phone can act on.
     *
     * Deprecated constants are still matched: the SDK can return them, and a message the
     * user can act on matters more than the deprecation.
     */
    @Suppress("DEPRECATION")
    private fun explain(state: Anchor.CloudAnchorState): String = when (state) {
        Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
            "not authorized: the API key is missing, wrong, or the ARCore API is not enabled"

        Anchor.CloudAnchorState.ERROR_RESOLVING_LOCALIZATION_NO_MATCH ->
            "the phone does not recognise this place: stand where the room was created, " +
                "point at the same walls and objects, and move slowly"

        Anchor.CloudAnchorState.ERROR_CLOUD_ID_NOT_FOUND ->
            "the hosted anchor is gone: with an API key it only lives $TTL_DAYS day(s), " +
                "so the room must be created again"

        Anchor.CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED ->
            "not enough visual detail to host here: pick a spot with texture, not a blank wall"

        Anchor.CloudAnchorState.ERROR_SERVICE_UNAVAILABLE,
        Anchor.CloudAnchorState.ERROR_HOSTING_SERVICE_UNAVAILABLE ->
            "no connection to the ARCore service: check the network"

        Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED ->
            "API quota exhausted for this project"

        else -> "cloud anchor failed: $state"
    }

    override fun status(): String = statusText

    override fun close() {
        originAnchor?.detach()
        originAnchor = null
    }

    companion object {
        const val ID = "cloud_anchor"

        /** An API-key project cannot host for longer than one day. */
        const val TTL_DAYS = 1
        private const val TAG = "CloudAnchor"
    }
}
