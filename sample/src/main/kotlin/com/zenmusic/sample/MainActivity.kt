package com.zenmusic.sample

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zenmusic.ZenMusic
import com.zenmusic.core.model.ZenContentSource
import com.zenmusic.core.model.ZenTrack
import com.zenmusic.datasource.ZenMediaItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wiring demo: search (music + open-platform videos) → tap → resolve → play,
 * with artwork loading through the engine. Every uri the app ever touches is
 * a zen:// pseudo-host uri; artwork uris are zen://a/… and load via
 * [ZenMusic.artworkBitmap] (or any DataSource built on zen.dataSourceFactory).
 */
@OptIn(UnstableApi::class)
class MainActivity : android.app.Activity() {

    private var player: ExoPlayer? = null
    private val results = ArrayList<ZenTrack>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var status: TextView
    private lateinit var nowPlayingArt: ImageView
    private lateinit var listHost: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val zen = ZenMusic.initialize(applicationContext)
        player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(zen.dataSourceFactory))
            .build()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "ZenMusic engine — resolver-only demo"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        nowPlayingArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                topMargin = dp(8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
        }
        root.addView(nowPlayingArt)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "search songs & videos…"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val search = Button(this).apply { text = "Search" }
        row.addView(input)
        row.addView(search)
        root.addView(row)

        status = TextView(this).apply { text = "type a query and hit search" }
        root.addView(status)

        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                addView(listHost)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
                )
            },
        )

        search.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener
            status.text = "searching…"
            listHost.removeAllViews()
            scope.launch {
                try {
                    results.clear()
                    // ZenSearchScope.ALL (default): music catalog + open-platform uploads
                    results.addAll(zen.search.songs(query).take(12))
                    status.text = if (results.isEmpty()) "no results" else
                        "tap to play — [V] = open-platform upload, all uris are zen://"
                    results.forEach { track ->
                        listHost.addView(resultRow(zen, track))
                    }
                } catch (t: Throwable) {
                    status.text = "search failed: ${t.message}"
                }
            }
        }

        setContentView(root)
    }

    /** One result row: artwork thumbnail + title/artist button. */
    private fun resultRow(zen: com.zenmusic.ZenEngine, track: ZenTrack): android.view.View {
        val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val art = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                rightMargin = dp(8)
                topMargin = dp(4)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        rowLayout.addView(art)
        val sourceTag = if (track.source == ZenContentSource.VIDEO) "[V] " else ""
        val views = track.viewsCount?.let { " · ${it / 1000}k views" } ?: ""
        val button = Button(this).apply {
            text = "$sourceTag${track.title} — ${track.artists.firstOrNull() ?: "?"}$views"
            setAllCaps(false)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { play(track) }
        }
        rowLayout.addView(button)
        loadArtwork(zen, track, art)
        return rowLayout
    }

    /** Artwork loads through the engine — the zen://a/… uri never hits an image lib. */
    private fun loadArtwork(zen: com.zenmusic.ZenEngine, track: ZenTrack, into: ImageView) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { zen.artworkBitmap(track.artworkUri) }
            if (bitmap != null) into.setImageBitmap(bitmap)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun play(track: ZenTrack) {
        val zen = ZenMusic.require()
        status.text = "resolving ${track.uri}"
        scope.launch {
            try {
                val stream = zen.resolve(track.uri)
                status.text = "playing via ${stream.uri}\n" +
                    "quality=${stream.format.quality} bitrate=${stream.format.bitrateKbps}kbps"
                loadArtwork(zen, track, nowPlayingArt)
                player?.apply {
                    // full metadata incl. artworkUri → shows in media notifications
                    setMediaItem(ZenMediaItems.from(stream, track))
                    prepare()
                    play()
                }
            } catch (t: Throwable) {
                status.text = "resolve failed: ${t.message}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        ZenMusic.shutdown()
        super.onDestroy()
    }
}
