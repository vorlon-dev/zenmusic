package com.zenmusic.extractor.innertube

import com.zenmusic.core.ZenExtractionException
import com.zenmusic.core.log.ZenLog
import com.zenmusic.core.vault.ZenVault
import com.zenmusic.potoken.PoToken
import com.zenmusic.potoken.PotokenCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal InnerTube (YouTube Music) client.
 *
 * Client identity chain — every identity, version, and host is vault-sealed.
 * 2026-11 refresh aligned with the zemer-app device-verified request shape:
 *
 *  1. `WEB_REMIX` — session-bound PO token in `serviceIntegrityDimensions`,
 *     `signatureTimestamp` in `contentPlaybackContext`, Firefox web UA,
 *     keyless URL, `X-Goog-Api-Format-Version: 1`, `X-Origin`.
 *  2. `VISIONOS` 1.02 / 1.03 — keyless AND tokenless; serves plain stream
 *     URLs without cipher (the only proven whole-song keyless identity).
 *  3. `TVHTML5_SIMPLY` (clientId 75) — web-style ciphered URLs + PO token.
 *
 * Search additionally speaks plain `WEB` against the open video platform host
 * (keyless, visitor-less) so results cover uploads the music catalog lacks.
 *
 * The IOS and ANDROID_VR families are deliberately absent: both are proven
 * dead for full-song delivery (SABR-only / 403-wall past the CDN's 1-MiB
 * free window). A response that comes back OK but with every stream URL
 * withheld (stale token, visitor rotation, gating) does NOT win the chain —
 * extraction falls through to the next identity.
 */
