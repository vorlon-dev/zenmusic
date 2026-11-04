package com.zenmusic.extractor

import com.zenmusic.core.ZenConfig
import com.zenmusic.core.model.ZenLyrics
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.core.vault.ZenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Time-synced lyrics via the public LRCLIB API (same provider the Zen-music
 * app uses). Falls back from exact match to fuzzy search.
 */
class ZenLyricsApi internal constructor(
    private val http: OkHttpClient,
    private val config: ZenConfig,
) {

    suspend fun get(track: ZenTrack): ZenLyrics? = withContext(Dispatchers.IO) {
        val host = ZenVault.open("lrc.host")
        val ua = ZenVault.open("ua.zen")
        val artist = track.artists.firstOrNull().orEmpty()
        val durationSec = if (track.durationMs > 0) track.durationMs / 1000 else null

        // 1) exact match
        val exact = "https://$host/api/get" +
            "?artist_name=${url(artist)}&track_name=${url(track.title)}" +
            (durationSec?.let { "&duration=$it" } ?: "")
        fetch(exact, ua)?.let { return@withContext it }

        // 2) fuzzy search — closest duration wins
        val search = "https://$host/api/search?q=${url("$artist ${track.title}")}"
        val raw = httpGet(search, ua) ?: return@withContext null
        val candidates = JSONArray(raw)
        var bestEntry: JSONObject? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until candidates.length()) {
            val e = candidates.optJSONObject(i) ?: continue
            val delta = if (durationSec != null) {
                kotlin.math.abs(e.optLong("duration", 0L) - durationSec)
            } else 0L
            if (delta < bestDelta) {
                bestDelta = delta
                bestEntry = e
            }
        }
        val entry = bestEntry ?: return@withContext null
        val synced = entry.optString("syncedLyrics").takeIf { it.isNotEmpty() }
        val plain = entry.optString("plainLyrics").takeIf { it.isNotEmpty() }
        if (synced == null && plain == null) null
        else ZenLyrics(synced = synced != null, lrc = synced, plain = plain)
    }

    private fun fetch(url: String, ua: String): ZenLyrics? {
        val body = httpGet(url, ua) ?: return null
        val json = JSONObject(body)
        val synced = json.optString("syncedLyrics").takeIf { it.isNotEmpty() }
        val plain = json.optString("plainLyrics").takeIf { it.isNotEmpty() }
        if (synced == null && plain == null) return null
        return ZenLyrics(synced = synced != null, lrc = synced, plain = plain)
    }

    private fun httpGet(url: String, ua: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun url(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
