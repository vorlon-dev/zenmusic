package com.zenmusic.extractor

import com.zenmusic.core.model.ZenAlbum
import com.zenmusic.core.model.ZenArtist
import com.zenmusic.core.model.ZenSearchScope
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.extractor.innertube.Parsers
import com.zenmusic.extractor.innertube.ZenInnertube
import com.zenmusic.core.vault.ZenVault

/**
 * Search facade over both catalogs: the curated music catalog (WEB_REMIX
 * filters) and the open video platform (WEB, videos-only). All results carry
 * `zen://` URIs and a [com.zenmusic.core.model.ZenContentSource] marker.
 */
class ZenSearchApi internal constructor(private val zenApi: ZenInnertube) {

    data class SearchResults(
        val query: String,
        val tracks: List<ZenTrack>,
        val albums: List<ZenAlbum>,
        val artists: List<ZenArtist>,
    )

    /**
     * Song search.
     * @param scope how wide to cast the net — [ZenSearchScope.ALL] (the
     * default) merges music-catalog hits with open-platform uploads; pass
     * [ZenSearchScope.MUSIC_ONLY] for the classic music-only behavior.
     */
    suspend fun songs(query: String, scope: ZenSearchScope = ZenSearchScope.ALL): List<ZenTrack> =
        when (scope) {
            ZenSearchScope.MUSIC_ONLY ->
                search(query, ZenVault.open("it.filter.songs")).tracks
            ZenSearchScope.OPEN_VIDEOS ->
                openVideos(query)
            ZenSearchScope.ALL ->
                Parsers.mergeTracks(
                    search(query, ZenVault.open("it.filter.songs")).tracks,
                    runCatching { openVideos(query) }.getOrDefault(emptyList()),
                )
        }

    /** Music-catalog videos only (covers, live performances, …). */
    suspend fun videos(query: String): List<ZenTrack> =
        search(query, ZenVault.open("it.filter.videos")).tracks

    /**
     * Open video platform results — uploads, remixes, full versions the music
     * catalog does not carry. Each track is tagged
     * [com.zenmusic.core.model.ZenContentSource.VIDEO] and resolves through
     * the same engine (and the same `zen://` uris) as catalog songs.
     */
    suspend fun openVideos(query: String): List<ZenTrack> =
        Parsers.parseOpenVideoSearch(zenApi.openVideoSearch(query))

    suspend fun albums(query: String): List<ZenAlbum> =
        search(query, ZenVault.open("it.filter.albums")).albums

    suspend fun artists(query: String): List<ZenArtist> =
        search(query, ZenVault.open("it.filter.artists")).artists

    /**
     * One pass over everything: music shelves + open-platform uploads merged
     * (music first, deduped — see [Parsers.mergeTracks]).
     */
    suspend fun all(query: String): SearchResults {
        val page = search(query, null)
        val open = runCatching { openVideos(query) }.getOrDefault(emptyList())
        return SearchResults(
            query = query,
            tracks = Parsers.mergeTracks(page.tracks, open),
            albums = page.albums,
            artists = page.artists,
        )
    }

    private suspend fun search(query: String, filter: String?) =
        Parsers.parseSearch(zenApi.search(query, filter))
}
