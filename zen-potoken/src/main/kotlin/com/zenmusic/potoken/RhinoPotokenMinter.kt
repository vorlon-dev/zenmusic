package com.zenmusic.potoken

import com.zenmusic.core.ZenTokenException
import com.zenmusic.core.log.ZenLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Default [PotokenProvider]: BotGuard attestation executed in a sandboxed
 * Rhino runtime, with in-memory caching per binding.
 *
 * Token lifetimes:
 *  - session tokens are refreshed at 80% of the reported TTL;
 *  - content tokens are cached per media id for 60% of the reported TTL
 *    (a fresh one is minted for the next playback of the same media).
 */
class RhinoPotokenMinter(
    http: OkHttpClient,
    gl: String = "US",
    hl: String = "en",
) : PotokenProvider {

    private val bot = BotGuardClient(http, gl, hl)
    private val lock = Any()

    @Volatile
    private var sessionCached: PoToken? = null
    private val contentCache = HashMap<String, PoToken>()

    override val isColdStartCapable: Boolean
        get() = sessionCached != null

    override suspend fun sessionToken(sessionIdentifier: String): PoToken {
        synchronized(lock) { sessionCached?.takeIf { !it.isExpired() } }?.let { return it }
        val fresh = mintInternal(sessionIdentifier, PoTokenType.SESSION)
        synchronized(lock) { sessionCached = fresh }
        return fresh
    }

    override suspend fun contentToken(mediaId: String, sessionIdentifier: String): PoToken {
        synchronized(this) { contentCache[mediaId]?.takeIf { !it.isExpired() } }?.let { return it }
        val fresh = mintInternal(mediaId, PoTokenType.CONTENT)
        synchronized(this) { contentCache[mediaId] = fresh }
        return fresh
    }

    private suspend fun mintInternal(identifier: String, type: PoTokenType): PoToken {
        val now = System.currentTimeMillis()
        return try {
            val challenge = bot.fetchChallenge()
            val minted = bot.mint(challenge, identifier)
            val ttlMs = TimeUnit.SECONDS.toMillis(minted.ttlSeconds)
            PoToken(
                value = minted.token,
                type = type,
                binding = identifier,
                expiresAtMs = now + ttlMs,
            )
        } catch (e: ZenTokenException) {
            ZenLog.w("potoken mint failed (${e.message})", e.cause)
            throw e
        }
    }

    fun clearCache() {
        synchronized(lock) { sessionCached = null }
        synchronized(this) { contentCache.clear() }
    }
}
