package com.hackson.spatialnav.model

/**
 * The one thing the room model cannot express without the platform: what time it is now.
 * Declared here and supplied per target so [SpatialReferenceRecord.isExpired] can stay in
 * common code.
 */
internal expect fun nowEpochMs(): Long
