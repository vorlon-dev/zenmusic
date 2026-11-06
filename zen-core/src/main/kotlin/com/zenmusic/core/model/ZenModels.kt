package com.zenmusic.core.model

/**
 * Loudness tiers the resolver aims for. The engine always falls back to the
 * best available tier below the requested one; LOSSLESS means "highest
 * available" for hosts that do not serve true lossless audio.
 */
enum class ZenQuality(val preferredBitrateKbps: Int) {
    LOW(70),
    MEDIUM(128),
    HIGH(256),
    LOSSLESS(385);

    companion object {
        /** Parses a user-facing tier string; unknown values fall back to HIGH. */
        fun from(raw: String?): ZenQuality = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true)
        } ?: HIGH
    }
}

/**
 * Where a result came from, phrased engine-agnostically (the disguised URIs
 * never encode an upstream platform, and neither does this marker).
 */
enum class ZenContentSource {
    /** Curated music catalog. */
    MUSIC,

    /** Open video platform (general search — covers uploads the music catalog lacks). */
    VIDEO,
}

/** How wide [com.zenmusic.extractor.ZenSearchApi.songs] casts its net. */
enum class ZenSearchScope {
    /** Music catalog only (the classic behavior). */
    MUSIC_ONLY,

    /** Open video platform only. */
    OPEN_VIDEOS,

    /** Both catalogs merged: music results first, then open-platform uploads, deduped. */
    ALL,
}

/**
 * A playable item surfaced by search / radio / related. The [uri] is always a
 * disguised `zen://` pseudo-host URI — never a real media URL, and it does not
 * encode which upstream engine served the item.
 */
data class ZenTrack(
    val uri: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val albumUri: String? = null,
    val durationMs: Long = -1L,
    /** Disguised artwork URI (`zen://a/…`); fetch bytes via the engine's media fetcher. */
    val artworkUri: String? = null,
    val isVideo: Boolean = false,
    val viewsCount: Long? = null,
    /** Which catalog surfaced this item (see [ZenSearchScope]). */
    val source: ZenContentSource = ZenContentSource.MUSIC,
)

data class ZenAlbum(
    val uri: String,
    val title: String,
    val artist: String?,
    val artworkUri: String? = null,
    val year: String? = null,
    val trackCount: Int = -1,
)

data class ZenArtist(
    val uri: String,
    val name: String,
    val artworkUri: String? = null,
    val subscribers: String? = null,
)

data class ZenPlaylist(
    val uri: String,
    val title: String,
    val author: String? = null,
    val artworkUri: String? = null,
    val trackCount: Int = -1,
)

/**
 * One home-feed / related carousel: a titled shelf of mixed content (the
 * upstream order is preserved: tracks, then albums, artists, playlists).
 */
data class ZenShelf(
    val title: String?,
    val tracks: List<ZenTrack> = emptyList(),
    val albums: List<ZenAlbum> = emptyList(),
    val artists: List<ZenArtist> = emptyList(),
    val playlists: List<ZenPlaylist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/** A home-feed filter chip ("Listen again", "New releases", …). */
data class ZenHomeChip(
    val title: String,
    /** Browse params to pass to [ZenHomeApi.feed] for this chip's view. */
    val params: String? = null,
)

/** The real home feed: filter chips + content shelves. */
data class ZenHomeFeed(
    val chips: List<ZenHomeChip>,
    val shelves: List<ZenShelf>,
)

/** Selected audio format of a resolved stream (metadata only — no URLs). */
data class ZenFormat(
    val quality: ZenQuality,
    val bitrateKbps: Int,
    val codec: String,
    val container: String,
    val sampleRateHz: Int? = null,
    val sizeBytes: Long? = null,
    /** True only when the upstream genuinely serves a lossless codec. */
    val genuinelyLossless: Boolean = false,
)

/**
 * A resolved, playable stream. Carries an opaque [handle] that the app passes
 * to its own player stack (see `zen-datasource`). The real media location is
 * never exposed on this object; it lives inside the engine's broker and
 * expires automatically.
 */
class ZenStream(
    val uri: String,
    val format: ZenFormat,
    val isLive: Boolean,
    val handle: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String = "ZenStream(uri=$uri, quality=${format.quality}, " +
        "bitrate=${format.bitrateKbps}kbps, live=$isLive)"
}

/** Lyrics result — synced LRC lines and/or plain text. */
data class ZenLyrics(
    val synced: Boolean,
    /** LRC payload with [mm:ss.xx] timestamps when [synced] is true. */
    val lrc: String? = null,
    val plain: String? = null,
    val provider: String = "zen",
)
