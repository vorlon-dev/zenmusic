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

    // ---------- prefetch ----------

    private val prefetched = LinkedHashMap<String, com.zenmusic.core.model.ZenStream>()

    private fun prefetchKey(uri: String, quality: ZenQuality?) = "$uri|${quality ?: "-"}"

    /**
     * Remembers an already-resolved stream so the next [resolve] for the same
     * uri+quality returns instantly (engine-internal; also the test hook).
     */
    internal fun rememberPrefetched(uri: String, quality: ZenQuality?, stream: com.zenmusic.core.model.ZenStream) {
        synchronized(prefetched) {
            prefetched[prefetchKey(uri, quality)] = stream
            while (prefetched.size > PREFETCH_MAX) {
                val eldest = prefetched.keys.firstOrNull() ?: break
                prefetched.remove(eldest)
            }
        }
    }

    private fun takePrefetched(uri: String, quality: ZenQuality?): com.zenmusic.core.model.ZenStream? {
        synchronized(prefetched) {
            val key = prefetchKey(uri, quality)
            val stream = prefetched.remove(key) ?: return null
            // must still be comfortably valid when the player opens it
            if (stream.expiresAtMs - System.currentTimeMillis() < 60_000L) return null
            return stream
        }
    }

    /**
     * Resolves [uri] in the background so a later [resolve] is instant.
     * Ideal for auto-queues: while the current song plays, prefetch the next
     * one — the next track then starts without any extraction latency.
     */
    suspend fun prefetch(uri: String, quality: ZenQuality? = null) {
        synchronized(prefetched) {
            if (prefetched.containsKey(prefetchKey(uri, quality))) return
        }
        try {
            val stream = resolve(uri, quality)
            rememberPrefetched(uri, quality, stream)
        } catch (_: Throwable) {
            // prefetch is best-effort; the real resolve reports errors
        }
    }

    /** True when a fresh prefetched stream exists for [uri]. */
    fun isPrefetched(uri: String, quality: ZenQuality? = null): Boolean {
        synchronized(prefetched) { return prefetched.containsKey(prefetchKey(uri, quality)) }
    }

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
        // Fast path: a prefetched stream for this exact uri+quality.
        takePrefetched(uri, quality)?.let { return it }
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

    /** Drops expired broker entries and stale prefetches. */
    fun purgeExpired(): Int = broker.purge()

    /** Nukes all broker state (e.g. on logout / account switch). */
    fun destroy() {
        synchronized(prefetched) { prefetched.clear() }
        broker.clear()
    }

    companion object {
        private const val PREFETCH_MAX = 12
    }
}
