package xyz.yuuki.novafin

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Every server URL in the app uses this placeholder host and is resolved against the best address
 * at the moment it is sent (see [Api.http]). Nothing stores a real address, so images, streams and
 * downloads survive switching between LAN, Tailscale and the public domain, and cache keys stay put.
 */
const val SERVER = "http://server.novafin"
private const val SERVER_HOST = "server.novafin"

/** Accepts what people paste: "192.168.1.5:8096", "https://jf.example.com/web/#/home". */
fun normalizeAddress(input: String): String {
    var u = input.trim().trimEnd('/')
    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
    u = u.substringBefore("/web/").substringBefore("/web#").removeSuffix("/web").trimEnd('/')
    return u.toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: u
}

enum class AddressKind { LAN, Tailscale, Public }

fun addressKind(url: String): AddressKind {
    val host = url.toHttpUrlOrNull()?.host ?: return AddressKind.Public
    if (host.endsWith(".ts.net")) return AddressKind.Tailscale
    if (host.endsWith(".local") || host == "localhost") return AddressKind.LAN
    val ip = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return AddressKind.Public
    return when {
        ip[0] == 100 && ip[1] in 64..127 -> AddressKind.Tailscale
        ip[0] == 10 || ip[0] == 127 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168) -> AddressKind.LAN
        else -> AddressKind.Public
    }
}

/**
 * The address to use given each one's round trip (null = no answer), in the user's order. The fastest
 * wins, but an earlier address answering within [graceMs] of it takes precedence, so home prefers
 * the LAN even when the public domain answers a hair faster.
 */
fun chooseAddress(order: List<String>, rtt: Map<String, Long?>, graceMs: Long = 250): String? {
    val ok = order.filter { rtt[it] != null }
    val best = ok.minOfOrNull { rtt.getValue(it)!! } ?: return null
    return ok.first { rtt.getValue(it)!! <= best + graceMs }
}

/** Address a Chromecast can reach: the LAN at home, else the public domain, Tailscale only when marked. */
fun castAddress(addresses: List<Address>, active: String?): String? {
    val a = addresses.firstOrNull { it.url == active }
    if (a != null && (a.tv || addressKind(a.url) == AddressKind.LAN)) return a.url
    return addresses.firstOrNull { it.tv }?.url ?: addresses.firstOrNull { addressKind(it.url) == AddressKind.Public }?.url ?: active
}

data class ServerInfo(val id: String, val name: String, val version: String, val localAddress: String?)

class Api(private val prefs: Prefs, private val scope: CoroutineScope, private val onUnauthorized: () -> Unit) {
    private val json = "application/json".toMediaType()

    val authHeader get() = authHeader(prefs.deviceId, prefs.token)

    /** Shared by API calls, Coil and ExoPlayer, so all of them follow the active address. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor(::resolve)
        .build()
    private val pingHttp = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

    @Volatile var active: String? = prefs.active ?: prefs.addresses.firstOrNull()?.url
        private set
    val activeFlow = MutableStateFlow(active)
    /** Last round trip per address in ms, null when it didn't answer. */
    val latency = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val online = MutableStateFlow(true)

    private var race: Deferred<String?>? = null
    @Volatile private var lastFailedRace = 0L

    // ---- address switching ----

