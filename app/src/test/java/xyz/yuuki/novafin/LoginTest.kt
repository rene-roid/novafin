package xyz.yuuki.novafin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class LoginTest {
    @Test fun parsesReply() {
        val s = parseDiscovery("""{"Address":"http://192.168.1.10:8096","Id":"abc123","Name":"Home","EndpointAddress":null}""")!!
        assertEquals(DiscoveredServer("abc123", "Home", "http://192.168.1.10:8096"), s)
    }

    @Test fun rejectsGarbageAndMissingId() {
        assertNull(parseDiscovery("hello"))
        assertNull(parseDiscovery(""))
        assertNull(parseDiscovery("""{"Address":"http://192.168.1.10:8096","Name":"Home"}"""))
    }

    @Test fun errorMessages() {
        val u = "http://192.168.1.10:8096"
        assertEquals("Couldn't find 192.168.1.10. Check the address.", connectError(UnknownHostException(), u))
        assertEquals("Nothing answered at 192.168.1.10:8096. Is Jellyfin running, and is the port right?", connectError(ConnectException(), u))
        assertEquals("192.168.1.10 didn't answer in time. Away from home? Try your Tailscale or public address.", connectError(SocketTimeoutException(), u))
        val tls = "Secure connection to 192.168.1.10 failed: the certificate isn't trusted."
        assertEquals(tls, connectError(SSLHandshakeException("x"), u))
        assertEquals(tls, connectError(SSLPeerUnverifiedException("x"), u))
        assertEquals("192.168.1.10 answered, but it isn't a Jellyfin server.", connectError(NotJellyfinException(), u))
        assertEquals("Couldn't connect to 192.168.1.10.", connectError(RuntimeException(), u))
    }

    @Test fun addressValidation() {
        assertNull(addressProblem("  "))
        assertNull(addressProblem("192.168.1.10:8096"))
        assertNull(addressProblem("jellyfin.example.com"))
        assertNotNull(addressProblem("my server"))
    }

    @Test fun oldVersions() {
        assertTrue(isOldServer("10.9.11"))
        assertFalse(isOldServer("10.10.0"))
        assertFalse(isOldServer("10.11.8"))
        assertFalse(isOldServer(""))
    }
}
