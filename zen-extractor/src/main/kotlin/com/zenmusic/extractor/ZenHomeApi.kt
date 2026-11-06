package com.zenmusic.extractor

import com.zenmusic.core.model.ZenHomeFeed
import com.zenmusic.extractor.innertube.Parsers
import com.zenmusic.extractor.innertube.ZenInnertube
import com.zenmusic.core.vault.ZenVault

/**
 * The real home feed of the music service — the same signed-in-agnostic
 * shelves the official client shows on launch: "Listen again", "Mixed for
 * you", "New releases", moods & genres, each as a titled [com.zenmusic.core.model.ZenShelf].
 *
 * ```kotlin
 * val feed = zen.home.feed()                 // chips + shelves
 * val again = zen.home.feed(feed.chips
 *     .first { it.title == "Listen again" }.params)
 * ```
 */
class ZenHomeApi internal constructor(private val zenApi: ZenInnertube) {

    /**
     * Fetches the home feed. Pass a chip's [params] (from a previous
     * [ZenHomeFeed.chips]) to view that chip's section instead.
     */
    suspend fun feed(chipParams: String? = null): ZenHomeFeed {
        val payload = Parsers.parseHomeFeed(
            zenApi.browse(ZenVault.open("it.browse.home"), chipParams),
        )
        return ZenHomeFeed(payload.chips, payload.shelves)
    }
}