    private fun resolve(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != SERVER_HOST) return chain.proceed(req)
        if (System.currentTimeMillis() - lastFailedRace < 10_000) throw IOException("Server unreachable")
        val base = active ?: throw IOException("No server address")
        val res = try {
            chain.proceed(rewrite(req, base))
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            // Re-race and retry once on whichever address answers now.
            val next = runBlocking { pickBest() }
            if (next == null || next == base) throw e
            chain.proceed(rewrite(req, next))
        }
        if (res.code == 401 && prefs.token != null && !req.url.encodedPath.startsWith("/Users/Authenticate")) onUnauthorized()
        return res
    }

    private fun rewrite(req: Request, base: String): Request {
        val b = base.toHttpUrl()
        val url = b.newBuilder()
            .encodedPath(b.encodedPath.trimEnd('/') + req.url.encodedPath)
            .encodedQuery(req.url.encodedQuery)
            .build()
        return req.newBuilder().url(url).header("Authorization", authHeader).build()
    }

    /** Real URL for [path] on [base], for the Chromecast, which can't use our interceptor. */
    fun absolute(path: String, base: String? = castAddress(prefs.addresses, active)): String =
        (base ?: active ?: "").trimEnd('/') + path.removePrefix(SERVER)

    private var reconnecting: Job? = null

    /** Races the addresses, retrying for a few seconds: a network that just came up often can't route yet. */
    fun reconnect() {
        reconnecting?.cancel()
        reconnecting = scope.launch {
            for (wait in listOf(0L, 1_000, 2_000, 4_000, 8_000)) {
                delay(wait)
                if (pickBest() != null) return@launch
            }
        }
    }

    /** Pings every address and switches to the best one. Concurrent callers share one race. */
    suspend fun pickBest(): String? {
        val r = synchronized(this) { race?.takeIf { it.isActive } ?: scope.async { runRace() }.also { race = it } }
        return r.await()
    }

    private suspend fun runRace(): String? {
        val all = prefs.addresses.map { it.url }
        val order = prefs.pinned?.takeIf { it in all }?.let { listOf(it) } ?: all
        if (order.isEmpty()) return null
        val rtt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val first = CompletableDeferred<Unit>()
        val jobs = order.map { url ->
            scope.launch(Dispatchers.IO) {
                val ms = ping(url)
                latency.update { it + (url to ms) }
                if (ms != null) {
                    rtt[url] = ms
                    first.complete(Unit)
                }
            }
        }
        val done = scope.launch { jobs.forEach { it.join() }; first.complete(Unit) }
        first.await()
        // Give earlier addresses a moment to answer too.
        withTimeoutOrNull(250) { done.join() }
        val w = chooseAddress(order, order.associateWith { rtt[it] })
        online.value = w != null
        lastFailedRace = if (w == null) System.currentTimeMillis() else 0
        if (w != null && w != active) {
            active = w
            prefs.active = w
            activeFlow.value = w
            // Pooled connections point at the old address.
            scope.launch(Dispatchers.IO) { http.connectionPool.evictAll() }
        }
        return w
    }

    /** Round trip to [base] in ms, only if it is our server. */
    private fun ping(base: String): Long? = try {
        val t0 = System.nanoTime()
        val info = publicInfo(base, pingHttp)
        if (info.id == prefs.serverId) (System.nanoTime() - t0) / 1_000_000 else null
    } catch (_: Exception) {
        null
    }

    fun networkChanged(available: Boolean) {
        lastFailedRace = 0
        scope.launch(Dispatchers.IO) { http.connectionPool.evictAll() }
        if (!available) online.value = false
    }

    // ---- plumbing ----

    private fun url(path: String, vararg q: Pair<String, Any?>): HttpUrl {
        val b = "$SERVER$path".toHttpUrl().newBuilder()
        for ((k, v) in q) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use {
            if (!it.isSuccessful) throw ApiException(it.code, "Server error ${it.code}")
            online.value = true
            it.body!!.string()
        }
    }

    private suspend fun get(path: String, vararg q: Pair<String, Any?>) = JSONObject(send(Request.Builder().url(url(path, *q)).build()).ifEmpty { "{}" })

    private suspend fun post(path: String, body: JSONObject? = null, vararg q: Pair<String, Any?>): String =
        send(Request.Builder().url(url(path, *q)).post((body?.toString() ?: "").toRequestBody(json)).build())

    private suspend fun delete(path: String) = send(Request.Builder().url(url(path)).delete().build())

    // ---- browsing ----

    private val fields = "Overview,MediaStreams,MediaSources,Genres,People,ChildCount,Chapters,Trickplay,ParentId"
    private val listFields = "Overview,ChildCount,MediaSources"
    private val imageTypes = "Primary,Backdrop,Thumb,Logo"

    suspend fun views() = Item.list(get("/UserViews", "userId" to prefs.userId)).filter { it.collectionType in setOf("movies", "tvshows", "homevideos", "boxsets", "musicvideos", null) }

    suspend fun resume() = Item.list(get("/UserItems/Resume", "userId" to prefs.userId, "mediaTypes" to "Video", "limit" to 24, "fields" to listFields, "enableImageTypes" to imageTypes))

    suspend fun nextUp() = Item.list(get("/Shows/NextUp", "userId" to prefs.userId, "limit" to 24, "fields" to listFields, "enableResumable" to false, "enableImageTypes" to imageTypes))

    suspend fun latest(parentId: String): List<Item> {
        val body = send(Request.Builder().url(url("/Items/Latest", "userId" to prefs.userId, "parentId" to parentId, "limit" to 24, "fields" to listFields, "enableImageTypes" to imageTypes)).build())
        return org.json.JSONArray(body).objects().map(::Item)
    }

    data class Page(val items: List<Item>, val total: Int)

    suspend fun items(
        parentId: String? = null,
        types: String? = null,
        sort: String = "SortName,Ascending",
        filters: String? = null,
        search: String? = null,
        start: Int = 0,
        limit: Int = 100,
        recursive: Boolean = true,
    ): Page {
        val (by, order) = sort.split(',').let { it[0] to it.getOrElse(1) { "Ascending" } }
        val o = get(
            "/Items", "userId" to prefs.userId, "parentId" to parentId, "includeItemTypes" to types, "sortBy" to by, "sortOrder" to order,
            "filters" to filters, "searchTerm" to search, "startIndex" to start, "limit" to limit, "recursive" to recursive,
            "fields" to listFields, "enableImageTypes" to imageTypes, "imageTypeLimit" to 1,
        )
        return Page(Item.list(o), o.optInt("TotalRecordCount"))
    }

    suspend fun item(id: String) = Item(get("/Items/$id", "userId" to prefs.userId, "fields" to fields))

    suspend fun seasons(seriesId: String) = Item.list(get("/Shows/$seriesId/Seasons", "userId" to prefs.userId, "fields" to listFields))

    suspend fun episodes(seriesId: String, seasonId: String? = null, startItemId: String? = null, limit: Int? = null) = Item.list(
        get("/Shows/$seriesId/Episodes", "userId" to prefs.userId, "seasonId" to seasonId, "startItemId" to startItemId, "limit" to limit, "fields" to "$listFields,MediaStreams"),
    )

    suspend fun segments(id: String) = get("/MediaSegments/$id").list("Items").map(Segment::from)

    suspend fun setPlayed(id: String, played: Boolean) {
        if (played) post("/UserPlayedItems/$id", null, "userId" to prefs.userId) else delete("/UserPlayedItems/$id?userId=${prefs.userId}")
    }

    suspend fun setFavorite(id: String, on: Boolean) {
        if (on) post("/UserFavoriteItems/$id", null, "userId" to prefs.userId) else delete("/UserFavoriteItems/$id?userId=${prefs.userId}")
    }

    suspend fun userData(id: String) = UserData.from(get("/UserItems/$id/UserData", "userId" to prefs.userId))

    suspend fun setUserData(id: String, positionMs: Long, played: Boolean, at: Instant) {
        post(
            "/UserItems/$id/UserData",
            JSONObject().put("PlaybackPositionTicks", positionMs * TICKS_PER_MS).put("Played", played).put("LastPlayedDate", at.toString()),
            "userId" to prefs.userId,
        )
    }

    /** Server-side language preferences, used when the app has no defaults of its own. */
    suspend fun loadUserConfig() {
        val c = get("/Users/Me").optJSONObject("Configuration") ?: return
        prefs.serverAudioLang = c.s("AudioLanguagePreference")
        prefs.serverSubLang = c.s("SubtitleLanguagePreference")
        prefs.serverSubMode = c.s("SubtitleMode")
    }

    // ---- playback ----

    /** PlaybackInfo; stream indices only apply when the media source is named too. */
    suspend fun playbackInfo(
        id: String,
        profile: JSONObject,
        maxBitrate: Int,
        startMs: Long,
        audio: Int?,
        sub: Int?,
        directPlay: Boolean = true,
        directStream: Boolean = true,
    ): Pair<String, Source> {
        val o = JSONObject(
            post(
                "/Items/$id/PlaybackInfo", JSONObject().put("DeviceProfile", profile),
                "userId" to prefs.userId, "MaxStreamingBitrate" to maxBitrate, "StartTimeTicks" to startMs * TICKS_PER_MS,
                "AudioStreamIndex" to audio, "SubtitleStreamIndex" to sub, "MediaSourceId" to id,
                "EnableDirectPlay" to directPlay, "EnableDirectStream" to directStream, "EnableTranscoding" to true,
                "AllowVideoStreamCopy" to true, "AllowAudioStreamCopy" to true,
            ),
        )
        if (o.s("ErrorCode") != null) throw ApiException(0, "Can't play this: ${o.s("ErrorCode")}")
        val source = o.list("MediaSources").map(Source::from).firstOrNull() ?: throw ApiException(0, "No playable source")
        return o.optString("PlaySessionId") to source
    }

    fun directUrl(id: String, sourceId: String = id) = "$SERVER/Videos/$id/stream?static=true&mediaSourceId=$sourceId"

    fun downloadUrl(id: String) = "$SERVER/Items/$id/Download"

    fun subtitleUrl(id: String, sourceId: String, index: Int, format: String) = "$SERVER/Videos/$id/$sourceId/Subtitles/$index/0/Stream.$format"

    fun trickplayUrl(id: String, width: Int, tile: Int) = "$SERVER/Videos/$id/Trickplay/$width/$tile.jpg"

    /** Sized WebP; [tag] makes the cache key change when the artwork does. */
    fun imageUrl(id: String, type: String, width: Int, tag: String? = null) =
        "$SERVER/Items/$id/Images/$type?fillWidth=$width&quality=85&format=Webp" + (tag?.let { "&tag=$it" } ?: "")

    fun personImageUrl(id: String, width: Int) = "$SERVER/Items/$id/Images/Primary?fillWidth=$width&quality=85&format=Webp"

    fun userImageUrl(width: Int) = "$SERVER/Users/${prefs.userId}/Images/Primary?fillWidth=$width&quality=85&format=Webp"

    fun report(path: String, body: JSONObject) = scope.launch(Dispatchers.IO) { runCatching { post("/Sessions/Playing$path", body) } }

    suspend fun reportNow(path: String, body: JSONObject) = post("/Sessions/Playing$path", body)

    suspend fun logout() = runCatching { post("/Sessions/Logout") }

    // ---- login ----

    data class Login(val token: String, val userId: String, val userName: String)

    suspend fun login(address: String, user: String, password: String): Login = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$address/Users/AuthenticateByName")
            .header("Authorization", authHeader(prefs.deviceId, null))
            .post(JSONObject().put("Username", user).put("Pw", password).toString().toRequestBody(json)).build()
        pingHttp.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).execute().use {
            if (it.code == 401) throw ApiException(401, "Wrong username or password")
            if (!it.isSuccessful) throw ApiException(it.code, "Login failed (${it.code})")
            loginFrom(JSONObject(it.body!!.string()))
        }
    }

    /** Quick Connect: returns (code, secret). */
    suspend fun quickConnectStart(address: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$address/QuickConnect/Initiate").header("Authorization", authHeader(prefs.deviceId, null)).post(ByteArray(0).toRequestBody()).build()
        pingHttp.newCall(req).execute().use {
            if (!it.isSuccessful) throw ApiException(it.code, "Quick Connect is turned off on this server")
            val o = JSONObject(it.body!!.string())
            o.getString("Code") to o.getString("Secret")
        }
    }

    /** Polls until approved, then logs in. */
    suspend fun quickConnectWait(address: String, secret: String): Login = withContext(Dispatchers.IO) {
        while (true) {
            val ok = pingHttp.newCall(Request.Builder().url("$address/QuickConnect/Connect?secret=$secret").build()).execute().use {
                it.isSuccessful && JSONObject(it.body!!.string()).optBoolean("Authenticated")
            }
            if (ok) break
            delay(2_000)
        }
        val req = Request.Builder().url("$address/Users/AuthenticateWithQuickConnect").header("Authorization", authHeader(prefs.deviceId, null))
            .post(JSONObject().put("Secret", secret).toString().toRequestBody(json)).build()
        pingHttp.newCall(req).execute().use {
            if (!it.isSuccessful) throw ApiException(it.code, "Quick Connect failed")
            loginFrom(JSONObject(it.body!!.string()))
        }
    }

    private fun loginFrom(o: JSONObject) = Login(o.getString("AccessToken"), o.getJSONObject("User").getString("Id"), o.getJSONObject("User").optString("Name"))

    companion object {
        fun authHeader(deviceId: String, token: String?) =
            "MediaBrowser Client=\"Novafin\", Device=\"${Build.MODEL.replace("\"", "")}\", DeviceId=\"$deviceId\", Version=\"${BuildConfig.VERSION_NAME}\"" +
                (token?.let { ", Token=\"$it\"" } ?: "")

        fun publicInfo(base: String, client: OkHttpClient = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()): ServerInfo =
            client.newCall(Request.Builder().url("$base/System/Info/Public").build()).execute().use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                val o = JSONObject(it.body!!.string())
                ServerInfo(o.getString("Id"), o.optString("ServerName"), o.optString("Version"), o.s("LocalAddress"))
            }

    }
}

