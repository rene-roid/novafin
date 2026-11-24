package xyz.yuuki.novafin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** One way to reach the server: LAN, Tailscale or a public domain. [tv]: a Chromecast can reach it too. */
data class Address(val url: String, val label: String, val tv: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("label", label).put("tv", tv)

    companion object {
        fun from(o: JSONObject) = Address(o.getString("url"), o.optString("label"), o.optBoolean("tv"))
    }
}

/** One signed-in user on one server: what [Prefs] keeps per session. There is no password to keep. */
data class Account(
    val serverId: String,
    val serverName: String?,
    val serverVersion: String?,
    val userId: String,
    val userName: String?,
    val token: String,
    val addresses: List<Address>,
    val active: String? = null,
    val pinned: String? = null,
    val isAdmin: Boolean = false,
    val audioLang: String? = null,
    val subLang: String? = null,
    val subMode: String? = null,
) {
    fun same(o: Account) = serverId == o.serverId && userId == o.userId

    fun toJson(): JSONObject = JSONObject().put("serverId", serverId).put("serverName", serverName).put("serverVersion", serverVersion)
        .put("userId", userId).put("userName", userName).put("token", token).put("addresses", JSONArray(addresses.map { it.toJson() }))
        .put("active", active).put("pinned", pinned).put("isAdmin", isAdmin).put("audioLang", audioLang).put("subLang", subLang).put("subMode", subMode)

    companion object {
        fun from(o: JSONObject) = Account(
            o.getString("serverId"), o.s("serverName"), o.s("serverVersion"), o.getString("userId"), o.s("userName"), o.getString("token"),
            o.optJSONArray("addresses").objects().map(Address::from), o.s("active"), o.s("pinned"), o.optBoolean("isAdmin"),
            o.s("audioLang"), o.s("subLang"), o.s("subMode"),
        )
    }
}

/**
 * Makes [target] the account in use: the one it replaces joins the saved ones, most recent first.
 * A saved copy of the same user on the same server is replaced, never duplicated.
 */
fun switchAccount(current: Account?, others: List<Account>, target: Account): Pair<Account, List<Account>> =
    target to (listOfNotNull(current) + others).filterNot { it.same(target) }

/** What the user last picked for a show or movie. Languages are Jellyfin's ISO 639-2 codes ("jpn"). */
data class TrackMemory(
    val audioLang: String? = null,
    val audioTitle: String? = null,
    val audioChannels: Int = 0,
    val subLang: String? = null,
    val subTitle: String? = null,
    val subForced: Boolean = false,
    val subOff: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("audioLang", audioLang).put("audioTitle", audioTitle).put("audioChannels", audioChannels)
        .put("subLang", subLang).put("subTitle", subTitle).put("subForced", subForced).put("subOff", subOff).put("updatedAt", updatedAt)

    companion object {
        fun from(o: JSONObject) = TrackMemory(
            o.s("audioLang"), o.s("audioTitle"), o.optInt("audioChannels"), o.s("subLang"), o.s("subTitle"),
            o.optBoolean("subForced"), o.optBoolean("subOff"), o.optLong("updatedAt"),
        )
    }
}

/** Rows the home screen can show; [Prefs.homeSections] holds the enabled ones in order. */
enum class HomeSection(val label: String) {
    Resume("Continue watching"),
    NextUp("Next up"),
    Latest("Recently added"),
    TopUnwatched("Top rated, not watched yet"),
    Recommended("Because you watched"),
    Libraries("Libraries"),
}

