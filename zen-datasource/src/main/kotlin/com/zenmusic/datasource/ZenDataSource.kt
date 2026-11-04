package com.zenmusic.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSource.Factory
import com.zenmusic.core.log.ZenLog
import com.zenmusic.core.model.ZenStream
import com.zenmusic.core.uri.ZenUri
import com.zenmusic.resolver.MediaChannel
import com.zenmusic.resolver.ZenResolver
import java.io.IOException

/**
 * Media3 [DataSource] that plays `zen://` URIs.
 *
 * This is the boundary where the disguise becomes airtight:
 *  - the app hands ExoPlayer a zen:// pseudo-host URI;
 *  - [open] swaps it for a brokered transport channel *inside the engine*;
 *  - [getUri] keeps reporting the zen:// uri, so even player diagnostics,
 *    logs, and error reports show nothing but the decoy.
 *
 * Fast path: the uri fragment (`#zh=<handle>`, added by [ZenMediaItems])
 * carries the opaque broker handle, so a new open() needs no re-extraction.
 * Fallback: without a fragment the uri is resolved fresh.
 */
@OptIn(UnstableApi::class)
class ZenDataSource(private val resolver: ZenResolver) : BaseDataSource(/* isNetwork = */ true) {

    private var channel: MediaChannel? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var openedUri: String? = null
    private var artwork: ByteArray? = null
    private var artworkPos: Int = 0

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uriString = dataSpec.uri.toString()
        if (!ZenUri.isZen(uriString)) {
            throw IOException("ZenDataSource only opens zen:// uris")
        }
        // Artwork uris (zen://a/…) are served in-memory through the same
        // factory — Media3's DataSourceBitmapLoader (media notifications,
        // Android Auto) can then render them with zero extra plumbing.
        if (ZenUri.parse(uriString)?.kind == ZenUri.Kind.ARTWORK) {
            return openArtwork(dataSpec, uriString)
        }
        val handle = dataSpec.key ?: fragmentHandle(uriString)
        val position = dataSpec.position

        val ch = if (handle != null) {
            resolver.openChannel(handle, position)
                ?: reResolveAndOpen(uriString, position)
        } else {
            reResolveAndOpen(uriString, position)
        } ?: throw IOException("stream handle expired")

        channel = ch
        openedUri = uriString
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            ch.contentLength > 0 -> ch.contentLength - position
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        artwork?.let { art ->
            if (artworkPos >= art.size) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining = 0
                return C.RESULT_END_OF_INPUT
            }
            val n = minOf(length, art.size - artworkPos)
            System.arraycopy(art, artworkPos, buffer, offset, n)
            artworkPos += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
            bytesTransferred(n)
            return n
        }
        val ch = channel ?: throw IOException("open() not called")
        val n = ch.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): android.net.Uri? = openedUri?.let { android.net.Uri.parse(it) }

    override fun close() {
        try {
            channel?.close()
        } catch (t: Throwable) {
            ZenLog.w("channel close: ${t.message}")
        } finally {
            val wasOpen = openedUri != null
            channel = null
            openedUri = null
            bytesRemaining = C.LENGTH_UNSET.toLong()
            artwork = null
            artworkPos = 0
            if (wasOpen) transferEnded()
        }
    }

    /** Fetches the disguised artwork once and serves it honoring position/length. */
    private fun openArtwork(dataSpec: DataSpec, uriString: String): Long {
        val bytes = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { resolver.fetchArtwork(uriString) }
        } ?: throw IOException("artwork unavailable")
        val from = dataSpec.position.coerceIn(0, bytes.size.toLong()).toInt()
        val to = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            bytes.size
        } else {
            minOf(bytes.size.toLong(), dataSpec.position + dataSpec.length).toInt()
        }
        artwork = if (from == 0 && to == bytes.size) bytes else bytes.copyOfRange(from, to)
        artworkPos = 0
        openedUri = uriString
        bytesRemaining = artwork!!.size.toLong()
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Re-resolves by uri (fresh extraction) and opens a channel. */
    private fun reResolveAndOpen(uriString: String, position: Long): MediaChannel? {
        val stream = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { resolver.resolve(uriString) }
        } ?: return null
        return resolver.openChannel(stream.handle, position)
    }

    /** Extracts `#zh=<handle>` fragment, if present. */
    private fun fragmentHandle(uri: String): String? {
        val idx = uri.indexOf("#zh=")
        return if (idx >= 0) uri.substring(idx + 4).takeWhile { it != '&' } else null
    }
}

/**
 * Factory for apps that build their own ExoPlayer:
 * `DefaultMediaSourceFactory(zenDataSourceFactory)` passed to
 * `ExoPlayer.Builder(context).setMediaSourceFactory(...)`.
 */
@OptIn(UnstableApi::class)
class ZenDataSourceFactory(private val resolver: ZenResolver) : Factory {
    override fun createDataSource(): ZenDataSource = ZenDataSource(resolver)
}

/**
 * Builders that attach stream metadata to MediaItems without ever exposing a
 * real media URL. The zen uri fragment carries the opaque broker handle.
 */
@OptIn(UnstableApi::class)
object ZenMediaItems {

    /** Appends the opaque handle fragment to a zen stream uri. */
    fun withHandle(stream: ZenStream): String = "${stream.uri}#zh=${stream.handle}"

    /**
     * Builds a playable MediaItem with full metadata from the [ZenTrack] the
     * stream was resolved from — title, artist, album and the disguised
     * artwork uri. Notification / MediaController artwork then loads through
     * `DataSourceBitmapLoader(zen.dataSourceFactory)` (see the README).
     */
    fun from(stream: ZenStream, track: com.zenmusic.core.model.ZenTrack?): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(withHandle(stream))
            .setMimeType(mimeFor(stream.format.container))
        if (track != null) {
            val meta = MediaMetadata.Builder()
                .setTitle(track.title.takeIf { it.isNotEmpty() })
                .setArtist(track.artists.joinToString(", ").takeIf { it.isNotEmpty() })
                .setAlbumTitle(track.album?.takeIf { it.isNotEmpty() })
                .setArtworkUri(track.artworkUri?.let { android.net.Uri.parse(it) })
            builder.setMediaMetadata(meta.build())
        }
        return builder.build()
    }

    /** Builds a playable MediaItem from a resolved stream (+ optional metadata). */
    fun from(stream: ZenStream, title: String? = null, artist: String? = null): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(withHandle(stream))
            .setMimeType(mimeFor(stream.format.container))
        if (title != null || artist != null) {
            val meta = MediaMetadata.Builder()
            title?.let { meta.setTitle(it) }
            artist?.let { meta.setArtist(it) }
            builder.setMediaMetadata(meta.build())
        }
        return builder.build()
    }

    private fun mimeFor(container: String): String = when (container.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/mpeg"
    }
}
