package xyz.yuuki.novafin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class DiscoveredServer(val id: String, val name: String, val address: String)

/** The server answered, but not like Jellyfin (HTTP error, or no JSON with an Id). */
class NotJellyfinException : IOException("Not a Jellyfin server")

fun parseDiscovery(text: String): DiscoveredServer? = runCatching {
    val o = JSONObject(text)
    val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return null
    val address = o.optString("Address").takeIf { it.isNotBlank() } ?: return null
    DiscoveredServer(id, o.optString("Name").ifBlank { address }, normalizeAddress(address))
}.getOrNull()

/** Jellyfin replies on UDP 7359 to this payload; some networks drop broadcasts, then the list is just empty. */
suspend fun discoverServers(timeoutMs: Long = 2000): List<DiscoveredServer> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, DiscoveredServer>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val payload = "who is JellyfinServer?".toByteArray()
            val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNullTo(targets) { it.broadcast }
            }
            for (t in targets) runCatching { socket.send(DatagramPacket(payload, payload.size, t, 7359)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (true) {
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) break
                socket.soTimeout = left
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: SocketTimeoutException) { break }
                parseDiscovery(String(p.data, 0, p.length))?.let { found.putIfAbsent(it.id, it) }
            }
        }
    }
    found.values.toList()
}

/** [Api.publicInfo] with "answered, but not Jellyfin" told apart from network failures. */
fun probeServer(base: String): ServerInfo = try {
    Api.publicInfo(base)
} catch (e: org.json.JSONException) {
    throw NotJellyfinException()
} catch (e: IOException) {
    if (e.message?.startsWith("HTTP ") == true) throw NotJellyfinException() else throw e
}

fun connectError(e: Throwable, url: String): String {
    val u = url.toHttpUrlOrNull()
    val host = u?.host ?: url
    return when (e) {
        is UnknownHostException -> "Couldn't find $host. Check the address."
        is ConnectException -> "Nothing answered at $host${u?.let { ":${it.port}" } ?: ""}. Is Jellyfin running, and is the port right?"
        is InterruptedIOException -> "$host didn't answer in time. Away from home? Try your Tailscale or public address."
        is SSLException -> "Secure connection to $host failed: the certificate isn't trusted."
        is NotJellyfinException -> "$host answered, but it isn't a Jellyfin server."
        else -> "Couldn't connect to $host."
    }
}

fun addressProblem(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    if (t.any { it.isWhitespace() }) return "Addresses can't contain spaces."
    return if (normalizeAddress(t).toHttpUrlOrNull() == null) "That doesn't look like a valid address." else null
}

/** Novafin targets 10.11; anything before 10.10 is flagged. */
fun isOldServer(version: String): Boolean {
    val p = version.split('.').mapNotNull { it.toIntOrNull() }
    return p.size >= 2 && (p[0] < 10 || (p[0] == 10 && p[1] < 10))
}
