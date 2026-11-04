package com.zenmusic.core

import com.zenmusic.core.decoy.ZenDecoys
import com.zenmusic.core.uri.ZenUri
import com.zenmusic.core.log.ZenLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenDecoysTest {

    @Test
    fun `shower produces distinct fake streams`() {
        val shower = ZenDecoys.shower(6)
        assertEquals(6, shower.size)
        assertEquals(shower.map { it.handle }.toSet().size, 6)
        shower.forEach {
            assertTrue(ZenUri.isZen(it.uri))
            assertTrue(it.format.bitrateKbps > 0)
        }
    }

    @Test
    fun `fake media urls look plausible but are synthetic`() {
        val url = ZenDecoys.fakeMediaUrl()
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("videoplayback"))
        assertNotEquals(url, ZenDecoys.fakeMediaUrl())
    }
}

class ZenLogTest {

    @Test
    fun `scrub hides sealed hosts and tokens`() {
        val gv = com.zenmusic.core.vault.ZenVault.open("gv.host")
        val msg = "open https://cache01.$gv/videoplayback?pot=AAAA123&signature=BBBB456&ip=10.1.2.3"
        val scrubbed = ZenLog.scrub(msg)
        assertNotEquals(msg, scrubbed)
        assertTrue(!scrubbed.contains("AAAA123"))
        assertTrue(!scrubbed.contains("BBBB456"))
        assertTrue(!scrubbed.contains("10.1.2.3"))
        assertTrue(scrubbed.contains("media.node"))
    }
}
