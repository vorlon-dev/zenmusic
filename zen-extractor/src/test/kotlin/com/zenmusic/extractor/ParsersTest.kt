package com.zenmusic.extractor

import com.zenmusic.core.model.ZenContentSource
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.core.uri.ZenUri
import com.zenmusic.extractor.innertube.Parsers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are built programmatically to avoid brace-count drift. */
class ParsersTest {

    private fun runsObj(vararg texts: String): JSONObject {
        val runs = JSONArray()
        for (t in texts) runs.put(JSONObject().put("text", t))
        return JSONObject().put("runs", runs)
    }

    private fun flexColumn(vararg texts: String): JSONObject =
        JSONObject().put(
            "musicResponsiveListItemFlexCellRenderer",
            JSONObject().put("text", runsObj(*texts)),
        )

    private fun flexItem(videoId: String?, browseId: String?, vararg columns: JSONObject): JSONObject {
        val item = JSONObject()
        if (videoId != null) item.put("playlistItemData", JSONObject().put("videoId", videoId))
        if (browseId != null) {
            item.put(
                "navigationEndpoint",
                JSONObject().put("browseEndpoint", JSONObject().put("browseId", browseId)),
            )
        }
        val cols = JSONArray()
        for (c in columns) cols.put(c)
        item.put("flexColumns", cols)
        return item
    }

    private fun shelf(title: String, vararg items: JSONObject): JSONObject = JSONObject().put(
        "musicShelfRenderer",
        JSONObject()
            .put("title", runsObj(title))
            .put("contents", JSONArray(items.map { JSONObject().put("musicResponsiveListItemRenderer", it) })),
    )

