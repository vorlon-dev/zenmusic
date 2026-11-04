package com.zenmusic.core

import com.zenmusic.core.uri.ZenUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenUriTest {

    @Test
    fun `mint and parse roundtrip`() {
        val uri = ZenUri.mint("dQw4w9WgXcQ")
        assertTrue(uri.startsWith("zen://"))
        assertTrue(ZenUri.isZen(uri))
        val parsed = ZenUri.parse(uri)!!
        assertEquals(ZenUri.Kind.STREAM, parsed.kind)
        assertEquals("dQw4w9WgXcQ", parsed.id)
    }

    @Test
    fun `artwork uris parse with kind`() {
        val uri = ZenUri.mintArtwork("dQw4w9WgXcQ")
        val parsed = ZenUri.parse(uri)!!
        assertEquals(ZenUri.Kind.ARTWORK, parsed.kind)
        assertEquals("dQw4w9WgXcQ", parsed.id)
    }

    @Test
    fun `rejects foreign and malformed uris`() {
        assertNull(ZenUri.parse("https://example.com/whatever"))
        assertNull(ZenUri.parse("zen://bad id.suffix"))
        assertNull(ZenUri.parse("zen://dQw4w9WgXcQ.wrong.suffix"))
        assertFalse(ZenUri.isZen("zen:xyz"))
    }

    @Test
    fun `validate throws on garbage`() {
        try {
            ZenUri.validate("zen://nope")
            org.junit.Assert.fail("expected throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `decoy mint produces valid parseable uri`() {
        val parsed = ZenUri.parse(ZenUri.mintDecoy())!!
        assertEquals(ZenUri.Kind.STREAM, parsed.kind)
    }

    @Test
    fun `pseudo-host suffix is not a real platform host`() {
        // the suffix must be a decoy: two labels, not an HTTPS-able FQDN of the engine
        val suffix = ZenUri.decoySuffix()
        assertTrue(suffix.matches(Regex("[a-z0-9.\\-]+")))
        assertFalse(suffix.endsWith(".com"))
    }
}
