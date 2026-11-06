package com.zenmusic.core

import com.zenmusic.core.vault.ZenVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZenVaultTest {

    @Test
    fun `roundtrip seal and unseal`() {
        val secret = "unit-test-payload-é中-123"
        val sealed = ZenVault.seal(secret)
        assertNotEquals(secret, sealed)
        assertEquals(secret, ZenVault.unseal(sealed))
    }

    @Test
    fun `built-in secrets decrypt`() {
        // The decoy suffix is a fake pseudo-host; we only assert it decrypts
        // to a syntactically plausible suffix without printing the plaintext.
        val suffix = ZenVault.open("decoy.suffix")
        assertTrue(suffix.contains('.'))
        assertTrue(suffix.length >= 5)
        // every built-in id must decrypt without integrity failure
        for (id in listOf(
            "it.host", "it.key.remix", "it.ver.remix", "it.origin",
            "ua.web", "ua.zen",
            "it.ver.visionos", "ua.visionos", "it.ver.visionos2", "it.ver.tvs", "ua.tvs",
            "gv.host", "jnn.host", "jnn.key", "jnn.reqkey", "lrc.host",
            "it.filter.songs", "it.filter.videos", "it.filter.albums", "it.filter.artists",
            "web.clientver", "jnn.create.path", "jnn.generate.path", "status.ok",
            "img.host", "it.path.prefix", "hdr.client.name", "hdr.client.ver",
            "yt.host", "yt.origin", "yt.filter.videos", "it.browse.home",
        )) {
            assertTrue("failed: $id", ZenVault.openOrNull(id)!!.isNotEmpty())
        }
    }

    @Test
    fun `tampered ciphertext fails loudly`() {
        val sealed = ZenVault.seal("tamper-check")
        val corrupted = sealed.dropLast(4) + (if (sealed.last() == 'A') 'B' else 'A')
        try {
            ZenVault.unseal(corrupted)
            fail("expected integrity failure")
        } catch (e: com.zenmusic.core.ZenVaultException) {
            // expected — GCM authentication rejected the modification
        }
    }

    @Test
    fun `unknown id throws`() {
        try {
            ZenVault.open("no.such.id")
            fail("expected throw")
        } catch (e: com.zenmusic.core.ZenVaultException) {
            // expected
        }
    }
}
