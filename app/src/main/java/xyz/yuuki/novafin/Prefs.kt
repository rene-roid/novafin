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

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("novafin", Context.MODE_PRIVATE)

    // ---- session ----
    val deviceId: String = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
    var serverId by str("serverId")
    var serverName by str("serverName")
    var serverVersion by str("serverVersion")
    var userId by str("userId")
    var userName by str("userName")
    var token by str("token")
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

    val loggedIn get() = token != null && addresses.isNotEmpty()

    // ---- playback ----
    var autoplayNext by bool("autoplayNext", true)
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
        get() = p.getStringSet("smartShows", null) ?: emptySet()
        set(v) = p.edit().putStringSet("smartShows", v).apply()
    var imageCacheMb by int("imageCacheMb", 256)

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
