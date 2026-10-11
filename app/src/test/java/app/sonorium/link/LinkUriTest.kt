package app.sonorium.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkUriTest {
    private val fp = "43ac7768b9a7f1466231109553362e434deb751e90843f402446fc86d2e8123e"

    private fun ok(raw: String) = (LinkUri.parse(raw) as LinkUri.Result.Ok).link

    @Test
    fun parsesTheWebForm() {
        // The real example from APP_API.md 1.1.
        val link = ok(
            "https://sonorium.app/link#v=1&id=f4c26f39-3512-497a-8703-62875a3d4736&n=Sonorium" +
                "&h=192.168.1.25,sonorium-2.local&p=8008&s=8808&fp=$fp&c=83KS6GG3&u=admin",
        )
        assertEquals("f4c26f39-3512-497a-8703-62875a3d4736", link.serverId)
        assertEquals(listOf("192.168.1.25", "sonorium-2.local"), link.hosts)
        assertEquals(8008, link.httpPort)
        assertEquals(8808, link.httpsPort)
        assertEquals(fp, link.fingerprint)
        assertEquals("83KS6GG3", link.code)
        assertEquals("admin", link.username)
    }

    @Test
    fun parsesTheLegacyFormAndTrailingSlash() {
        val legacy = ok("sonorium://pair?v=1&id=x&n=Sonorium&h=10.0.0.2&p=8008&fp=$fp&c=AB")
        assertNull(legacy.httpsPort)
        assertNull(legacy.username)
        assertEquals("x", ok("https://sonorium.app/link/#v=1&id=x&h=a&p=1&fp=$fp&c=C").serverId)
    }

    @Test
    fun decodesPercentEscapesLikeTheServer() {
        // The server encodes with quote(safe=","): spaces as %20, a comma kept literally in h.
        val link = ok("https://sonorium.app/link#v=1&id=x&n=Living%20room&h=a,b&p=8008&fp=$fp&c=C&u=J%C3%B6rg%2Bx")
        assertEquals("Living room", link.name)
        assertEquals("Jörg+x", link.username)
    }

    @Test
    fun refusesOtherVersionsAndBadInput() {
        assertTrue(LinkUri.parse("https://sonorium.app/link#v=2&id=x") is LinkUri.Result.UnsupportedVersion)
        assertTrue(LinkUri.parse("https://example.com/link#v=1") is LinkUri.Result.Invalid)
        assertTrue(LinkUri.parse("https://sonorium.app/linkfoo#v=1&id=x&h=a&p=1&fp=$fp&c=C") is LinkUri.Result.Invalid)
        assertTrue(LinkUri.parse("https://sonorium.app/link#v=1&id=x&h=a&p=1&fp=abc&c=C") is LinkUri.Result.Invalid)
        assertTrue(LinkUri.parse("https://sonorium.app/link#v=1&id=x&h=a&p=99999&fp=$fp&c=C") is LinkUri.Result.Invalid)
    }
}
