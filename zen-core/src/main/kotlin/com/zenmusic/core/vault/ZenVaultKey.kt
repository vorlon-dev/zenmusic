package com.zenmusic.core.vault

import java.security.MessageDigest

/**
 * Split-key material for [ZenVault].
 *
 * The three fragments alone are meaningless; the AES-256 key exists only at
 * runtime as `SHA-256(A xor B xor C || domain)`. No single class holds enough
 * information to decrypt the vault, which defeats naive string-based /
 * AI-assisted static analysis of the sealed secrets.
 *
 * Rotate all three fragments together with [ZenVaultConsts] when re-sealing
 * (see scripts/gen_zenvault.py shipped with the source).
 */
internal object ZenVaultKey {

    // 32-byte fragment A (hex)
    private const val A =
        "9015ee5038b4ee94260756907366d31bb2a8e406564342d29d97e9cb2f70cec9"

    // 32-byte fragment B (hex)
    private const val B =
        "2a41beba731611eccf46a38bc0e0a785f0d3e34e09cd77afe8e85a72929b70b4"

    // 32-byte fragment C (hex)
    private const val C =
        "667faa8275fbebc3ec40ec5aa233a6875db9e4fe8401c6f73abe7a91ec22c54b"

    private const val DOMAIN = "zenmusic.vault.v1"

    @Volatile
    private var cached: ByteArray? = null

    /** Derives (and caches) the runtime AES-256 key. */
    fun derive(): ByteArray {
        cached?.let { return it.copyOf() }
        val a = hexToBytes(A)
        val b = hexToBytes(B)
        val c = hexToBytes(C)
        val combined = ByteArray(a.size)
        for (i in a.indices) {
            combined[i] = (a[i].toInt() xor b[i].toInt() xor c[i].toInt()).toByte()
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(combined)
        md.update(DOMAIN.toByteArray(Charsets.US_ASCII))
        val key = md.digest()
        cached = key.copyOf()
        return key
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 64) { "vault fragment corrupted" }
        return ByteArray(32) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) +
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
