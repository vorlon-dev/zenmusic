package com.zenmusic.potoken

import com.zenmusic.core.ZenTokenException
import com.zenmusic.core.log.ZenLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Facade the rest of the engine talks to. Adds:
 *  - token caching (session + per-media content tokens) — works even with
 *    stateless providers,
 *  - single-flight minting (no duplicate concurrent VM runs),
 *  - global kill switch ([enabled]) wired from [com.zenmusic.core.ZenConfig],
 *  - failure backoff (minting is expensive; don't hammer a failing endpoint),
 *  - observability counters that never leak token material.
 */
class PotokenCoordinator(
    private val provider: PotokenProvider,
    private val enabled: Boolean = true,
) {

    private val sessionMutex = Mutex()
    private val contentMutex = Mutex()

    private var sessionCached: PoToken? = null
    private val contentCached = HashMap<String, PoToken>()

    @Volatile
    private var backoffUntilMs: Long = 0L

    /** True while minting is in failure backoff. */
    val isCoolingDown: Boolean
        get() = System.currentTimeMillis() < backoffUntilMs

    /** Last mint outcome (never contains token material). */
    @Volatile
    var stats: PotokenStats = PotokenStats()
        private set

    /**
     * Returns the session token for [sessionIdentifier], or null when minting
     * is disabled / cooling down / failed. Resolution continues without a
     * token where the upstream permits it.
     *
     * The cache is keyed by the binding: YouTube rotates `visitorData`
     * mid-session (the rotation is echoed in `responseContext`), and a token
     * minted against a previous visitor must never be served for the new one —
     * the server then answers OK but silently withholds every stream format.
     */
    suspend fun sessionTokenOrNull(sessionIdentifier: String): PoToken? {
        if (!enabled || isCoolingDown) return null
        return try {
            sessionMutex.withLock {
                sessionCached?.takeIf { it.binding == sessionIdentifier && !it.isExpired() }
                    ?: provider.sessionToken(sessionIdentifier).also { sessionCached = it }
            }.also {
                stats = stats.copy(sessionMints = stats.sessionMints + 1, lastError = null)
            }
        } catch (t: Throwable) {
            noteFailure(t)
            null
        }
    }

    /**
     * Returns a content-bound token for [mediaId], or null under the same
     * conditions as [sessionTokenOrNull].
     */
    suspend fun contentTokenOrNull(mediaId: String, sessionIdentifier: String): PoToken? {
        if (!enabled || isCoolingDown) return null
        return try {
            contentMutex.withLock {
                contentCached[mediaId]?.takeIf { !it.isExpired() }
                    ?: provider.contentToken(mediaId, sessionIdentifier)
                        .also { contentCached[mediaId] = it }
            }.also {
                stats = stats.copy(contentMints = stats.contentMints + 1, lastError = null)
            }
        } catch (t: Throwable) {
            noteFailure(t)
            null
        }
    }

    /** Hard failure path: throws instead of degrading. */
    suspend fun sessionToken(sessionIdentifier: String): PoToken {
        return sessionTokenOrNull(sessionIdentifier)
            ?: throw ZenTokenException(stats.lastError ?: "potoken unavailable (disabled/cooling)")
    }

    /** Drops all cached tokens (e.g. after a network change). */
    fun clearCache() {
        sessionCached = null
        contentCached.clear()
    }

    /** Releases the underlying provider (WebView / JS runtime). */
    fun close() {
        clearCache()
        runCatching { provider.close() }
    }

    private fun noteFailure(t: Throwable) {
        backoffUntilMs = System.currentTimeMillis() + BACKOFF_MS
        stats = stats.copy(failures = stats.failures + 1, lastError = t.message ?: t.javaClass.simpleName)
        ZenLog.w("potoken coordinator failure: ${t.message}", t)
    }

    companion object {
        private const val BACKOFF_MS = 90_000L
    }
}

/** Counters for monitoring; safe to log (no token material). */
data class PotokenStats(
    val sessionMints: Long = 0,
    val contentMints: Long = 0,
    val failures: Long = 0,
    val lastError: String? = null,
)