/** Saved order first, then rows the saved order doesn't know yet, minus the hidden ones. */
fun homeLayout(order: List<String>?, hidden: Set<String>): List<HomeSection> {
    val known = order.orEmpty().mapNotNull { n -> HomeSection.entries.firstOrNull { it.name == n } }
    return (known + (HomeSection.entries - known.toSet())).filter { it.name !in hidden }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("novafin", Context.MODE_PRIVATE)

    // ---- session ----
    val deviceId: String = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
    var serverId by str("serverId")
    var serverName by str("serverName")
    var serverVersion by str("serverVersion")
    var userId by str("userId")
    var userName by str("userName")
    /** Set while [Api] still points at the previous account's server, so its requests carry no token until it has moved. */
    @Volatile var switching = false
    var token: String?
        get() = if (switching) null else p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var addresses: List<Address>
        get() = p.getString("addresses", null)?.let { JSONArray(it).objects().map(Address::from) } ?: emptyList()
        set(v) = p.edit().putString("addresses", JSONArray(v.map { it.toJson() }).toString()).apply()
    var active by str("active")
    /** Address the user pinned; null means automatic. */
    var pinned by str("pinned")

    // Server-side user preferences, used when nothing is remembered and no app default is set.
    var serverAudioLang by str("serverAudioLang")
    var serverSubLang by str("serverSubLang")
    var serverSubMode by str("serverSubMode")
    var isAdmin by bool("isAdmin", false)

    val loggedIn get() = token != null && addresses.isNotEmpty()

    /** The account in use, as stored in the flat keys above. */
    val account: Account?
        get() = Account(
            serverId ?: return null, serverName, serverVersion, userId ?: return null, userName, p.getString("token", null) ?: return null,
            addresses, active, pinned, isAdmin, serverAudioLang, serverSubLang, serverSubMode,
        )
    /** Signed-in accounts other than the one in use. */
    var otherAccounts: List<Account>
        get() = p.getString("accounts", null)?.let { JSONArray(it).objects().map(Account::from) } ?: emptyList()
        set(v) = p.edit().putString("accounts", JSONArray(v.map { it.toJson() }).toString()).apply()

    /** One write, so being killed mid-switch can't lose or duplicate an account. */
    fun setAccounts(current: Account, others: List<Account>) = p.edit()
        .putString("serverId", current.serverId).putString("serverName", current.serverName).putString("serverVersion", current.serverVersion)
        .putString("userId", current.userId).putString("userName", current.userName).putString("token", current.token)
        .putString("addresses", JSONArray(current.addresses.map { it.toJson() }).toString()).putString("active", current.active).putString("pinned", current.pinned)
        .putBoolean("isAdmin", current.isAdmin).putString("serverAudioLang", current.audioLang).putString("serverSubLang", current.subLang).putString("serverSubMode", current.subMode)
        .putString("accounts", JSONArray(others.map { it.toJson() }).toString()).apply()

    // ---- playback ----
    var autoplayNext by bool("autoplayNext", true)
    /** After a movie or a show's last episode, suggest a similar movie (never autoplayed). */
    var suggestRelated by bool("suggestRelated", true)
    var autoSkipIntro by bool("autoSkipIntro", false)
    var autoPip by bool("autoPip", true)
    var backgroundAudio by bool("backgroundAudio", false)
    var zoomFill by bool("zoomFill", false)
    var preferTranscodeAv1 by bool("preferTranscodeAv1", false)

    // ---- languages ----
    /** Preferred languages, most wanted first (ISO 639-2 codes). */
    var audioLangs by list("audioLang")
    var subLangs by list("subLang")
    /** Jellyfin's SubtitleMode: Default, Always, OnlyForced, None, Smart. Null follows the server user. */
    var subMode by str("subMode")

    // ---- subtitle style ----
    var subSize by float("subSize", 1f)
    var subColor by int("subColor", 0xFFFFFFFF.toInt())
    var subBackground by bool("subBackground", false)
    var subBottom by float("subBottom", .06f)

    // ---- quality ----
    var qualityWifi by str("qualityWifi", Quality.Original.name)
    var qualityMobile by str("qualityMobile", Quality.Medium.name)
    var smartOriginal by bool("smartOriginal", true)
    var downmix by bool("downmix", true)
    var smallImagesOnMobile by bool("smallImagesOnMobile", true)

    // ---- downloads ----
    var downloadQuality by str("downloadQuality", Quality.Medium.name)
    var keepBothAudio by bool("keepBothAudio", false)
    var wifiOnlyDownloads by bool("wifiOnly", true)
    /** GB, 0 = unlimited. */
    var storageLimitGb by int("storageLimitGb", 0)
    var storageSd by bool("storageSd", false)
    /** Days after watching, -1 = never. */
    var autoDeleteDays by int("autoDeleteDays", -1)
    /** Next unwatched episodes kept downloaded for shows in [smartShows]. */
    var smartEpisodes by int("smartEpisodes", 3)
    var smartShows: Set<String>
        get() = p.getStringSet("smartShows:$serverId", null) ?: emptySet()
        set(v) = p.edit().putStringSet("smartShows:$serverId", v).apply()
    init {
        // One global set before there were accounts: it belongs to the server signed in back then.
        val old = p.getStringSet("smartShows", null)
        if (old != null && serverId != null) p.edit().putStringSet("smartShows:$serverId", old).remove("smartShows").apply()
    }
    var imageCacheMb by int("imageCacheMb", 256)

    // ---- home ----
    /** Enabled rows in order. Hidden ones are stored rather than shown ones, so rows added in an update appear. */
    var homeSections: List<HomeSection>
        get() = homeLayout(p.getString("homeOrder", null)?.split(','), p.getString("homeHidden", null)?.split(',')?.toSet().orEmpty())
        set(v) = p.edit().putString("homeOrder", v.joinToString(",") { it.name })
            .putString("homeHidden", (HomeSection.entries - v.toSet()).joinToString(",") { it.name }).apply()

    /** Where "Add to playlist" puts new items: false = the end (Jellyfin's default), true = the start. */
    var playlistAddToTop by bool("playlistAddToTop", false)

    /** Newest first; one per line since a search can contain commas. */
    var recentSearches: List<String>
        get() = p.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = p.edit().putString("recentSearches", v.take(8).joinToString("\n")).apply()

    // ---- per-library sort ----
    fun sort(library: String) = p.getString("sort:$library", null) ?: "SortName,Ascending"
    fun setSort(library: String, v: String) = p.edit().putString("sort:$library", v).apply()

    // ---- track memory: per series for episodes, per item for movies ----
    fun memory(key: String): TrackMemory? = p.getString("mem:$key", null)?.let { TrackMemory.from(JSONObject(it)) }
    fun remember(key: String, m: TrackMemory) = p.edit().putString("mem:$key", m.toJson().toString()).apply()

    fun logout() = p.edit().remove("token").remove("userId").remove("addresses").remove("active").remove("pinned").apply()

    private fun list(k: String) = prop({ p.getString(k, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList() }) { putString(k, it.joinToString(",")) }
    private fun str(k: String, d: String? = null) = prop({ p.getString(k, d) }) { putString(k, it) }
    private fun int(k: String, d: Int) = prop({ p.getInt(k, d) }) { putInt(k, it) }
    private fun float(k: String, d: Float) = prop({ p.getFloat(k, d) }) { putFloat(k, it) }
    private fun bool(k: String, d: Boolean) = prop({ p.getBoolean(k, d) }) { putBoolean(k, it) }

    private fun <T> prop(get: () -> T, put: android.content.SharedPreferences.Editor.(T) -> Unit) = object : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = get()
        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = p.edit().apply { put(value) }.apply()
    }
}
