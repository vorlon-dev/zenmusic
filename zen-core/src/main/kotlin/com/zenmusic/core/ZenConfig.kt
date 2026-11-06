package com.zenmusic.core

import com.zenmusic.core.model.ZenQuality

/**
 * Immutable engine configuration. Everything has a sane default so an app can
 * start with `ZenConfig()` and tune later.
 */
data class ZenConfig(
    /** Geo parameter (content region) used by the extraction engine. */
    val gl: String = "US",
    /** Language parameter used by the extraction engine. */
    val hl: String = "en",
    /** Preferred loudness tier when resolving streams (falls back automatically). */
    val preferredQuality: ZenQuality = ZenQuality.HIGH,
    /** Master switch for PO token minting. Disabling breaks stream URLs on enforced clients. */
    val potokenEnabled: Boolean = true,
    /**
     * Mint PO tokens through the vendored zemer-cipher WebView engine
     * (BotGuard + remote self-healing configs — recommended on Android).
     * When false, or when the WebView is unavailable, the engine falls back
     * to the built-in Rhino minter (JVM embedding).
     */
    val potokenWebView: Boolean = true,
    /** Mint a content-bound token per video in addition to the session token. */
    val contentPoToken: Boolean = true,
    /** Enable the fake-stream shower (decoy responses under tamper / analysis). */
    val decoyShower: Boolean = true,
    /** Verbose internal logging (NEVER enabled implicitly; scrubbed even when on). */
    val logDebug: Boolean = false,
    /** Optional pinned visitor data (advanced; leave null to let the engine manage sessions). */
    val visitorData: String? = null,
    /** Requested artwork width in px for disguised artwork URIs. */
    val artworkWidth: Int = 544,
    /**
     * Warm the whole playback chain in the background the moment
     * [com.zenmusic.ZenMusic.initialize] returns — session visitor, PO-token
     * WebView mint, player-JS cipher. First playback then resolves in a
     * single request instead of a cold-start chain (recommended).
     */
    val warmupOnStart: Boolean = true,
    /** How long a fetched player response may be re-used for repeat plays (seconds). */
    val playerCacheTtlSeconds: Int = 20 * 60,
)
