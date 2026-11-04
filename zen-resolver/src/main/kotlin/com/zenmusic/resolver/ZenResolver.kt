package com.zenmusic.resolver

import com.zenmusic.core.ZenConfig
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.core.uri.ZenUri

/**
 * Tamper hook. The engine consults it before serving playback metadata; when
 * it reports tampering and the fake-stream shower is enabled, decoy streams
 * are returned instead (see [com.zenmusic.core.decoy.ZenDecoys]).
 *
 * The Android umbrella ships an implementation that checks for attached
 * debuggers and emulator fingerprints; hosts may plug their own.
 */
fun interface TamperDetector {
    fun isTampered(): Boolean

    companion object {
        /** Default: never tampered. */
        val NONE: TamperDetector = TamperDetector { false }
    }
}

/**
 * Resolves disguised `zen://` URIs into playable [com.zenmusic.core.model.ZenStream]s.
 *
 * Flow:
 *   zen://<id>.<decoy>  →  validate  →  extract (engine)  →  broker register
 *   →  ZenStream(opaque handle)
 *
 * The real media location never leaves the engine: apps play through
 * [openChannel] (transport handles built on it — see `zen-datasource`).
 */
class ZenResolver(
    private val extractor: com.zenmusic.extractor.ZenExtractor,
    private val config: ZenConfig,
    private val tamperDetector: TamperDetector = TamperDetector.NONE,
) {

    internal val broker = StreamBroker()

    /**
     * Resolves one zen:// stream uri. Throws [com.zenmusic.core.ZenResolutionException]
     * on failure; returns a decoy stream when tampering is detected and the
     * shower is enabled.
     */
    suspend fun resolve(uri: String, quality: ZenQuality? = null): com.zenmusic.core.model.ZenStream {
        val parsed = ZenUri.parse(uri)
            ?: throw com.zenmusic.core.ZenResolutionException("not a zen stream uri")
        if (parsed.kind != ZenUri.Kind.STREAM) {
            throw com.zenmusic.core.ZenResolutionException("uri kind ${parsed.kind} is not playable")
        }
        if (config.decoyShower && tamperDetector.isTampered()) {
            com.zenmusic.core.log.ZenLog.w("tamper signal — serving shower decoy")
            return com.zenmusic.core.decoy.ZenDecoys.shower(1).first()
        }
        val plan = extractor.streamPlan(parsed.id, quality ?: config.preferredQuality)
        return broker.register(plan)
    }

    /** Bulk resolve; failures are skipped (missing entries in the result). */
    suspend fun resolveAll(
        uris: List<String>,
        quality: ZenQuality? = null,
    ): List<com.zenmusic.core.model.ZenStream> = uris.mapNotNull {
        try {
            resolve(it, quality)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Opens a transport channel for [handle] at [position] bytes.
     * Returns null when the handle is unknown/expired — callers (including
     * the zen-datasource adapter) treat null as "serve decoy/silence".
     */
    fun openChannel(handle: String, position: Long = 0L): MediaChannel? =
        broker.channel(handle, position)

    /**
     * Fetches artwork bytes for a `zen://a/…` uri. The upstream image host is
     * contacted with the neutral engine user-agent and no referer.
     */
    suspend fun fetchArtwork(uri: String): ByteArray? {
        val parsed = ZenUri.parse(uri) ?: return null
        if (parsed.kind != ZenUri.Kind.ARTWORK && parsed.kind != ZenUri.Kind.STREAM) return null
        return extractor.artworkBytes(parsed.id)
    }

    /** Drops expired broker entries. */
    fun purgeExpired(): Int = broker.purge()

    /** Nukes all broker state (e.g. on logout / account switch). */
    fun destroy() = broker.clear()
}
