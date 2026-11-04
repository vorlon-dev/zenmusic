package com.zenmusic.resolver

import com.zenmusic.core.ZenConfig
import com.zenmusic.core.ZenResolutionException
import com.zenmusic.core.model.ZenQuality
import com.zenmusic.extractor.StreamPlan
import com.zenmusic.extractor.ZenExtractor
import com.zenmusic.potoken.PotokenCoordinator
import com.zenmusic.potoken.PotokenProvider
import com.zenmusic.potoken.PoToken
import com.zenmusic.potoken.PoTokenType
import com.zenmusic.core.uri.ZenUri
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenResolverTest {

    private fun extractor(): ZenExtractor =
        ZenExtractor(
            http = OkHttpClient(),
            config = ZenConfig(),
            potoken = PotokenCoordinator(NoopProvider),
        )

    @Test
    fun `tampered engine serves shower decoys`() = runTest {
        val resolver = ZenResolver(
            extractor(),
            ZenConfig(decoyShower = true),
            tamperDetector = { true },
        )
        val stream = resolver.resolve(ZenUri.mint("dQw4w9WgXcQ"))
        assertTrue(stream.handle.isNotEmpty())
        // decoy handles are never registered in the broker
        assertNull(resolver.openChannel(stream.handle))
    }

    @Test(expected = ZenResolutionException::class)
    fun `non-zen uri rejected`() = runTest {
        ZenResolver(extractor(), ZenConfig(), tamperDetector = { false })
            .resolve("https://example.invalid/song.mp3")
    }

    @Test
    fun `broker keeps urls opaque and handles random`() {
        val broker = StreamBroker()
        val plan = StreamPlan(
            mediaId = "dQw4w9WgXcQ",
            mediaUrl = "https://some-host.invalid/videoplayback?id=SECRET&id=dQw4w9WgXcQ&pot=TOKEN",
            format = com.zenmusic.core.model.ZenFormat(ZenQuality.HIGH, 128, "mp4a.40.2", "mp4"),
            isLive = false,
        )
        val stream = broker.register(plan)
        assertTrue(stream.handle.length >= 24)
        assertTrue(!stream.handle.contains("SECRET"))
        assertEquals(ZenUri.mint("dQw4w9WgXcQ"), stream.uri)
        // two registrations must produce distinct handles
        assertTrue(stream.handle != broker.register(plan).handle)
    }

    @Test
    fun `unknown handle yields null channel`() {
        val resolver = ZenResolver(extractor(), ZenConfig(), tamperDetector = { false })
        assertNull(resolver.openChannel("totally-unknown-handle-aaaaaaaaaaaa"))
    }

    @Test
    fun `mediaIdOf accepts zen uri and bare id`() {
        assertEquals("dQw4w9WgXcQ", ZenExtractor.mediaIdOf(ZenUri.mint("dQw4w9WgXcQ")))
        assertEquals("dQw4w9WgXcQ", ZenExtractor.mediaIdOf("dQw4w9WgXcQ"))
    }

    @Test
    fun `purge clears expired entries`() {
        val broker = StreamBroker()
        broker.register(
            StreamPlan("aaaaaaaaaaa", "https://x.invalid/1",
                com.zenmusic.core.model.ZenFormat(ZenQuality.LOW, 64, "opus", "webm"), false),
        )
        assertEquals(0, broker.purge()) // nothing expired yet
        broker.clear()
    }

    private object NoopProvider : PotokenProvider {
        override suspend fun sessionToken(sessionIdentifier: String): PoToken =
            PoToken("x", PoTokenType.SESSION, sessionIdentifier, 0L)

        override suspend fun contentToken(mediaId: String, sessionIdentifier: String): PoToken =
            PoToken("x", PoTokenType.CONTENT, mediaId, 0L)
    }
}
