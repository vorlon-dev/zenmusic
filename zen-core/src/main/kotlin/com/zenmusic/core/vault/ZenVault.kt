package com.zenmusic.core.vault

import com.zenmusic.core.ZenVaultException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM string vault.
 *
 * Every secret the engine needs (endpoint hosts, client identities, attestation
 * keys, decoy constants) is stored as base64(iv ‖ ciphertext ‖ tag) ciphertext.
 * Plaintext exists only transiently at runtime after [open]; it never appears
 * in the shipped artifact, so static analysis / AI-assisted RE tooling has no
 * endpoints or platform strings to harvest.
 *
 * GCM authentication also means a tampered ciphertext fails loudly
 * ([ZenVaultException]) instead of decrypting to attacker-chosen garbage.
 */
object ZenVault {

    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val runtimeSecrets = ConcurrentHashMap<String, String>()
    private val cache = ConcurrentHashMap<String, String>()

    /** Registers a runtime-sealed secret (e.g. supplied by the host app). */
    fun register(id: String, sealedBase64: String) {
        runtimeSecrets[id] = sealedBase64
        cache.remove(id)
    }

    /**
     * Seals [plaintext] with the runtime key and returns the sealed blob.
     * Useful for persisting engine state without leaking it to backups.
     */
    fun seal(plaintext: String): String {
        val key = ZenVaultKey.derive()
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        iv.copyInto(out)
        ct.copyInto(out, iv.size)
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    /** Decrypts a built-in or runtime-registered secret by id. */
    fun open(id: String): String {
        cache[id]?.let { return it }
        val sealed = ZenVaultConsts.SECRETS[id] ?: runtimeSecrets[id]
            ?: throw ZenVaultException("secret '$id' not sealed")
        val plain = unseal(sealed, id)
        cache[id] = plain
        return plain
    }

    /** Like [open] but returns null instead of throwing. */
    fun openOrNull(id: String): String? = try {
        open(id)
    } catch (_: Throwable) {
        null
    }

    /** Decrypts an arbitrary sealed blob produced by [seal] or the build script. */
    fun unseal(sealedBase64: String, contextId: String = "dynamic"): String {
        return try {
            val raw = java.util.Base64.getDecoder().decode(sealedBase64)
            require(raw.size > IV_LEN + 16) { "sealed blob too short" }
            val iv = raw.copyOfRange(0, IV_LEN)
            val body = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(ZenVaultKey.derive(), "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            throw ZenVaultException("vault integrity failure ($contextId)", t)
        }
    }

    /** Best-effort scrub of the plaintext cache ( defense-in-depth). */
    fun purgeCache() = cache.clear()
}
