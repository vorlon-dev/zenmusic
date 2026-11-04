package com.zenmusic.extractor

/**
 * Post-processes stream URLs after the player response is parsed and before
 * they enter the resolver broker.
 *
 * 2026 InnerTube behaviour: WEB_REMIX stream URLs only serve the first ~1 MiB
 * unless the URL carries a per-video PO token (`&pot=`, bound to the media
 * id), and the `n` parameter must be transformed to avoid CDN throttling.
 * Both operations require BotGuard machinery (WebView on Android), which
 * lives outside this JVM module — so the umbrella injects an implementation.
 *
 * Implementations must be safe to call concurrently and must never log the
 * URL or token material. Return null to keep the URL untouched (engine then
 * degrades gracefully to the tokenless fallback clients).
 */
fun interface StreamUrlEnhancer {
    /**
     * @param mediaId the media id the URL belongs to
     * @param sessionId the engine session identifier (visitor data) the
     * player request was bound to
     * @param url the raw stream URL from the player response
     * @return the enhanced URL, or null to keep [url] as-is
     */
    suspend fun enhance(mediaId: String, sessionId: String, url: String): String?
}