    private fun response(vararg shelves: JSONObject): JSONObject = JSONObject().put(
        "contents",
        JSONObject().put(
            "tabbedSearchResultsRenderer",
            JSONObject().put(
                "tabs",
                JSONArray().put(
                    JSONObject().put(
                        "tabRenderer",
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "sectionListRenderer",
                                JSONObject().put("contents", JSONArray(shelves)),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `signatureCipher formats are recovered through the decipher hook`() = runBlocking {
        val realUrl = "https://media.invalid/videoplayback?itag=251&signature=deciphered"
        val json = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("videoDetails", JSONObject().put("videoId", "dQw4w9WgXcQ").put("title", "t").put("author", "a").put("lengthSeconds", "100"))
            .put(
                "streamingData",
                JSONObject().put(
                    "adaptiveFormats",
                    JSONArray().put(
                        // cipher-only format: no direct url field
                        JSONObject()
                            .put("itag", 251)
                            .put("mimeType", "audio/webm; codecs=\"opus\"")
                            .put("bitrate", 142381)
                            .put(
                                "signatureCipher",
                                "s=OBFUSCATED&sp=sig&url=" + java.net.URLEncoder.encode(realUrl, "UTF-8"),
                            ),
                    ),
                ),
            )

        // without a decipher hook the format is skipped
        val noDecipher = Parsers.parsePlayer(json)
        assertEquals(0, noDecipher.formats.size)

        // with the hook the real URL is recovered
        val payload = Parsers.parsePlayer(json) { cipher, videoId ->
            assertEquals("dQw4w9WgXcQ", videoId)
            java.net.URLDecoder.decode(cipher.substringAfter("url="), "UTF-8")
        }
        assertEquals(1, payload.formats.size)
        assertEquals(realUrl, payload.formats[0].url)
        assertEquals(251, payload.formats[0].itag)
    }

    @Test
    fun `parses player payload with audio formats`() {
        val json = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put(
                "videoDetails",
                JSONObject()
                    .put("videoId", "dQw4w9WgXcQ")
                    .put("title", "Never Gonna Give You Up")
                    .put("author", "Rick Astley")
                    .put("lengthSeconds", "213")
                    .put("isLiveContent", false)
                    .put(
                        "thumbnail",
                        JSONObject().put(
                            "thumbnails",
                            JSONArray()
                                .put(JSONObject().put("width", 120).put("height", 90).put("url", "https://t/small.jpg"))
                                .put(JSONObject().put("width", 544).put("height", 408).put("url", "https://t/big.jpg")),
                        ),
                    ),
            )
            .put(
                "streamingData",
                JSONObject().put(
                    "adaptiveFormats",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("itag", 140)
                                .put("url", "https://media.invalid/seg?itag=140&pot=x")
                                .put("mimeType", "audio/mp4; codecs=\\\"mp4a.40.2\\\"")
                                .put("bitrate", 129537)
                                .put("audioSampleRate", "44100")
                                .put("contentLength", 3456789),
                        )
                        .put(
                            JSONObject()
                                .put("itag", 251)
                                .put("url", "https://media.invalid/seg?itag=251")
                                .put("mimeType", "audio/webm; codecs=\\\"opus\\\"")
                                .put("bitrate", 142381)
                                .put("audioSampleRate", "48000")
                                .put("contentLength", 3556789),
                        )
                        .put(
                            JSONObject()
                                .put("itag", 137)
                                .put("url", "https://media.invalid/video")
                                .put("mimeType", "video/mp4; codecs=\\\"avc1.640028\\\"")
                                .put("bitrate", 2000000),
                        ),
                ),
            )

        val payload = runBlocking { Parsers.parsePlayer(json) }
        assertEquals("dQw4w9WgXcQ", payload.mediaId)
        assertEquals(2, payload.formats.size) // video-only format filtered out
        assertEquals(213_000L, payload.durationMs)
        assertEquals("https://t/big.jpg", payload.thumbnailUrl)

        val (raw, format) = Parsers.chooseFormat(payload.formats, ZenQuality.HIGH)!!
        // preferred 256 kbps: best tier under the target is opus ~142
        assertEquals(251, raw.itag)
        assertEquals("opus", format.codec)
        assertTrue(format.bitrateKbps in 100..200)
    }

    @Test
    fun `codec extraction handles escaped quotes`() {
        assertEquals("mp4a.40.2", Parsers.extractCodec("""audio/mp4; codecs=\"mp4a.40.2\""""))
        assertEquals("opus", Parsers.extractCodec("audio/webm; codecs=\"opus\""))
        assertEquals("opus", Parsers.extractCodec("audio/webm; codecs='opus'"))
        assertEquals("audio/mp4", Parsers.extractCodec("audio/mp4"))
    }

    @Test
    fun `quality ladder falls back upward`() {
        val formats = listOf(
            Parsers.RawFormat(140, "u140", 128, "audio/mp4; codecs=\"mp4a.40.2\"", 44100, null, null),
        )
        val (raw, format) = Parsers.chooseFormat(formats, ZenQuality.LOSSLESS)!!
        assertEquals(140, raw.itag)
        assertEquals("mp4a.40.2", format.codec)
    }

    @Test
    fun `duration text parsing`() {
        assertEquals(225_000L, Parsers.parseDurationText("3:45"))
        assertEquals(3_723_000L, Parsers.parseDurationText("1:02:03"))
        assertEquals(-1L, Parsers.parseDurationText("nope"))
    }

    @Test
    fun `search page parses tracks albums artists`() {
        val song = flexItem(
            "aaaaaaaaaaa", null,
            flexColumn("Some Song"),
            flexColumn("Some Artist", " • ", "Some Album"),
        ).put(
            "fixedColumns",
            JSONArray().put(
                JSONObject().put(
                    "musicResponsiveListItemFixedColumnRenderer",
                    JSONObject().put("text", runsObj("3:30")),
                ),
            ),
        )
        val page = Parsers.parseSearch(response(shelf("Songs", song)))
        assertEquals(1, page.tracks.size)
        assertEquals("Some Song", page.tracks[0].title)
        assertEquals(listOf("Some Artist"), page.tracks[0].artists)
        assertEquals("Some Album", page.tracks[0].album)
        assertEquals(210_000L, page.tracks[0].durationMs)
        assertTrue(page.albums.isEmpty())
        assertTrue(page.artists.isEmpty())
    }

    @Test
    fun `search page parses album shelf items`() {
        val album = flexItem(
            null, "MPREb_xyz12345",
            flexColumn("Some Album"),
            flexColumn("Some Artist", " • ", "2024"),
        )
        val page = Parsers.parseSearch(response(shelf("Albums", album)))
        assertEquals(1, page.albums.size)
        assertEquals("Some Album", page.albums[0].title)
        assertEquals("2024", page.albums[0].year)
        assertTrue(page.tracks.isEmpty())
    }

    @Test
    fun `radio queue parses playlist panels`() {
        fun panel(videoId: String, title: String, artist: String, length: String? = null): JSONObject {
            val p = JSONObject()
                .put("videoId", videoId)
                .put("title", runsObj(title))
                .put("longBylineText", runsObj(artist))
            if (length != null) p.put("lengthText", runsObj(length))
            return JSONObject().put("playlistPanelVideoRenderer", p)
        }
        val json = JSONObject().put(
            "contents",
            JSONObject().put(
                "singleColumnMusicWatchNextResultsRenderer",
                JSONObject().put(
                    "tabbedRenderer",
                    JSONObject().put(
                        "watchNextTabbedResultsRenderer",
                        JSONObject().put(
                            "tabs",
                            JSONArray().put(
                                JSONObject().put(
                                    "tabRenderer",
                                    JSONObject().put(
                                        "content",
                                        JSONObject().put(
                                            "musicQueueRenderer",
                                            JSONObject().put(
                                                "content",
                                                JSONObject().put(
                                                    "playlistPanelRenderer",
                                                    JSONObject().put(
                                                        "contents",
                                                        JSONArray().put(
                                                            panel("bbbbbbbbbbb", "Queue Song", "Queue Artist", "4:00"),
                                                        ).put(
                                                            panel("ccccccccccc", "Queue Song 2", "Queue Artist 2"),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val queue = Parsers.parseRadioQueue(json)
        assertEquals(2, queue.size)
        assertEquals("Queue Song", queue[0].title)
        assertEquals(240_000L, queue[0].durationMs)
    }

    // ---------- open video-platform search ----------

    private fun videoRenderer(videoId: String, title: String, channel: String, length: String?): JSONObject {
        val v = JSONObject()
            .put("videoId", videoId)
            .put("title", runsObj(title))
            .put("ownerText", runsObj(channel))
            .put("viewCountText", runsObj("1,234,567 views"))
            .put(
                "thumbnail",
                JSONObject().put(
                    "thumbnails",
                    JSONArray().put(
                        JSONObject().put("width", 360).put("height", 202).put("url", "https://t/v.jpg"),
                    ),
                ),
            )
        if (length != null) v.put("lengthText", JSONObject().put("simpleText", length))
        return v
    }

    @Test
    fun `open video search parses videoRenderer items`() {
        val json = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("videoRenderer", videoRenderer("bbbbbbbbbbb", "Live Cover Song", "Cover Channel", "4:05")),
            ).put(
                JSONObject().put("videoRenderer", videoRenderer("ccccccccccc", "Unreleased Track", "Indie Uploads", null)),
            ),
        )
        val tracks = Parsers.parseOpenVideoSearch(json)
        assertEquals(2, tracks.size)
        assertEquals("Live Cover Song", tracks[0].title)
        assertEquals(listOf("Cover Channel"), tracks[0].artists)
        assertEquals(245_000L, tracks[0].durationMs)
        assertEquals(1_234_567L, tracks[0].viewsCount)
        assertTrue(tracks[0].isVideo)
        assertEquals(ZenContentSource.VIDEO, tracks[0].source)
        assertTrue(tracks[0].artworkUri!!.startsWith("zen://a/"))
        assertEquals(-1L, tracks[1].durationMs)
        assertTrue(ZenUri.parse(tracks[0].uri)!!.id.isNotEmpty())
    }

    @Test
    fun `open video search falls back to lockupViewModel`() {
        val lockup = JSONObject().put(
            "lockupViewModel",
            JSONObject()
                .put("contentType", "LOCKUP_CONTENT_TYPE_VIDEO")
                .put("contentId", "ddddddddddd")
                .put(
                    "contentImage",
                    JSONObject().put(
                        "thumbnailViewModel",
                        JSONObject().put(
                            "image",
                            JSONObject().put(
                                "sources",
                                JSONArray().put(
                                    JSONObject().put("width", 336).put("height", 188).put("url", "https://t/l.jpg"),
                                ),
                            ),
                        ),
                    ),
                )
                .put(
                    "metadata",
                    JSONObject().put(
                        "lockupMetadataViewModel",
                        JSONObject()
                            .put("title", JSONObject().put("content", "Lockup Song"))
                            .put(
                                "metadata",
                                JSONObject().put(
                                    "contentMetadataViewModel",
                                    JSONObject().put(
                                        "metadataRows",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "metadataParts",
                                                JSONArray().put(
                                                    JSONObject().put("text", JSONObject().put("content", "Some Channel")),
                                                ).put(
                                                    JSONObject().put("text", JSONObject().put("content", "1.2M views")),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                )
                .put(
                    "thumbnailOverlays",
                    JSONArray().put(
                        JSONObject().put(
                            "thumbnailBadgeViewModel",
                            JSONObject().put("text", "3:21"),
                        ),
                    ),
                ),
        )
        val tracks = Parsers.parseOpenVideoSearch(JSONObject().put("contents", JSONArray().put(lockup)))
        assertEquals(1, tracks.size)
        assertEquals("Lockup Song", tracks[0].title)
        assertEquals(listOf("Some Channel"), tracks[0].artists)
        assertEquals(201_000L, tracks[0].durationMs)
        assertEquals(1_200_000L, tracks[0].viewsCount)
        assertEquals(ZenContentSource.VIDEO, tracks[0].source)
    }

    @Test
    fun `view count text parsing`() {
        assertEquals(1_234_567L, Parsers.parseViewCountText("1,234,567 views"))
        assertEquals(1_200_000L, Parsers.parseViewCountText("1.2M views"))
        assertEquals(42_000L, Parsers.parseViewCountText("42K"))
        assertEquals(987L, Parsers.parseViewCountText("987"))
        assertEquals(null, Parsers.parseViewCountText("no numbers here"))
        assertEquals(null, Parsers.parseViewCountText(null))
    }

    @Test
    fun `merged tracks keep music first and dedupe by uri`() {
        val music = listOf(track("aaaaaaaaaaa", ZenContentSource.MUSIC), track("bbbbbbbbbbb", ZenContentSource.MUSIC))
        val open = listOf(track("bbbbbbbbbbb", ZenContentSource.VIDEO), track("ccccccccccc", ZenContentSource.VIDEO))
        val merged = Parsers.mergeTracks(music, open)
        assertEquals(3, merged.size)
        assertEquals(ZenContentSource.MUSIC, merged[0].source)
        // the open-platform duplicate of a music hit is dropped
        assertEquals(ZenContentSource.MUSIC, merged[1].source)
        assertEquals(ZenContentSource.VIDEO, merged[2].source)
    }

    private fun track(videoId: String, source: ZenContentSource): ZenTrack =
        ZenTrack(uri = ZenUri.mint(videoId), title = "t-$videoId", artists = emptyList(), source = source)

    private fun twoRow(
        title: String,
        subtitle: String,
        videoId: String? = null,
        browseId: String? = null,
        playlistId: String? = null,
    ): JSONObject {
        val watch = JSONObject()
        videoId?.let { watch.put("videoId", it) }
        playlistId?.let { watch.put("playlistId", it) }
        val nav = JSONObject()
        if (videoId != null || playlistId != null) nav.put("watchEndpoint", watch)
        browseId?.let { nav.put("browseEndpoint", JSONObject().put("browseId", it)) }
        return JSONObject().put(
            "musicTwoRowItemRenderer",
            JSONObject()
                .put("title", runsObj(title))
                .put("subtitle", runsObj(subtitle))
                .put("navigationEndpoint", nav)
                .put(
                    "thumbnailRenderer",
                    JSONObject().put(
                        "musicThumbnailRenderer",
                        JSONObject().put(
                            "thumbnail",
                            JSONObject().put(
                                "thumbnails",
                                JSONArray().put(JSONObject().put("url", "https://img.invalid/t.jpg").put("width", 544).put("height", 544)),
                            ),
                        ),
                    ),
                ),
        )
    }

    @Test
    fun `home feed parses chips and mixed shelves`() {
        val home = JSONObject()
            .put(
                "header",
                JSONObject().put(
                    "feedFilterChipBarRenderer",
                    JSONObject().put(
                        "chips",
                        JSONArray().put(
                            JSONObject().put(
                                "chipCloudChipRenderer",
                                JSONObject()
                                    .put("text", runsObj("Listen again"))
                                    .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("params", "chip-p-1"))),
                            ),
                        ),
                    ),
                ),
            )
            .put(
                "contents",
                JSONObject().put(
                    "sectionListRenderer",
                    JSONObject().put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "musicCarouselShelfRenderer",
                                JSONObject()
                                    .put(
                                        "header",
                                        JSONObject().put(
                                            "musicCarouselShelfBasicHeaderRenderer",
                                            JSONObject().put("title", runsObj("Mixed for you")),
                                        ),
                                    )
                                    .put(
                                        "contents",
                                        JSONArray()
                                            .put(twoRow("Song A", "Artist A", videoId = "aaaaaaaaaaa"))
                                            .put(twoRow("Album B", "Artist B", browseId = "MPREb_album1"))
                                            .put(twoRow("Artist C", "1.2M subscribers", browseId = "UCartist123"))
                                            .put(twoRow("Playlist D", "12 tracks", playlistId = "PLplay1234")),
                                    ),
                            ),
                        ),
                    ),
                ),
            )

        val feed = Parsers.parseHomeFeed(home)
        assertEquals(1, feed.chips.size)
        assertEquals("Listen again", feed.chips[0].title)
        assertEquals("chip-p-1", feed.chips[0].params)

        assertEquals(1, feed.shelves.size)
        val shelf = feed.shelves[0]
        assertEquals("Mixed for you", shelf.title)
        assertEquals(1, shelf.tracks.size)
        assertEquals("Song A", shelf.tracks[0].title)
        assertTrue(shelf.tracks[0].uri.startsWith("zen://"))
        assertEquals(1, shelf.albums.size)
        assertEquals("Album B", shelf.albums[0].title)
        assertEquals(1, shelf.artists.size)
        assertEquals("Artist C", shelf.artists[0].name)
        assertEquals(1, shelf.playlists.size)
        assertEquals("Playlist D", shelf.playlists[0].title)
    }

    @Test
    fun `watch-next related tab is located and related shelves parse`() {
        val watchNext = JSONObject().put(
            "contents",
            JSONObject().put(
                "singleColumnMusicWatchNextRenderer",
                JSONObject().put(
                    "tabs",
                    JSONArray()
                        .put(JSONObject().put("tabRenderer", JSONObject().put("title", "Queue")))
                        .put(
                            JSONObject().put(
                                "tabRenderer",
                                JSONObject()
                                    .put("title", "Related")
                                    .put(
                                        "endpoint",
                                        JSONObject().put(
                                            "watchEndpoint",
                                            JSONObject()
                                                .put("videoId", "aaaaaaaaaaa")
                                                .put("playlistId", "RDAMVMaaaaaaaaaaa")
                                                .put("params", "related-params-1"),
                                        ),
                                    ),
                            ),
                        ),
                ),
            ),
        )

        val tab = Parsers.watchNextRelatedTab(watchNext)
        assertNotNull(tab)
        assertEquals("related-params-1", tab!!.params)
        assertEquals("RDAMVMaaaaaaaaaaa", tab.playlistId)

        // the related response itself: one carousel of tracks
        val related = JSONObject().put(
            "contents",
            JSONObject().put(
                "sectionListRenderer",
                JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "musicCarouselShelfRenderer",
                            JSONObject()
                                .put(
                                    "header",
                                    JSONObject().put(
                                        "musicCarouselShelfBasicHeaderRenderer",
                                        JSONObject().put("title", runsObj("You might also like")),
                                    ),
                                )
                                .put(
                                    "contents",
                                    JSONArray()
                                        .put(twoRow("Related 1", "R Artist", videoId = "rbbbbbbbbbb"))
                                        .put(twoRow("Related 2", "R Artist 2", videoId = "rcccccccccc")),
                                ),
                        ),
                    ),
                ),
            ),
        )
        val shelves = Parsers.parseRelatedShelves(related)
        assertEquals(1, shelves.size)
        assertEquals("You might also like", shelves[0].title)
        assertEquals(2, shelves[0].tracks.size)
        assertEquals("Related 1", shelves[0].tracks[0].title)
    }
}
