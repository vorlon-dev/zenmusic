package com.zenmusic.potoken

import com.zenmusic.core.ZenTokenException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(
    private val failFirst: Int = 0,
) : PotokenProvider {
    var sessionMints = 0
    var contentMints = 0
    private var fails = 0

    override suspend fun sessionToken(sessionIdentifier: String): PoToken {
        if (sessionMints + contentMints < failFirst) {
            fails++
            throw ZenTokenException("fake failure #$fails")
        }
        sessionMints++
        return PoToken("sess-token", PoTokenType.SESSION, sessionIdentifier,
            System.currentTimeMillis() + 60_000)
    }

    override suspend fun contentToken(mediaId: String, sessionIdentifier: String): PoToken {
        contentMints++
        return PoToken("content-token-$mediaId", PoTokenType.CONTENT, mediaId,
            System.currentTimeMillis() + 60_000)
    }
}

class PotokenCoordinatorTest {

    @Test
    fun `caches session token across calls`() = runTest {
        val provider = FakeProvider()
        val coordinator = PotokenCoordinator(provider)
        val first = coordinator.sessionTokenOrNull("visitor-1")
        val second = coordinator.sessionTokenOrNull("visitor-1")
        assertNotNull(first)
        assertEquals(first!!.value, second!!.value)
        assertEquals(1, provider.sessionMints)
    }

    @Test
    fun `session cache is keyed by session identifier`() = runTest {
        val provider = FakeProvider()
        val coordinator = PotokenCoordinator(provider)
        val first = coordinator.sessionTokenOrNull("visitor-1")
        // Visitor rotation: a new identifier must mint a fresh token bound to
        // it — serving the previous binding would make the server withhold
        // every stream format behind an OK playability status.
        val second = coordinator.sessionTokenOrNull("visitor-2")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("visitor-1", first!!.binding)
        assertEquals("visitor-2", second!!.binding)
        assertEquals(2, provider.sessionMints)
        // The cache holds only the latest binding: switching back re-mints.
        coordinator.sessionTokenOrNull("visitor-1")
        assertEquals(3, provider.sessionMints)
    }

    @Test
    fun `content tokens are per media id`() = runTest {
        val provider = FakeProvider()
        val coordinator = PotokenCoordinator(provider)
        val a = coordinator.contentTokenOrNull("aaaaaaaaaaa", "visitor-1")
        val b = coordinator.contentTokenOrNull("bbbbbbbbbbb", "visitor-1")
        assertEquals("content-token-aaaaaaaaaaa", a!!.value)
        assertEquals("content-token-bbbbbbbbbbb", b!!.value)
        assertEquals(2, provider.contentMints)
    }

    @Test
    fun `disabled coordinator returns null without minting`() = runTest {
        val provider = FakeProvider()
        val coordinator = PotokenCoordinator(provider, enabled = false)
        assertNull(coordinator.sessionTokenOrNull("v"))
        assertNull(coordinator.contentTokenOrNull("m", "v"))
        assertEquals(0, provider.sessionMints)
    }

    @Test
    fun `failure enters backoff and stats recorded`() = runTest {
        val provider = FakeProvider(failFirst = 5)
        val coordinator = PotokenCoordinator(provider)
        assertNull(coordinator.sessionTokenOrNull("v"))
        assertTrue(coordinator.isCoolingDown)
        assertNull(coordinator.sessionTokenOrNull("v")) // backoff: no second attempt
        assertEquals(1, coordinator.stats.failures)
        assertEquals(0, provider.sessionMints)
    }

    @Test
    fun `strict sessionToken throws when unavailable`() = runTest {
        val coordinator = PotokenCoordinator(FakeProvider(failFirst = 5), enabled = false)
        try {
            coordinator.sessionToken("v")
            org.junit.Assert.fail("expected throw")
        } catch (e: ZenTokenException) {
            // expected
        }
    }
}
