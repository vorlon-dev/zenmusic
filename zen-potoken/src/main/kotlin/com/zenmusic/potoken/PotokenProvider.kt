package com.zenmusic.potoken

import com.zenmusic.core.ZenTokenException

/**
 * Source of Proof-of-Origin tokens. The engine ships one implementation
 * ([RhinoPotokenMinter]); hosts may plug their own (e.g. a remote attestation
 * service) via [com.zenmusic.core.ZenConfig]-level wiring.
 */
interface PotokenProvider {
    /** True when the provider can mint immediately without network warm-up. */
    val isColdStartCapable: Boolean get() = false

    /**
     * Session-bound token (bound to [sessionIdentifier], typically visitor data).
     * Implementations should cache and refresh proactively.
     */
    @Throws(ZenTokenException::class)
    suspend fun sessionToken(sessionIdentifier: String): PoToken

    /**
     * Content-bound token for one media id.
     */
    @Throws(ZenTokenException::class)
    suspend fun contentToken(mediaId: String, sessionIdentifier: String): PoToken

    /** Releases JS runtime / network resources. */
    fun close() {}
}
