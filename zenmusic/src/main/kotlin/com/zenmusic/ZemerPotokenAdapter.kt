package com.zenmusic

import com.zemer.cipher.CipherDeobfuscator
import com.zemer.cipher.potoken.PoTokenGenerator
import com.zenmusic.core.ZenTokenException
import com.zenmusic.core.log.ZenLog
import com.zenmusic.core.vault.ZenVault
import com.zenmusic.extractor.StreamUrlEnhancer
import com.zenmusic.potoken.PoToken
import com.zenmusic.potoken.PoTokenType
import com.zenmusic.potoken.PotokenCoordinator
import com.zenmusic.potoken.PotokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PotokenProvider] backed by the vendored zemer-cipher library
 * (WebView-sandboxed BotGuard with remote self-healing player configs).
 *
 * Token binding follows the live-CDN-verified scheme:
 *  - session token (visitorData-bound) → sent in the `player` request
 *  - per-video token (media-id-bound)  → appended to stream URLs as `&pot=`
 *
 * The adapter keeps a single [PoTokenGenerator]; it manages its own WebView
 * lifecycle (warm session reuse, renderer-death recovery, 8s mint timeout).
 */
internal class ZemerPotokenAdapter : PotokenProvider {

    private val generator = PoTokenGenerator()

    @Volatile
    private var cachedSession: Pair<String, String>? = null

    override suspend fun sessionToken(sessionIdentifier: String): PoToken {
        cachedSession?.takeIf { it.first == sessionIdentifier }?.let { (_, value) ->
            return PoToken(value, PoTokenType.SESSION, sessionIdentifier, expiresAt())
        }
        val result = mint(SESSION_PROBE_MEDIA, sessionIdentifier)
            ?: throw ZenTokenException("zemer poToken unavailable (WebView missing or mint failed)")
        cachedSession = sessionIdentifier to result.playerRequestPoToken
        return PoToken(
            result.playerRequestPoToken,
            PoTokenType.SESSION,
            sessionIdentifier,
            expiresAt(),
        )
    }

    override suspend fun contentToken(mediaId: String, sessionIdentifier: String): PoToken {
        val result = mint(mediaId, sessionIdentifier)
            ?: throw ZenTokenException("zemer poToken unavailable (WebView missing or mint failed)")
        return PoToken(
            result.streamingDataPoToken,
            PoTokenType.CONTENT,
            mediaId,
            expiresAt(),
        )
    }

    /** zemer's generator is blocking (runBlocking internally) — hop to IO. */
    private suspend fun mint(mediaId: String, sessionId: String) =
        withContext(Dispatchers.IO) {
            try {
                generator.getWebClientPoToken(mediaId, sessionId)
            } catch (t: Throwable) {
                ZenLog.w("zemer potoken mint failed: ${t.message}", t)
                null
            }
        }

    private fun expiresAt(): Long = System.currentTimeMillis() + TOKEN_TTL_MS

    companion object {
        /** Fixed embeddable probe id — the session token binds to the session
         * identifier, never to this id; it only warms the minting session. */
        private const val SESSION_PROBE_MEDIA = "jNQXAC9IVRw"

        /** Conservative wrapper TTL; zemer rotates its WebView independently. */
        private const val TOKEN_TTL_MS = 3L * 60 * 60 * 1000
    }
}

/**
 * Appends the per-video PO token (`&pot=`) and applies the `n`-parameter
 * transform to googlevideo stream URLs. This is what makes WEB_REMIX streams
 * serve beyond the first ~1 MiB without CDN throttling in 2026.
 */
internal class ZemerStreamEnhancer(
    private val potoken: PotokenCoordinator,
) : StreamUrlEnhancer {

    override suspend fun enhance(mediaId: String, sessionId: String, url: String): String? {
        if (!url.contains(ZenVault.open("gv.host"))) return null
        val pot = potoken.contentTokenOrNull(mediaId, sessionId)?.value ?: return null
        val transformed = CipherDeobfuscator.transformNParamInUrl(url)
        val sep = if (transformed.contains('?')) '&' else '?'
        return "$transformed${sep}pot=$pot"
    }
}
