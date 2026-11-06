package com.zenmusic.extractor

import com.zenmusic.core.model.ZenShelf
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.extractor.innertube.Parsers
import com.zenmusic.extractor.innertube.ZenInnertube

/**
 * Radio / related-song queue builder built on the `next` endpoint — the same
 * two-step flow the official client uses: a watch-next request (mix queue +
 * tab endpoints), then a second request with the Related tab's serialized
 * watch params that returns the real "You might also like" carousels.
 */
class ZenRadioApi internal constructor(private val zenApi: ZenInnertube) {

    /**
     * Endless-style radio seeded by [seed] (a zen:// uri or media id):
     * returns the mix queue starting around the seed.
     */
    suspend fun radioFor(seed: String): List<ZenTrack> {
        val mediaId = ZenExtractor.mediaIdOf(seed)
        return Parsers.parseRadioQueue(zenApi.next(mediaId, "RDAMVM$mediaId"))
    }

    /** The watch-next queue for [seed] (single song context, no mix session). */
    suspend fun queue(seed: String): List<ZenTrack> {
        val mediaId = ZenExtractor.mediaIdOf(seed)
        return Parsers.parseRadioQueue(zenApi.next(mediaId, null))
    }

    /**
     * Related songs for [seed] — every carousel of the Related tab merged
     * (upstream order, deduped). This is what real autoplay queues are built
     * from.
     */
    suspend fun related(seed: String): List<ZenTrack> =
        relatedShelves(seed).flatMap { it.tracks }.distinctBy { it.uri }

    /**
     * Related carousels for [seed], laid out exactly like the official
     * Related tab: titled shelves ("You might also like", "Similar to …").
     */
    suspend fun relatedShelves(seed: String): List<ZenShelf> {
        val mediaId = ZenExtractor.mediaIdOf(seed)
        val watchNext = zenApi.next(mediaId, "RDAMVM$mediaId")
        val tab = Parsers.watchNextRelatedTab(watchNext)
        val relatedResponse = if (tab?.params != null) {
            runCatching {
                zenApi.next(tab.videoId ?: mediaId, tab.playlistId, tab.params)
            }.getOrNull() ?: watchNext
        } else {
            watchNext
        }
        return Parsers.parseRelatedShelves(relatedResponse)
    }
}
