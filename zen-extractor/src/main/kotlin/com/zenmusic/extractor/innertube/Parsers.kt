package com.zenmusic.extractor.innertube

import com.zenmusic.core.model.ZenAlbum
import com.zenmusic.core.model.ZenArtist
import com.zenmusic.core.model.ZenContentSource
import com.zenmusic.core.model.ZenFormat
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.core.uri.ZenUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Response parsers. Everything public-facing carries only disguised `zen://`
 * URIs — upstream hosts/ids are minted through [ZenUri] and never surfaced.
 */
internal object Parsers {

    // ---------- player ----------

    internal data class RawFormat(
        val itag: Int,
        val url: String,
        val bitrateKbps: Int,
        val mime: String,
        val sampleRateHz: Int?,
        val sizeBytes: Long?,
        val loudnessDb: Double?,
    )

    internal data class PlayerPayload(
        val mediaId: String,
        val title: String,
        val author: String,
        val durationMs: Long,
        val isLive: Boolean,
        val formats: List<RawFormat>,
        val thumbnailUrl: String?,
    )

    /**
     * Parses the player response into [PlayerPayload]. Formats served with a
     * direct `url` are taken as-is; formats served as `signatureCipher` (common
     * for WEB_REMIX sessions) are recovered through the optional [decipher]
     * hook — when it is absent (or fails) those formats are skipped.
     */
    internal suspend fun parsePlayer(
        response: JSONObject,
        decipher: (suspend (signatureCipher: String, videoId: String) -> String?)? = null,
    ): PlayerPayload {
        val details = response.optJSONObject("videoDetails")
            ?: throw IllegalArgumentException("player response missing videoDetails")
        val videoId = details.optString("videoId")
        val formats = ArrayList<RawFormat>()
        val adaptive = response.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: JSONArray()
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            var url = f.optString("url").takeIf { it.isNotEmpty() }
            if (url == null) {
                // WEB_REMIX often serves `signatureCipher` instead of a plain
                // `url`: the real googlevideo URL is base64-encoded inside the
                // cipher blob and the `s` param needs the player cipher.
                val cipher = f.optString("signatureCipher").takeIf { it.isNotEmpty() }
                if (cipher != null && decipher != null && videoId.isNotEmpty()) {
                    url = runCatching { decipher(cipher, videoId) }.getOrNull()
                }
            }
            url = url?.takeIf { it.isNotEmpty() } ?: continue
            val mime = f.optString("mimeType").trim()
            if (!mime.startsWith("audio/")) continue
            formats.add(
                RawFormat(
                    itag = f.optInt("itag", -1),
                    url = url,
                    bitrateKbps = (f.optLong("bitrate", 0L) / 1000L).toInt()
                        .takeIf { it > 0 }
                        ?: (f.optLong("averageBitrate", 0L) / 1000L).toInt(),
                    mime = mime,
                    sampleRateHz = f.optString("audioSampleRate").toIntOrNull(),
                    sizeBytes = f.optLong("contentLength", -1L).takeIf { it > 0 },
                    loudnessDb = if (f.has("loudnessDb")) f.optDouble("loudnessDb") else null,
                ),
            )
        }
        return PlayerPayload(
            mediaId = videoId,
            title = details.optString("title"),
            author = details.optString("author"),
            durationMs = details.optLong("lengthSeconds", 0L) * 1000L,
            isLive = details.optBoolean("isLiveContent", false),
            formats = formats,
            thumbnailUrl = thumbnailOf(details.optJSONObject("thumbnail")),
        )
    }

    private fun thumbnailOf(node: JSONObject?): String? {
        node ?: return null
        val arr = node.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val area = t.optLong("width", 0) * t.optLong("height", 0)
            if (area > bestArea) {
                bestArea = area
                best = t.optString("url")
            }
        }
        return best
    }

    /** Quality ladder: closest tier at or under [preferred], else nearest above. */
    internal fun chooseFormat(
        formats: List<RawFormat>,
        preferred: ZenQuality,
    ): Pair<RawFormat, ZenFormat>? {
        if (formats.isEmpty()) return null
        val sorted = formats.sortedBy { it.bitrateKbps }
        val target = preferred.preferredBitrateKbps
        val under = sorted.lastOrNull { it.bitrateKbps <= target }
        val chosen = under ?: sorted.first()
        val tier = when {
            chosen.mime.contains("flac", true) -> ZenQuality.LOSSLESS
            chosen.bitrateKbps <= ZenQuality.LOW.preferredBitrateKbps + 24 -> ZenQuality.LOW
            chosen.bitrateKbps <= ZenQuality.MEDIUM.preferredBitrateKbps + 40 -> ZenQuality.MEDIUM
            chosen.bitrateKbps <= ZenQuality.HIGH.preferredBitrateKbps + 90 -> ZenQuality.HIGH
            else -> ZenQuality.LOSSLESS
        }
        val format = ZenFormat(
            quality = tier,
            bitrateKbps = chosen.bitrateKbps,
            codec = extractCodec(chosen.mime),
            container = chosen.mime.substringAfter('/').substringBefore(';').trim()
                .ifEmpty { "mp4" },
            sampleRateHz = chosen.sampleRateHz,
            sizeBytes = chosen.sizeBytes,
            genuinelyLossless = chosen.mime.contains("flac", true),
        )
        return chosen to format
    }

    /** `audio/mp4; codecs=\"mp4a.40.2\"` → `mp4a.40.2` (handles escaped quotes). */
    internal fun extractCodec(mime: String): String {
        val clean = mime.replace("\\", "")
        val after = clean.substringAfter("codecs=", "").trim()
        return if (after.isEmpty()) clean.substringBefore(';').trim()
        else after.trim('"', '\'')
    }

    // ---------- search ----------

    internal data class SearchPage(
        val tracks: List<ZenTrack>,
        val albums: List<ZenAlbum>,
        val artists: List<ZenArtist>,
    )

    /**
     * Parses the open video-platform search response (`WEB` client). Primary
     * shape is `videoRenderer`; when the platform re-shelves results into
     * `lockupViewModel` wrappers a fallback pass recovers those too. Every
     * result is tagged [ZenContentSource.VIDEO].
     */
    internal fun parseOpenVideoSearch(response: JSONObject): List<ZenTrack> {
        val out = ArrayList<ZenTrack>()
        for (key in listOf("videoRenderer", "gridVideoRenderer")) {
            for (v in JsonWalk.findAllObjects(response, key)) {
                parseVideoRenderer(v)?.let { out.add(it) }
            }
        }
        if (out.isEmpty()) {
            for (l in JsonWalk.findAllObjects(response, "lockupViewModel")) {
                parseLockupVideo(l)?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.uri }
    }

    private fun parseVideoRenderer(v: JSONObject): ZenTrack? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = JsonWalk.allRunText(v.optJSONObject("title"))
            ?: v.optJSONObject("title")?.optString("simpleText")?.takeIf { it.isNotEmpty() }
            ?: return null
        val byline = (JsonWalk.allRunText(v.optJSONObject("ownerText"))
            ?: JsonWalk.allRunText(v.optJSONObject("longBylineText"))).orEmpty().trim()
        val lengthText = v.optJSONObject("lengthText")
        val durationText = lengthText?.let { JsonWalk.allRunText(it) }
            ?: lengthText?.optString("simpleText")?.takeIf { it.isNotEmpty() }
        val viewsText = v.optJSONObject("viewCountText")?.let { JsonWalk.allRunText(it) }
            ?: v.optJSONObject("viewCountText")?.optString("simpleText")
        return videoTrack(
            videoId = videoId,
            title = title,
            byline = byline,
            durationMs = parseDurationText(durationText),
            viewsCount = parseViewCountText(viewsText),
            thumbNode = v.optJSONObject("thumbnail"),
        )
    }

    private fun parseLockupVideo(l: JSONObject): ZenTrack? {
        val videoId = l.optString("contentId").takeIf { it.isNotEmpty() } ?: return null
        if (!l.optString("contentType").contains("VIDEO", ignoreCase = true) &&
            !l.optString("contentType").isEmpty()
        ) return null
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            ?: return null
        val title = meta.optJSONObject("title")?.optString("content")?.takeIf { it.isNotEmpty() }
            ?: return null
        // channel / views / age live in flat metadata parts; best-effort only
        val parts = ArrayList<String>()
        val rows = JsonWalk.findArray(
            meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel"),
            "metadataRows",
        )
        if (rows != null) {
            for (r in 0 until rows.length()) {
                val partsArr = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                for (i in 0 until partsArr.length()) {
                    val part = partsArr.optJSONObject(i)?.optJSONObject("text")?.optString("content")
                    if (!part.isNullOrEmpty()) parts.add(part)
                }
            }
        }
        val badges = ArrayList<String>()
        for (badge in JsonWalk.findAllObjects(l, "thumbnailBadgeViewModel")) {
            badge.optString("text")?.takeIf { it.isNotEmpty() }?.let { badges.add(it) }
        }
        val duration = badges.firstOrNull { it.contains(':') }
            ?: parts.firstOrNull { it.contains(':') }
        val views = parts.firstOrNull { Regex("\\d").containsMatchIn(it) && !it.contains(':') }
        val image = l.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
        return videoTrack(
            videoId = videoId,
            title = title,
            byline = parts.firstOrNull().orEmpty(),
            durationMs = parseDurationText(duration),
            viewsCount = parseViewCountText(views),
            thumbNode = image,
        )
    }

    private fun videoTrack(
        videoId: String,
        title: String,
        byline: String,
        durationMs: Long,
        viewsCount: Long?,
        thumbNode: JSONObject?,
    ): ZenTrack = ZenTrack(
        uri = ZenUri.mint(videoId),
        title = title,
        artists = listOfNotNull(byline.takeIf { it.isNotEmpty() }),
        durationMs = durationMs,
        artworkUri = JsonWalk.thumbnailUrl(thumbNode)?.let { ZenUri.mintArtwork(videoId) },
        isVideo = true,
        viewsCount = viewsCount,
        source = ZenContentSource.VIDEO,
    )

    /** `"1,234,567 views" / "1.2M views" / "42K" / "987"` → view count; null when unclear. */
    internal fun parseViewCountText(text: String?): Long? {
        text ?: return null
        val m = Regex("([0-9][0-9.,]*)\\s*([KMB])?").find(text) ?: return null
        val num = m.groupValues[1]
        return when (val suffix = m.groupValues[2].uppercase()) {
            // abbreviated forms carry a decimal separator ("1.2M" / "1,2M")
            "K", "M", "B" -> {
                val d = num.replace(',', '.').toDoubleOrNull() ?: return null
                val mult = when (suffix) {
                    "K" -> 1_000.0
                    "M" -> 1_000_000.0
                    else -> 1_000_000_000.0
                }
                (d * mult).toLong().takeIf { it > 0 }
            }
            // plain counts use thousand separators only
            else -> num.replace(Regex("[.,]"), "").toLongOrNull()?.takeIf { it > 0 }
        }
    }

    /**
     * Merged result list: music-catalog hits first, open-platform uploads
     * after, deduped by uri (the same upload found in both catalogs appears
     * once, as the music-catalog entry).
     */
    internal fun mergeTracks(music: List<ZenTrack>, open: List<ZenTrack>): List<ZenTrack> =
        (music + open).distinctBy { it.uri }

    internal fun parseSearch(response: JSONObject): SearchPage {
        val tracks = ArrayList<ZenTrack>()
        val albums = ArrayList<ZenAlbum>()
        val artists = ArrayList<ZenArtist>()

        val shelves = JsonWalk.findAllObjects(response, "musicShelfRenderer")
        for (shelf in shelves) {
            val shelfTitle = JsonWalk.firstRunText(shelf.optJSONObject("title")) ?: ""
            val items = JsonWalk.findAllObjects(shelf, "musicResponsiveListItemRenderer")
            for (item in items) {
                val videoId = JsonWalk.videoIdOf(item)
                val browseId = JsonWalk.browseIdOf(item)
                val title = JsonWalk.flexColumnText(item, 0) ?: continue
                val byline = JsonWalk.flexColumnText(item, 1) ?: ""
                val thumb = JsonWalk.thumbnailUrl(item)
                val durationMs = parseDurationText(fixedColumnText(item))

                if (videoId != null) {
                    val parts = byline.split(" • ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
                    tracks.add(
                        ZenTrack(
                            uri = ZenUri.mint(videoId),
                            title = title,
                            artists = if (parts.isNotEmpty()) listOf(parts.first()) else emptyList(),
                            album = parts.getOrNull(1),
                            durationMs = durationMs,
                            artworkUri = thumb?.let { ZenUri.mintArtwork(videoId) },
                            isVideo = shelfTitle.contains("Video", ignoreCase = true),
                            viewsCount = null,
                        ),
                    )
                } else if (browseId != null) {
                    when {
                        shelfTitle.contains("Album", ignoreCase = true) || browseId.startsWith("MPRE") ->
                            albums.add(
                                ZenAlbum(
                                    uri = ZenUri.mintBrowse(browseId),
                                    title = title,
                                    artist = byline.takeIf { it.isNotEmpty() },
                                    artworkUri = thumb?.let { ZenUri.mintBrowse(browseId) },
                                    year = Regex("\\b(19|20)\\d{2}\\b").find(byline)?.value,
                                ),
                            )
                        shelfTitle.contains("Artist", ignoreCase = true) || browseId.startsWith("UC") ->
                            artists.add(
                                ZenArtist(
                                    uri = ZenUri.mintBrowse(browseId),
                                    name = title,
                                    artworkUri = thumb?.let { ZenUri.mintBrowse(browseId) },
                                    subscribers = byline.takeIf { it.contains("subscriber", true) },
                                ),
                            )
                    }
                }
            }
        }
        return SearchPage(tracks, albums, artists)
    }

    private fun fixedColumnText(item: JSONObject): String? {
        val cols = item.optJSONArray("fixedColumns") ?: return null
        val col = cols.optJSONObject(0) ?: return null
        return JsonWalk.allRunText(col)
    }

    /** "3:45" / "1:02:03" → ms; null when not parseable. */
    internal fun parseDurationText(text: String?): Long {
        text ?: return -1L
        val m = Regex("(\\d{1,3}:(\\d{1,2}:)?\\d{1,2})").find(text) ?: return -1L
        val parts = m.value.split(':').map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            2 -> parts[0] * 60_000L + parts[1] * 1000L
            3 -> parts[0] * 3_600_000L + parts[1] * 60_000L + parts[2] * 1000L
            else -> -1L
        }
    }

    // ---------- next (radio / related) ----------

    internal fun parseRadioQueue(response: JSONObject): List<ZenTrack> {
        val out = ArrayList<ZenTrack>()
        val panels = JsonWalk.findAllObjects(response, "playlistPanelVideoRenderer")
        for (p in panels) {
            val videoId = p.optString("videoId").takeIf { it.isNotEmpty() }
                ?: JsonWalk.videoIdOf(p)
                ?: continue
            val title = JsonWalk.allRunText(p.optJSONObject("title")) ?: continue
            val byline = JsonWalk.allRunText(p.optJSONObject("longBylineText")).orEmpty()
            val durationMs = parseDurationText(JsonWalk.allRunText(p.optJSONObject("lengthText")))
            out.add(
                ZenTrack(
                    uri = ZenUri.mint(videoId),
                    title = title,
                    artists = byline.split(" • ", " · ").map { it.trim() }.filter { it.isNotEmpty() },
                    durationMs = durationMs,
                    artworkUri = JsonWalk.thumbnailUrl(p.optJSONObject("thumbnail"))
                        ?.let { ZenUri.mintArtwork(videoId) },
                ),
            )
        }
        if (out.isEmpty()) {
            // fallback: queue renderers
            for (q in JsonWalk.findAllObjects(response, "musicQueueRenderer")) {
                val shelf = q.optJSONObject("content") ?: continue
                for (item in JsonWalk.findAllObjects(shelf, "musicResponsiveListItemRenderer")) {
                    val videoId = JsonWalk.videoIdOf(item) ?: continue
                    val title = JsonWalk.flexColumnText(item, 0) ?: continue
                    out.add(
                        ZenTrack(
                            uri = ZenUri.mint(videoId),
                            title = title,
                            artists = listOfNotNull(JsonWalk.flexColumnText(item, 1)),
                            durationMs = parseDurationText(item.optJSONArray("fixedColumns")?.optJSONObject(0)
                                ?.let { JsonWalk.allRunText(it) }),
                            artworkUri = JsonWalk.thumbnailUrl(item)?.let { ZenUri.mintArtwork(videoId) },
                        ),
                    )
                }
            }
        }
        return out.distinctBy { it.uri }
    }
}
