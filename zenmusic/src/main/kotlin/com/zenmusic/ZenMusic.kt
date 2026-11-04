package com.zenmusic

import android.content.Context
import android.os.Debug
import com.zenmusic.core.ZenConfig
import com.zenmusic.core.log.ZenLog
import com.zenmusic.potoken.PotokenCoordinator
import com.zenmusic.potoken.RhinoPotokenMinter
import com.zenmusic.resolver.TamperDetector
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One-call wiring for the whole engine.
 *
 * ```kotlin
 * val zen = ZenMusic.initialize(context)          // or ZenConfig(...) to tune
 *
 * val results = zen.search.songs("night drive")   // List<ZenTrack> (zen:// uris)
 * val stream  = zen.resolve(results[0].uri)       // ZenStream (opaque handle)
 * val media   = ZenMediaItems.from(stream, results[0].title)
 *
 * val player = ExoPlayer.Builder(context)
 *     .setMediaSourceFactory(DefaultMediaSourceFactory(zen.dataSourceFactory))
 *     .build()
 * player.setMediaItem(media)
 * player.prepare(); player.play()
 * ```
 *
 * No other third-party dependency is required: extraction, PO-token minting,
 * resolution, disguise, and the Media3 transport are all bundled.
 */
object ZenMusic {

    @Volatile
    private var engine: ZenEngine? = null

    /** Initializes (or re-initializes) the engine with [config]. */
    @JvmStatic
    @Synchronized
    fun initialize(context: Context, config: ZenConfig = ZenConfig()): ZenEngine {
        engine?.destroy()
        ZenLog.enabled = config.logDebug
        val appContext = context.applicationContext

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val potokenProvider: com.zenmusic.potoken.PotokenProvider = run {
            if (config.potokenWebView) {
                try {
                    // WebView-based BotGuard (zemer-cipher): remote self-healing
                    // player configs keep minting current without app updates.
                    com.zemer.cipher.ZemerCipher.initialize(appContext)
                    ZemerPotokenAdapter()
                } catch (t: Throwable) {
                    ZenLog.w("zemer poToken engine unavailable; using Rhino minter", t)
                    RhinoPotokenMinter(http, config.gl, config.hl)
                }
            } else {
                RhinoPotokenMinter(http, config.gl, config.hl)
            }
        }
        val potoken = PotokenCoordinator(
            provider = potokenProvider,
            enabled = config.potokenEnabled,
        )
        val enhancer = if (config.potokenWebView && config.contentPoToken) {
            ZemerStreamEnhancer(potoken)
        } else null
        // WEB_REMIX (and other web clients) may serve formats as `signatureCipher`;
        // the vendored zemer cipher WebView recovers the real stream URLs.
        val decipher = if (config.potokenWebView) {
            com.zenmusic.extractor.SignatureDecipher { cipher, videoId ->
                com.zemer.cipher.CipherDeobfuscator.deobfuscateStreamUrl(cipher, videoId)
            }
        } else null
        // The player request's contentPlaybackContext.signatureTimestamp must
        // come from the same player config the cipher uses — a second, stale
        // source would de-sync the two. Null (or a failed fetch) simply omits
        // the field, exactly like the verified web client's degraded path.
        val signatureTimestamp: (suspend () -> Int?)? = if (config.potokenWebView) {
            { com.zemer.cipher.CipherDeobfuscator.signatureTimestamp() }
        } else null
        val extractor = com.zenmusic.extractor.ZenExtractor(http, config, potoken, enhancer, decipher, signatureTimestamp)
        val resolver = com.zenmusic.resolver.ZenResolver(
            extractor = extractor,
            config = config,
            tamperDetector = AndroidTamperDetector,
        )
        val dataSourceFactory = com.zenmusic.datasource.ZenDataSourceFactory(resolver)

        return ZenEngine(appContext, config, http, potoken, extractor, resolver, dataSourceFactory)
            .also { engine = it }
    }

    /** Currently wired engine, or null before [initialize]. */
    @JvmStatic
    fun get(): ZenEngine? = engine

    /** Currently wired engine; throws when [initialize] was not called. */
    @JvmStatic
    fun require(): ZenEngine =
        engine ?: error("ZenMusic.initialize(context) must be called before use")

    /** Releases everything. */
    @JvmStatic
    @Synchronized
    fun shutdown() {
        engine?.destroy()
        engine = null
    }
}

/**
 * The wired engine. Exposes the safe facade only: search / radio / lyrics
 * operate on zen:// uris, [resolve] yields opaque stream handles, and
 * [dataSourceFactory] feeds the host app's own Media3/ExoPlayer.
 */
class ZenEngine internal constructor(
    @Suppress("unused") private val appContext: Context,
    val config: ZenConfig,
    internal val http: OkHttpClient,
    val potoken: PotokenCoordinator,
    private val extractor: com.zenmusic.extractor.ZenExtractor,
    val resolver: com.zenmusic.resolver.ZenResolver,
    val dataSourceFactory: com.zenmusic.datasource.ZenDataSourceFactory,
) {
    /** YT Music search (songs / videos / albums / artists). */
    val search: com.zenmusic.extractor.ZenSearchApi get() = extractor.search

    /** Radio / related-song queues. */
    val radio: com.zenmusic.extractor.ZenRadioApi get() = extractor.radio

    /** LRCLIB synced lyrics. */
    val lyrics: com.zenmusic.extractor.ZenLyricsApi get() = extractor.lyrics

    /** Resolves a zen:// uri into a playable, opaque [com.zenmusic.core.model.ZenStream]. */
    suspend fun resolve(uri: String, quality: com.zenmusic.core.model.ZenQuality? = null) =
        resolver.resolve(uri, quality)

    /** Track metadata for a zen:// uri / media id. */
    suspend fun track(uri: String) = extractor.track(uri)

    /** Disguised artwork bytes for a zen://a/… uri. */
    suspend fun artwork(uri: String) = resolver.fetchArtwork(uri)

    /**
     * Decoded artwork bitmap for a zen://a/… uri — the one-liner for in-app
     * covers: `imageView.setImageBitmap(zen.artworkBitmap(track.artworkUri))`.
     * Network + decode run on IO; null when the uri is invalid or the image
     * is unavailable.
     */
    suspend fun artworkBitmap(zenArtworkUri: String?): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = zenArtworkUri?.let { resolver.fetchArtwork(it) } ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

    /** Internal state nuke (tokens, broker handles, visitor data). */
    fun destroy() {
        resolver.destroy()
        potoken.close()
        ZenVaultPurge.purge()
    }
}

/**
 * Android tamper signals feeding the fake-stream shower. When a debugger is
 * attached (or a stock-emulator fingerprint is detected) the resolver serves
 * decoy streams instead of real playback metadata.
 */
object AndroidTamperDetector : TamperDetector {

    private val EMULATOR_MARKERS = listOf(
        "generic", "sdk_gphone", "emulator", "vbox86p", "genymotion",
        "nox", "bluestacks", "x86_64",
    )

    override fun isTampered(): Boolean {
        return try {
            Debug.isDebuggerConnected() || Debug.waitingForDebugger() || isEmulatorLike()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isEmulatorLike(): Boolean {
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val product = android.os.Build.PRODUCT.lowercase()
        return EMULATOR_MARKERS.any { fp.contains(it) || model.contains(it) || product.contains(it) }
    }
}

/** Best-effort scrub of decrypted secrets cached by the vault. */
private object ZenVaultPurge {
    fun purge() = com.zenmusic.core.vault.ZenVault.purgeCache()
}
