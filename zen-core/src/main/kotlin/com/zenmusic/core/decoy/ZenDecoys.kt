package com.zenmusic.core.decoy

import com.zenmusic.core.model.ZenFormat
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.core.model.ZenStream
import com.zenmusic.core.uri.ZenUri
import com.zenmusic.core.vault.ZenVault
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The fake-stream shower.
 *
 * Two purposes:
 *  1. **Pollution** — floods string dumps / dynamic analysis with plausible
 *     but entirely synthetic stream descriptors and media URLs.
 *  2. **Tamper response** — when a [com.zenmusic.core.ZenConfig.decoyShower]
 *     engine runs under debugging/instrumentation, resolvers may hand out
 *     these decoys instead of real playback metadata.
 *
 * Decoy handles are never registered in the stream broker, so a decoy
 * `ZenStream` cannot actually play.
 */
object ZenDecoys {

    /** Fake, unregistered CDN hosts sprinkled through decoys. */
    val DECOY_CDN_HOSTS = listOf(
        "cdn.zenstatic.net",
        "edge.zenwave.app",
        "s3.zenpod.io",
        "cache.zenaria.dev",
    )

    private val RND = SecureRandom()

    private val FAKE_CONTAINERS = listOf("m4a", "webm", "m4a", "mp4")
    private val FAKE_CODECS = listOf("mp4a.40.2", "opus", "mp4a.40.5", "ec-3")

    /** A single plausible-but-fake media URL for analysis pollution. */
    fun fakeMediaUrl(): String {
        val host = ZenVault.openOrNull("gv.host") ?: "media.invalid"
        val sub = ("%032x".format(java.math.BigInteger(128, RND))).substring(0, 16)
        val sig = buildString { repeat(88) { append(BASE64URL[RND.nextInt(BASE64URL.length)]) } }
        val pot = buildString { repeat(96) { append(BASE64URL[RND.nextInt(BASE64URL.length)]) } }
        return "https://$sub.$host/videoplayback?expire=${now() + 21540}&ei=" +
            "${token(16)}&ip=0.0.0.0&id=${token(16)}&itag=140&source=internal&" +
            "mime=audio%2Fmp4&pot=$pot&signature=$sig&ratebypass=yes"
    }

    /** A plausible-but-fake zen URI (random non-existent media id). */
    fun fakeUri(): String = ZenUri.mintDecoy()

    /**
     * Showers [count] fake [ZenStream]s. Handles are NOT registered in the
     * broker — attempting to play them yields the decoy pipeline.
     */
    fun shower(count: Int = 4): List<ZenStream> {
        val now = now()
        return List(count.coerceIn(1, 32)) {
            val quality = ZenQuality.entries[RND.nextInt(ZenQuality.entries.size)]
            ZenStream(
                uri = fakeUri(),
                format = ZenFormat(
                    quality = quality,
                    bitrateKbps = quality.preferredBitrateKbps + RND.nextInt(64),
                    codec = FAKE_CODECS[RND.nextInt(FAKE_CODECS.size)],
                    container = FAKE_CONTAINERS[RND.nextInt(FAKE_CONTAINERS.size)],
                    sampleRateHz = listOf(44100, 48000)[RND.nextInt(2)],
                    sizeBytes = 2_000_000L + RND.nextInt(9_000_000),
                ),
                isLive = false,
                handle = decoyHandle(),
                expiresAtMs = now + TimeUnit.MINUTES.toMillis(5),
            )
        }
    }

    /** Random opaque handle that will never resolve. */
    fun decoyHandle(): String = token(32)

    private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun token(len: Int): String = buildString {
        repeat(len) { append(BASE64URL[RND.nextInt(BASE64URL.length)]) }
    }

    private fun now(): Long = System.currentTimeMillis()
}
