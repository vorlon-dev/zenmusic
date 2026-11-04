package com.zenmusic.resolver

import com.zenmusic.core.ZenResolutionException
import com.zenmusic.core.model.ZenStream
import com.zenmusic.core.vault.ZenVault
import com.zenmusic.extractor.StreamPlan
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Handle → media-location store. Entries hold the real URL XOR-obfuscated at
 * rest and decode only for the instant a transport channel opens; handles are
 * random opaque tokens, so neither dumps of this map nor [ZenStream] objects
 * seen by the app reveal the upstream platform.
 */
internal class StreamBroker {

    private class Entry(
        val key: ByteArray,
        val obfuscatedUrl: ByteArray,
        val format: com.zenmusic.core.model.ZenFormat,
        val isLive: Boolean,
        val expiresAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val rnd = SecureRandom()

    /** Registers a plan and returns its public stream descriptor. */
    fun register(plan: StreamPlan): ZenStream {
        val urlBytes = plan.mediaUrl.toByteArray(Charsets.UTF_8)
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val obf = ByteArray(urlBytes.size) { i -> (urlBytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val handle = newHandle()
        val expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS
        entries[handle] = Entry(key, obf, plan.format, plan.isLive, expiresAt)
        return ZenStream(
            uri = com.zenmusic.core.uri.ZenUri.mint(plan.mediaId),
            format = plan.format,
            isLive = plan.isLive,
            handle = handle,
            expiresAtMs = expiresAt,
        )
    }

    /** Decodes the real URL for [handle] (engine-internal use only). */
    private fun urlOf(handle: String): String? {
        val e = entries[handle] ?: return null
        if (System.currentTimeMillis() >= e.expiresAtMs) {
            entries.remove(handle)
            return null
        }
        val plain = ByteArray(e.obfuscatedUrl.size) { i ->
            (e.obfuscatedUrl[i].toInt() xor e.key[i % e.key.size].toInt()).toByte()
        }
        return String(plain, Charsets.UTF_8)
    }

    /** Opens a transport channel at [position]; null when handle is stale. */
    fun channel(handle: String, position: Long): MediaChannel? {
        val url = urlOf(handle) ?: return null
        return try {
            MediaChannel(ZenTransport.http, url, ZenVault.open("ua.zen"), position)
        } catch (_: Throwable) {
            null
        }
    }

    fun purge(): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        entries.entries.removeIf { it.value.expiresAtMs <= now }.also { if (it) removed++ }
        return removed
    }

    fun clear() = entries.clear()

    private fun newHandle(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString { repeat(32) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    companion object {
        private const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Neutral transport configuration for engine-issued media requests.
 * A custom non-browser UA and no Referer: requests look like nothing but the
 * engine itself, and platform-specific fingerprints stay out of the picture.
 */
internal object ZenTransport {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build()
    }
}

/**
 * Streaming transport channel over a brokered media location.
 * This is what the Media3 `DataSource` in `zen-datasource` drives.
 * The real URL stays inside; only bytes flow out.
 *
 * NOT thread-safe: Media3 drives a single DataSource per stream.
 */
class MediaChannel internal constructor(
    private val http: OkHttpClient,
    private val mediaLocation: String,
    private val userAgent: String,
    initialPosition: Long,
) : AutoCloseable {

    var contentLength: Long = -1L
        private set
    var position: Long = initialPosition
        private set

    private var response: Response? = null
    private var stream: InputStream? = null

    init {
        openAt(initialPosition)
    }

    private fun openAt(pos: Long) {
        val req = Request.Builder()
            .url(mediaLocation)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            // deliberately: no Referer, no Origin, no cookies
            .apply { if (pos > 0) header("Range", "bytes=$pos-") }
            .get()
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (t: Throwable) {
            throw ZenResolutionException("media transport failed", t)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw ZenResolutionException("media transport http $code")
        }
        contentLength = parseTotalLength(resp.header("Content-Range"), resp.header("Content-Length"), pos)
        response = resp
        stream = resp.body?.byteStream()
        position = pos
    }

    /** Reads up to [len] bytes into [sink] at [offset]; -1 at end of stream. */
    @Throws(IOException::class)
    fun read(sink: ByteArray, offset: Int, len: Int): Int {
        val n = stream?.read(sink, offset, len) ?: -1
        if (n > 0) position += n
        return n
    }

    /** Re-opens the channel at an absolute byte position (Media3 seek). */
    fun seekTo(newPosition: Long) {
        if (newPosition == position && stream != null) return
        closeQuietly()
        openAt(newPosition)
    }

    override fun close() = closeQuietly()

    private fun closeQuietly() {
        try {
            stream?.close()
        } catch (_: Throwable) {
        }
        try {
            response?.close()
        } catch (_: Throwable) {
        }
        stream = null
        response = null
    }

    private fun parseTotalLength(contentRange: String?, contentLength: String?, pos: Long): Long {
        contentRange?.let {
            val idx = it.lastIndexOf('/')
            if (idx >= 0 && idx < it.length - 1) {
                it.substring(idx + 1).toLongOrNull()?.let { total -> return if (total > 0) total else -1L }
            }
        }
        contentLength?.toLongOrNull()?.let { return pos + it }
        return -1L
    }
}
