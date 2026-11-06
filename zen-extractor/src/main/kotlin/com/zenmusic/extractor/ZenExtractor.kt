package com.zenmusic.extractor

import com.zenmusic.core.ZenExtractionException
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.core.uri.ZenUri
import com.zenmusic.core.vault.ZenVault
import com.zenmusic.extractor.innertube.Parsers
import com.zenmusic.potoken.PotokenCoordinator
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Stream extraction entry point. Produces [StreamPlan]s — internal objects
 * whose real media URLs never escape the engine (the resolver wraps them
 * into public [com.zenmusic.core.model.ZenStream] handles).
 *
 * Prefer constructing this through the `zenmusic` umbrella; direct wiring is
 * for advanced / modular embedding.
 */
class ZenExtractor(
    private val http: OkHttpClient,
    internal val config: com.zenmusic.core.ZenConfig,
    private val potoken: PotokenCoordinator,
    private val streamEnhancer: StreamUrlEnhancer? = null,
    /** Recovers googlevideo URLs from `signatureCipher` formats (web clients). */
    private val decipher: SignatureDecipher? = null,
    /** Current player signatureTimestamp (from the cipher's player config). */
    private val signatureTimestamp: (suspend () -> Int?)? = null,
) {

    private val zenApi = com.zenmusic.extractor.innertube.ZenInnertube(
        http = http,
        potoken = potoken,
        gl = config.gl,
        hl = config.hl,
        pinnedVisitorData = config.visitorData,
        signatureTimestamp = signatureTimestamp,
    )

    val search: ZenSearchApi = ZenSearchApi(zenApi)
    val radio: ZenRadioApi = ZenRadioApi(zenApi)
    val home: ZenHomeApi = ZenHomeApi(zenApi)
    val lyrics: ZenLyricsApi = ZenLyricsApi(http, config)

    // ---------- player-response cache ----------

    private class CachedPlayer(
        @field:Volatile var payload: org.json.JSONObject? = null,
        var atMs: Long = 0L,
    )

    private val playerCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlayer>()

    /**
     * Player response for [mediaId], served from a short-lived cache when a
     * recent one exists — repeat plays, prefetch hits and queue lookups skip
     * the whole extraction chain. Fails the resolve when upstream fails.
     */
    private suspend fun playerCached(mediaId: String): org.json.JSONObject {
        val ttlMs = config.playerCacheTtlSeconds.coerceAtLeast(0) * 1000L
        val hit = playerCache[mediaId]
        if (hit != null && ttlMs > 0) {
            val cachedPayload = hit.payload
            if (cachedPayload != null && System.currentTimeMillis() - hit.atMs < ttlMs) {
                return cachedPayload
            }
        }
        val payload = zenApi.player(mediaId)
        val entry = hit ?: CachedPlayer().also { playerCache[mediaId] = it }
        entry.payload = payload
        entry.atMs = System.currentTimeMillis()
        if (playerCache.size > PLAYER_CACHE_MAX) {
            playerCache.entries.removeIf {
                System.currentTimeMillis() - it.value.atMs > ttlMs.coerceAtLeast(1)
            }
            while (playerCache.size > PLAYER_CACHE_MAX) {
                val eldest = playerCache.entries.asSequence().minByOrNull { it.value.atMs }
                playerCache.remove(eldest?.key) ?: break
            }
        }
        return payload
    }

    /** Drops every cached player response (e.g. network change, logout). */
    fun invalidatePlayerCache() = playerCache.clear()

    /**
     * Warms the whole playback chain without fetching any media: session
     * visitor, PO-token WebView mint and the player-JS cipher. Called in the
     * background right after wiring (see [com.zenmusic.core.ZenConfig.warmupOnStart])
     * so the first real playback resolves in one request.
     */
    suspend fun warmup() {
        val visitor = zenApi.sessionIdentifier()
        runCatching { potoken.sessionTokenOrNull(visitor) }
        runCatching { signatureTimestamp?.invoke() }
    }

    /** Fetches track metadata for a media id / zen:// uri. */
    suspend fun track(uriOrMediaId: String): ZenTrack {
        val mediaId = mediaIdOf(uriOrMediaId)
        val payload = playerCached(mediaId)
        val p = Parsers.parsePlayer(payload, decipher?.asLambda())
        return ZenTrack(
            uri = ZenUri.mint(p.mediaId),
            title = p.title,
            artists = listOfNotNull(p.author.takeIf { it.isNotEmpty() }),
            durationMs = p.durationMs,
            artworkUri = p.thumbnailUrl?.let { ZenUri.mintArtwork(p.mediaId) },
            isVideo = p.isLive,
        )
    }

    /**
     * Fetches artwork bytes for [mediaId] from the (vault-sealed) image host
     * with the neutral engine UA. Returns null when unavailable.
     */
    suspend fun artworkBytes(mediaId: String): ByteArray? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val host = ZenVault.open("img.host")
            val ua = ZenVault.open("ua.zen")
            val candidates = listOf("maxresdefault", "hqdefault", "mqdefault")
            for (variant in candidates) {
                val url = "https://$host/vi/$mediaId/$variant.jpg"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .get()
                    .build()
                try {
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.size > 1024) return@withContext bytes
                        }
                    }
                } catch (_: Throwable) {
                    // try next variant
                }
            }
            null
        }

    /**
     * Extracts the best stream for [quality] (falls back automatically).
     * The [StreamPlan.mediaUrl] is engine-internal: never return it to app
     * code, never log it ([com.zenmusic.core.log.ZenLog] scrubs it anyway).
     */
    suspend fun streamPlan(
        uriOrMediaId: String,
        quality: ZenQuality? = null,
    ): StreamPlan {
        val mediaId = mediaIdOf(uriOrMediaId)
        val payload = playerCached(mediaId)
        val parsed = Parsers.parsePlayer(payload, decipher?.asLambda())
        val chosen = Parsers.chooseFormat(
            parsed.formats,
            quality ?: ZenQuality.HIGH,
        ) ?: throw ZenExtractionException("no audio formats available${diagnoseNoFormats(payload)}")
        val rawUrl = chosen.first.url
        val finalUrl = if (streamEnhancer != null) {
            try {
                streamEnhancer.enhance(parsed.mediaId, zenApi.sessionIdentifier(), rawUrl)
                    ?: rawUrl
            } catch (_: Throwable) {
                rawUrl // degrade gracefully: tokenless URL may still serve
            }
        } else rawUrl
        return StreamPlan(
            mediaId = parsed.mediaId,
            mediaUrl = finalUrl,
            format = chosen.second,
            isLive = parsed.isLive,
        )
    }

    companion object {
        /** Upper bound of cached player responses. */
        private const val PLAYER_CACHE_MAX = 64

        /** Accepts a zen:// uri, a bare media id, and tolerates junk. */
        fun mediaIdOf(uriOrMediaId: String): String {
            ZenUri.parse(uriOrMediaId)?.let { if (it.kind == ZenUri.Kind.STREAM) return it.id }
            val cleaned = uriOrMediaId.trim()
            if (Regex("^[A-Za-z0-9_-]{8,24}$").matches(cleaned)) return cleaned
            throw IllegalArgumentException("not a resolvable media reference")
        }

        /** True when the upstream serves genuinely lossless audio. */
        fun losslessSupported(): Boolean = ZenVault.openOrNull("it.filter.songs") != null

        /**
         * One-line diagnostic appended to the "no audio formats available" error:
         * distinguishes bot-gated empty streamingData from cipher-only formats
         * (decipher hook missing/failing) from URL-withheld responses, and
         * surfaces the stream-protection status + playability reason.
         */
        private fun diagnoseNoFormats(payload: org.json.JSONObject): String {
            val sd = payload.optJSONObject("streamingData")
                ?: return " (streamingData missing; likely bot-gated)"
            val adaptive = sd.optJSONArray("adaptiveFormats")
                ?: return " (adaptiveFormats missing)"
            if (adaptive.length() == 0) return " (adaptiveFormats empty)"
            var audio = 0
            var cipherOnly = 0
            var noUrl = 0
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                if (!f.optString("mimeType").startsWith("audio/")) continue
                audio++
                val hasUrl = f.optString("url").isNotEmpty()
                val hasCipher = f.optString("signatureCipher").isNotEmpty()
                when {
                    hasUrl -> {}
                    hasCipher -> cipherOnly++
                    else -> noUrl++
                }
            }
            val sps = sd.optJSONObject("streamProtectionStatus")?.opt("status")?.toString()
            val reason = payload.optJSONObject("playabilityStatus")
                ?.optString("reason").orEmpty()
            return " (adaptive=${adaptive.length()} audio=$audio cipherOnly=$cipherOnly otherNoUrl=$noUrl" +
                (sps?.let { " sps=$it" } ?: "") +
                (reason.takeIf { it.isNotEmpty() }?.let { " reason=$it" } ?: "") + ")"
        }
    }
}

/**
 * Recovers the real stream URL from a `signatureCipher` format blob. Host apps
 * (or the umbrella engine) back this with the vendored player-cipher engine.
 */
fun interface SignatureDecipher {
    suspend fun decipher(signatureCipher: String, videoId: String): String?
}

internal fun SignatureDecipher.asLambda(): suspend (String, String) -> String? =
    { cipher, videoId -> decipher(cipher, videoId) }

/**
 * Internal extraction result.
 *
 * ENGINE-INTERNAL — [mediaUrl] must never be persisted, logged
 * ([com.zenmusic.core.log.ZenLog] scrubs it), or surfaced through the safe
 * facade ([com.zenmusic.resolver.ZenResolver] + `zen-datasource`). It exists
 * only so the resolver's broker can open transport channels. Apps embedding
 * the extractor directly accept responsibility for handling it with the same
 * discretion.
 */
data class StreamPlan(
    val mediaId: String,
    val mediaUrl: String,
    val format: com.zenmusic.core.model.ZenFormat,
    val isLive: Boolean,
)
