package com.zenmusic.potoken

/** Kind of Proof-of-Origin token. */
enum class PoTokenType {
    /** Bound to the session identifier (visitor data). Long-lived, reused. */
    SESSION,

    /** Bound to a single media id. Minted per playback request. */
    CONTENT,
}

/**
 * A minted Proof-of-Origin token. Values are opaque base64url strings —
 * they are never logged ([com.zenmusic.core.log.ZenLog] redacts them).
 */
class PoToken(
    val value: String,
    val type: PoTokenType,
    /** Identifier the token is bound to (session id or media id). */
    val binding: String,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    override fun toString(): String =
        "PoToken(type=$type, binding=$binding, expiresAtMs=$expiresAtMs)"
}
