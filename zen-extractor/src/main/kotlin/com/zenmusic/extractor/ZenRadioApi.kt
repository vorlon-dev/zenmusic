package com.zenmusic.extractor

import com.zenmusic.core.model.ZenTrack
import com.zenmusic.extractor.innertube.Parsers
import com.zenmusic.extractor.innertube.ZenInnertube

/**
 * Radio / related-song queue builder (autoplay) built on the `next` endpoint.
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

    /** Related songs for [seed] (no mix session, plain next request). */
    suspend fun related(seed: String): List<ZenTrack> {
        val mediaId = ZenExtractor.mediaIdOf(seed)
        return Parsers.parseRadioQueue(zenApi.next(mediaId, null))
    }
}