internal class ZenInnertube(
    private val http: OkHttpClient,
    private val potoken: PotokenCoordinator,
    gl: String,
    hl: String,
    pinnedVisitorData: String?,
    /** Current player signatureTimestamp (cipher's player config), nullable. */
    private val signatureTimestamp: (suspend () -> Int?)? = null,
) {

    internal data class ClientIdentity(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientIndex: String,
        val needsPoToken: Boolean,
        val useSignatureTimestamp: Boolean = false,
        /** Additional `context.client` fields (device identity etc.). */
        val extraClientFields: Map<String, String> = emptyMap(),
    )

    private val host: String = ZenVault.open("it.host")
    private val origin: String = ZenVault.open("it.origin")
    private val remix = ClientIdentity(
        name = "WEB_REMIX",
        version = ZenVault.open("it.ver.remix"),
        userAgent = ZenVault.open("ua.web"),
        clientIndex = "67",
        needsPoToken = true,
        useSignatureTimestamp = true,
    )
    private val visionos = ClientIdentity(
        name = "VISIONOS",
        version = ZenVault.open("it.ver.visionos"),
        userAgent = ZenVault.open("ua.visionos"),
        clientIndex = "101",
        needsPoToken = false,
        extraClientFields = mapOf(
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
        ),
    )
    private val visionos103 = visionos.copy(version = ZenVault.open("it.ver.visionos2"))
    private val tvs = ClientIdentity(
        name = "TVHTML5_SIMPLY",
        version = ZenVault.open("it.ver.tvs"),
        userAgent = ZenVault.open("ua.tvs"),
        clientIndex = "75",
        needsPoToken = true,
        useSignatureTimestamp = true,
    )
    private val web = ClientIdentity(
        name = "WEB",
        version = ZenVault.open("web.clientver"),
        userAgent = ZenVault.open("ua.web"),
        clientIndex = "1",
        needsPoToken = false,
    )

    private var gl: String = gl
    private var hl: String = hl

    @Volatile
    private var visitorData: String? = pinnedVisitorData
    private val visitorMutex = Mutex()

    /** Session identifier used for PO token binding. */
    suspend fun sessionIdentifier(): String = ensureVisitorData()

    private class Attempt(
        val response: JSONObject? = null,
        val error: Throwable? = null,
    )

    /**
     * One identity attempt with its mid-session visitor-rotation recovery.
     * Runs on its own coroutine; never throws.
     */
    private suspend fun attempt(
        mediaId: String,
        client: ClientIdentity,
        tag: String,
        visitor: String,
        sts: Int?,
        token: String?,
    ): Attempt {
        return try {
            var response = rawPlayer(mediaId, client, visitor, sts, token)
            if (isPlayable(response)) {
                // Mid-session visitor rotation: the server answers OK but
                // withholds every stream format when the token was minted
                // against a previous visitor (the rotation is echoed in
                // responseContext). Re-mint against the new visitor and
                // retry this client once instead of failing the resolve.
                val rotated = captureVisitorData(response)
                if (rotated && client.needsPoToken && !hasUsableAudio(response)) {
                    val newVisitor = visitorData
                    val fresh = newVisitor?.let { potoken.sessionTokenOrNull(it) }
                    if (newVisitor != null && fresh != null && fresh.value != token) {
                        ZenLog.d { "visitor rotated; re-minting session token" }
                        val retried = rawPlayer(mediaId, client, newVisitor, sts, fresh.value)
                        if (isPlayable(retried)) {
                            captureVisitorData(retried)
                            response = retried
                        }
                    }
                }
                return Attempt(response = response)
            }
            Attempt(
                error = ZenExtractionException(
                    "playability: " + response.optJSONObject("playabilityStatus")
                        ?.optString("reason").orEmpty().ifEmpty { "unplayable" },
                ),
            )
        } catch (t: Throwable) {
            ZenLog.w("player attempt failed via=$tag: ${t.message}", t)
            Attempt(error = t)
        }
    }

    /**
     * Executes a `player` request. The first three identities race in
     * parallel (one round trip instead of three) and are consumed in
     * priority order — WEB_REMIX wins when it delivers usable audio, else
     * the keyless VISIONOS identities, else TVHTML5_SIMPLY sequentially.
     * Returns the first response that actually carries usable audio
     * formats; if no identity does, the first playable response is returned
     * so the caller's diagnostic can describe exactly what was withheld.
     */
    suspend fun player(mediaId: String): JSONObject = withContext(Dispatchers.IO) {
        val visitor = ensureVisitorData()
        val sessionPot = potoken.sessionTokenOrNull(visitor)
        val sts = try {
            signatureTimestamp?.invoke()
        } catch (t: Throwable) {
            ZenLog.w("signatureTimestamp provider failed: ${t.message}")
            null
        }

        var firstPlayable: JSONObject? = null
        var lastError: Throwable? = null

        kotlinx.coroutines.coroutineScope {
            val raced = listOf(
                Triple(remix, "remix", sessionPot?.value),
                Triple(visionos, "visionos", null),
                Triple(visionos103, "visionos2", null),
            ).map { (client, tag, token) ->
                async { attempt(mediaId, client, tag, visitor, sts, token) }
            }
            try {
                for ((idx, deferred) in raced.withIndex()) {
                    val attemptResult = deferred.await()
                    val response = attemptResult.response
                    if (response != null && isPlayable(response)) {
                        if (firstPlayable == null) firstPlayable = response
                        if (hasUsableAudio(response)) {
                            ZenLog.d { "player ok via=${listOf("remix", "visionos", "visionos2")[idx]} media=${mediaId.take(4)}**" }
                            return@coroutineScope response
                        }
                        // OK but the server withheld the stream URLs — do not
                        // win the chain; let the next identity (already in
                        // flight) take it.
                        ZenLog.d { "player ok-but-withheld via=${listOf("remix", "visionos", "visionos2")[idx]}" }
                    } else {
                        lastError = lastError ?: attemptResult.error
                    }
                }
            } finally {
                raced.forEach { runCatching { it.cancel() } }
            }

            // Sequential last resort: web-style ciphered client.
            val tvsAttempt = attempt(mediaId, tvs, "tvs", visitor, sts, sessionPot?.value)
            val response = tvsAttempt.response
            if (response != null && isPlayable(response)) {
                firstPlayable = firstPlayable ?: response
                if (hasUsableAudio(response)) {
                    ZenLog.d { "player ok via=tvs media=${mediaId.take(4)}**" }
                    return@coroutineScope response
                }
            }
            lastError = lastError ?: tvsAttempt.error
            firstPlayable?.let { return@coroutineScope it }
            throw ZenExtractionException("stream extraction failed for media", lastError)
        }
    }

    /**
     * Executes a YTM `search` request with an optional filter param.
     * Visitor data is deliberately omitted (mirrors the verified web client):
     * a shared/stale anonymous visitor id can get flagged, which makes search
     * silently return an empty result set.
     */
    suspend fun search(query: String, filterParam: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("context", context(remix, null))
                .put("query", query)
            filterParam?.let { body.put("params", it) }
            post("search", remix, body, visitor = null)
        }

    /**
     * General video-platform search (plain `WEB` client, videos-only filter)
     * against the open host. Covers uploads outside the music catalog.
     * Visitor-less like [search]; parse with [Parsers.parseOpenVideoSearch].
     */
    suspend fun openVideoSearch(query: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("context", context(web, null))
            .put("query", query)
            .put("params", ZenVault.open("yt.filter.videos"))
        post(
            "search",
            web,
            body,
            visitor = null,
            hostOverride = ZenVault.open("yt.host"),
            originOverride = ZenVault.open("yt.origin"),
        )
    }

    /**
     * Executes a `next` request (watch-next / radio queue / related tab).
     * [params] carries the serialized watch endpoint of a specific tab
     * (e.g. the Related tab of the music watch-next response).
     */
    suspend fun next(videoId: String?, playlistId: String?, params: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val visitor = ensureVisitorData()
            val body = JSONObject().put("context", context(remix, visitor))
            videoId?.let { body.put("videoId", it) }
            playlistId?.let { body.put("playlistId", it) }
            params?.let { body.put("params", it) }
            post("next", remix, body, visitor).also { captureVisitorData(it) }
        }

    /**
     * Executes a `browse` request — the engine behind the music app's home
     * feed ([ZenHomeApi] uses `FEmusic_home`) and catalog pages.
     */
    suspend fun browse(browseId: String, params: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val visitor = ensureVisitorData()
            val body = JSONObject()
                .put("context", context(remix, visitor))
                .put("browseId", browseId)
            params?.let { body.put("params", it) }
            post("browse", remix, body, visitor).also { captureVisitorData(it) }
        }

    private suspend fun rawPlayer(
        mediaId: String,
        client: ClientIdentity,
        visitor: String,
        signatureTimestamp: Int?,
        poToken: String?,
    ): JSONObject {
        val body = JSONObject()
            .put("context", context(client, visitor))
            .put("videoId", mediaId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (client.useSignatureTimestamp && signatureTimestamp != null) {
            body.put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("signatureTimestamp", signatureTimestamp),
                ),
            )
        }
        if (client.needsPoToken && poToken != null) {
            body.put(
                "serviceIntegrityDimensions",
                JSONObject().put("poToken", poToken),
            )
        }
        return post("player", client, body, visitor)
    }

    private fun context(client: ClientIdentity, visitor: String?): JSONObject {
        val clientJson = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", hl)
            .put("gl", gl)
        visitor?.let { clientJson.put("visitorData", it) }
        for ((k, v) in client.extraClientFields) clientJson.put(k, v)
        return JSONObject().put("client", clientJson)
    }

    private fun post(
        endpoint: String,
        client: ClientIdentity,
        body: JSONObject,
        visitor: String? = null,
        hostOverride: String? = null,
        originOverride: String? = null,
    ): JSONObject {
        // Keyless — the verified 2026 web shape carries no API key parameter.
        val host = hostOverride ?: this.host
        val origin = originOverride ?: this.origin
        val url = "https://$host${ZenVault.open("it.path.prefix")}$endpoint?prettyPrint=false"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", client.userAgent)
            .header(ZenVault.open("hdr.client.name"), client.clientIndex)
            .header(ZenVault.open("hdr.client.ver"), client.version)
            .header("X-Goog-Api-Format-Version", "1")
            // Session correlation: for web clients the server binds the PO
            // token (minted against visitorData) to the request through this
            // header — body-only visitorData leaves an uncorrelated session
            // and formats get silently withheld.
            .apply { visitor?.takeIf { it.isNotEmpty() }?.let { header("X-Goog-Visitor-Id", it) } }
            .header("X-Origin", origin)
            .header("Referer", "$origin/")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ZenExtractionException("$endpoint http ${resp.code}")
            val text = resp.body?.string().orEmpty()
            return JSONObject(text)
        }
    }

    private fun isPlayable(response: JSONObject): Boolean {
        val status = response.optJSONObject("playabilityStatus")?.optString("status")
        return status == ZenVault.open("status.ok")
    }

    /** True when at least one audio format carries a usable stream URL. */
    private fun hasUsableAudio(response: JSONObject): Boolean {
        val adaptive = response.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: return false
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            if (!f.optString("mimeType").startsWith("audio/")) continue
            if (f.optString("url").isNotEmpty() || f.optString("signatureCipher").isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private suspend fun ensureVisitorData(): String {
        visitorData?.let { return it }
        visitorMutex.withLock {
            visitorData?.let { return it }
            // Cold-start probe chain. A bare player request is bot-gated in 2026
            // but still echoes responseContext.visitorData; if it ever stops, the
            // `next` endpoint is the fallback. A REAL visitor id is mandatory:
            // PO tokens are minted bound to it, and a fabricated id would poison
            // every session token.
            for (probe in listOf(
                "player" to JSONObject()
                    .put("context", context(remix, null))
                    .put("videoId", COLD_START_MEDIA)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true),
                "next" to JSONObject().put("context", context(remix, null)),
            )) {
                try {
                    val resp = post(probe.first, remix, probe.second)
                    val vd = resp.optJSONObject("responseContext")?.optString("visitorData")
                    if (!vd.isNullOrEmpty()) {
                        visitorData = vd
                        return vd
                    }
                } catch (t: Throwable) {
                    ZenLog.w("visitor probe ${probe.first} failed: ${t.message}")
                }
            }
            throw ZenExtractionException("no visitor data obtainable from InnerTube probes")
        }
    }

    private fun captureVisitorData(response: JSONObject): Boolean {
        val vd = response.optJSONObject("responseContext")?.optString("visitorData")
        if (!vd.isNullOrEmpty() && visitorData != vd) {
            visitorData = vd
            return true
        }
        return false
    }

    companion object {
        /** Stable, embeddable public media id used only to bootstrap visitor data. */
        private const val COLD_START_MEDIA = "jNQXAC9IVRw"
    }
}
