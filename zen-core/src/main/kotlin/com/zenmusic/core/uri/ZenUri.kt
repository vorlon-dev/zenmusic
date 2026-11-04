package com.zenmusic.core.uri

import com.zenmusic.core.vault.ZenVault

/**
 * The disguised stream URI scheme.
 *
 * Format (pseudo-host decoy style):
 *   stream  :  zen://&lt;media-id&gt;.&lt;decoy-suffix&gt;
 *   artwork :  zen://a/&lt;media-id&gt;.&lt;decoy-suffix&gt;
 *
 * The suffix (a fake, platform-sounding pseudo-host) is vault-sealed so the
 * shipped artifact contains no plaintext that points at any real platform.
 * The URI never encodes the actual upstream engine — resolution happens
 * inside the engine's broker.
 */
object ZenUri {

    const val SCHEME = "zen"

    enum class Kind { STREAM, ARTWORK, BROWSE }

    data class Parsed(val kind: Kind, val id: String)

    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{8,24}$")

    /** Decoy pseudo-host suffix, e.g. `google.video` (from the vault). */
    fun decoySuffix(): String = ZenVault.open("decoy.suffix")

    /** Mints a disguised stream URI for an internal media id. */
    fun mint(mediaId: String): String = "$SCHEME://$mediaId.${decoySuffix()}"

    /** Mints a disguised artwork URI for an internal media id. */
    fun mintArtwork(mediaId: String): String = "$SCHEME://a/$mediaId.${decoySuffix()}"

    /** Mints a disguised browse URI (albums / artists), not directly playable. */
    fun mintBrowse(browseId: String): String = "$SCHEME://b/$browseId.${decoySuffix()}"

    /** True when [uri] uses the zen scheme. */
    fun isZen(uri: String): Boolean = uri.startsWith("$SCHEME://", ignoreCase = true)

    /**
     * Parses a zen:// URI.
     * @return [Parsed] or null when the URI is not a valid zen URI.
     */
    fun parse(uri: String): Parsed? {
        if (!isZen(uri)) return null
        var rest = uri.substring(SCHEME.length + 3)
        val kind = when {
            rest.startsWith("a/") -> { rest = rest.substring(2); Kind.ARTWORK }
            rest.startsWith("b/") -> { rest = rest.substring(2); Kind.BROWSE }
            else -> Kind.STREAM
        }
        // strip query/fragment noise attackers might append
        val clean = rest.substringBefore('#').substringBefore('?')
        val dot = clean.indexOf('.')
        if (dot <= 0) return null
        val id = clean.substring(0, dot)
        val tail = clean.substring(dot + 1)
        if (!ID_REGEX.matches(id)) return null
        if (tail != decoySuffix()) return null
        return Parsed(kind, id)
    }

    /** Strict validation used before any resolution attempt. */
    fun validate(uri: String): Parsed =
        parse(uri) ?: throw IllegalArgumentException("invalid zen uri")

    /**
     * Decoy generator used by the fake-stream shower — mints a syntactically
     * valid zen URI for a random non-existent media id.
     */
    fun mintDecoy(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        val rnd = java.security.SecureRandom()
        val id = buildString {
            repeat(11) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
        return mint(id)
    }
}
