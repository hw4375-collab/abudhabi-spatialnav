package com.hackson.spatialnav.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.hackson.spatialnav.model.SpatialReferenceRecord

/**
 * The room origin is a physical spot the person stands on, described in words.
 *
 * Creating the reference drops a local anchor at the current pose and writes down where that
 * was ("on the door mat, facing the window"). Reopening the room means physically standing
 * there again and confirming — the app then anchors the saved room frame to that pose.
 *
 * Accuracy is therefore human placement accuracy, tens of centimetres, and the person has to
 * walk to the marker. In exchange it needs no Google Cloud project, no API key, no network,
 * and it cannot fail because a white wall has no visual features. It is the fallback that
 * keeps the demo alive when Cloud Anchors are unavailable, and the reference implementation
 * that proves [SpatialReferenceStrategy] is not shaped around Cloud Anchors.
 */
class ManualOriginStrategy : SpatialReferenceStrategy {

    override val id = ID
    override val displayName = "Manual origin (stand on the marker)"

    override var originAnchor: Anchor? = null
        private set

    private var statusText = "not set"

    override fun unavailableReason(): String? = null

    override fun create(
        session: Session,
        pose: Pose,
        originDescription: String,
        onResult: (Result<SpatialReferenceRecord>) -> Unit,
    ) {
        attach(session, pose)
        statusText = "origin set here"
        Log.i(TAG, "created manual origin: $originDescription")
        onResult(
            Result.success(
                SpatialReferenceRecord(
                    strategy = ID,
                    hostedAtEpochMs = System.currentTimeMillis(),
                    originDescription = originDescription,
                )
            )
        )
    }

    /**
     * Re-establishes the frame at the *current* pose, which is only correct if the person is
     * actually standing on the marker — the UI must say so before calling this.
     */
    override fun resolve(
        session: Session,
        record: SpatialReferenceRecord,
        currentCameraPose: Pose,
        onResult: (Result<Unit>) -> Unit,
    ) {
        attach(session, currentCameraPose)
        statusText = "aligned to current position"
        Log.i(TAG, "resolved manual origin from description: ${record.originDescription}")
        onResult(Result.success(Unit))
    }

    private fun attach(session: Session, pose: Pose) {
        originAnchor?.detach()
        originAnchor = session.createAnchor(pose)
    }

    override fun status(): String = statusText

    override fun close() {
        originAnchor?.detach()
        originAnchor = null
    }

    companion object {
        const val ID = "manual_origin"
        private const val TAG = "ManualOrigin"
    }
}
