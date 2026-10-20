package xyz.yuuki.novafin

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun JSONObject.s(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
fun JSONObject.list(k: String): List<JSONObject> = optJSONArray(k).objects()

const val TICKS_PER_MS = 10_000L

/**
 * A Jellyfin BaseItemDto, read straight from its JSON so the same object is what we save for
 * offline browsing ([json]) and what the screens read.
 */
class Item(val json: JSONObject) {
    val id: String get() = json.getString("Id")
    val name: String get() = json.s("Name") ?: ""
    val type: String get() = json.s("Type") ?: ""
    val isFolder get() = json.optBoolean("IsFolder")
    val collectionType get() = json.s("CollectionType")
    val overview get() = json.s("Overview")
    val year get() = json.optInt("ProductionYear").takeIf { it > 0 }
    val officialRating get() = json.s("OfficialRating")
    val communityRating get() = json.optDouble("CommunityRating").takeIf { !it.isNaN() }
    val runTimeMs get() = json.optLong("RunTimeTicks") / TICKS_PER_MS
    val seriesId get() = json.s("SeriesId")
    val seriesName get() = json.s("SeriesName")
    val seasonId get() = json.s("SeasonId")
    val seasonName get() = json.s("SeasonName")
    val index get() = json.optInt("IndexNumber", -1).takeIf { it >= 0 }
    val parentIndex get() = json.optInt("ParentIndexNumber", -1).takeIf { it >= 0 }
    val childCount get() = json.optInt("ChildCount")
    val genres get() = json.optJSONArray("Genres")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
    val people get() = json.list("People").filter { it.s("Type") == "Actor" }.map { Person(it.optString("Id"), it.optString("Name"), it.s("Role"), it.optJSONObject("ImageTags")?.s("Primary") ?: it.s("PrimaryImageTag")) }
    val userData get() = UserData.from(json.optJSONObject("UserData") ?: JSONObject())
    val sources get() = json.list("MediaSources").map(Source::from)
    val streams get() = (json.list("MediaSources").firstOrNull()?.list("MediaStreams") ?: json.list("MediaStreams")).map(Stream::from)
    val chapters get() = json.list("Chapters").map { it.optLong("StartPositionTicks") / TICKS_PER_MS }
    val isEpisode get() = type == "Episode"
    val isSeries get() = type == "Series"
    val isSeason get() = type == "Season"
    val playable get() = type == "Movie" || type == "Episode" || type == "Video" || type == "MusicVideo"

    /** "S1:E5" for episodes. */
    val episodeLabel get() = if (isEpisode) "S${parentIndex ?: 0}:E${index ?: 0}" else null

    /** Where remembered tracks are kept: per show, so every episode shares them, or per movie. */
    val memoryKey get() = seriesId ?: id

    fun imageTag(kind: String) = json.optJSONObject("ImageTags")?.s(kind)
    val backdropTag get() = json.optJSONArray("BackdropImageTags")?.optString(0)?.ifEmpty { null }

    /** Best backdrop: own, else the show's. */
    val backdrop: Img?
        get() = backdropTag?.let { Img(id, "Backdrop", it) }
            ?: json.s("ParentBackdropItemId")?.let { Img(it, "Backdrop", json.optJSONArray("ParentBackdropImageTags")?.optString(0)) }

    /** Poster: own, else the show's. */
    val poster: Img?
        get() = imageTag("Primary")?.let { Img(id, "Primary", it) } ?: seriesId?.let { sid -> json.s("SeriesPrimaryImageTag")?.let { Img(sid, "Primary", it) } }

    /** Landscape still: the episode's own image, a thumb, then a backdrop. */
    val thumb: Img?
        get() = (if (isEpisode) imageTag("Primary")?.let { Img(id, "Primary", it) } else null)
            ?: imageTag("Thumb")?.let { Img(id, "Thumb", it) }
            ?: json.s("ParentThumbItemId")?.let { Img(it, "Thumb", json.s("ParentThumbImageTag")) }
            ?: backdrop

    val logo: Img?
        get() = imageTag("Logo")?.let { Img(id, "Logo", it) } ?: json.s("ParentLogoItemId")?.let { Img(it, "Logo", json.s("ParentLogoImageTag")) }

    fun withUserData(u: UserData) = Item(JSONObject(json.toString()).put("UserData", u.toJson()))

    override fun equals(other: Any?) = other is Item && other.json.toString() == json.toString()
    override fun hashCode() = id.hashCode()

    companion object {
        fun list(o: JSONObject) = o.list("Items").map(::Item)
    }
}

/** An image of item [id]; [tag] changes when the artwork does. */
data class Img(val id: String, val type: String, val tag: String?)

data class Person(val id: String, val name: String, val role: String?, val imageTag: String?)

data class UserData(
    val positionMs: Long = 0,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val lastPlayed: Instant? = null,
    val unplayed: Int = 0,
    val playedPercentage: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().put("PlaybackPositionTicks", positionMs * TICKS_PER_MS).put("Played", played)
        .put("IsFavorite", favorite).put("LastPlayedDate", lastPlayed?.toString()).put("UnplayedItemCount", unplayed)
        .put("PlayedPercentage", playedPercentage)

    companion object {
        fun from(o: JSONObject) = UserData(
            o.optLong("PlaybackPositionTicks") / TICKS_PER_MS, o.optBoolean("Played"), o.optBoolean("IsFavorite"),
            o.s("LastPlayedDate")?.let { runCatching { Instant.parse(it) }.getOrNull() }, o.optInt("UnplayedItemCount"),
            o.optDouble("PlayedPercentage", 0.0),
        )
    }
}

data class Stream(
    val index: Int,
    val type: String,
    val codec: String?,
    val language: String?,
    val title: String?,
    val displayTitle: String,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    val isText: Boolean,
    val channels: Int,
    val bitrate: Int,
    val height: Int,
    val width: Int,
    val bitDepth: Int,
    val deliveryUrl: String?,
    val deliveryMethod: String?,
) {
    val isAudio get() = type == "Audio"
    val isSub get() = type == "Subtitle"
    val isVideo get() = type == "Video"

    /** "Signs & Songs" or forced subtitles, as opposed to a full track. */
    val isSigns get() = isForced || title?.let { t -> SIGNS.any { t.contains(it, true) } } == true

    companion object {
        private val SIGNS = listOf("sign", "song", "forced")

        fun from(o: JSONObject) = Stream(
            o.optInt("Index"), o.optString("Type"), o.s("Codec")?.lowercase(), o.s("Language"), o.s("Title"),
            o.s("DisplayTitle") ?: o.s("Title") ?: o.s("Language") ?: "Track ${o.optInt("Index")}",
            o.optBoolean("IsDefault"), o.optBoolean("IsForced"), o.optBoolean("IsExternal"), o.optBoolean("IsTextSubtitleStream"),
            o.optInt("Channels"), o.optInt("BitRate"), o.optInt("Height"), o.optInt("Width"), o.optInt("BitDepth"),
            o.s("DeliveryUrl"), o.s("DeliveryMethod"),
        )
    }
}

data class Source(
    val id: String,
    val container: String?,
    val bitrate: Int,
    val size: Long,
    val streams: List<Stream>,
    val directPlay: Boolean,
    val directStream: Boolean,
    val transcodingUrl: String?,
    val defaultAudio: Int?,
    val defaultSub: Int?,
) {
    val video get() = streams.firstOrNull { it.isVideo }

    companion object {
        fun from(o: JSONObject) = Source(
            o.getString("Id"), o.s("Container"), o.optInt("Bitrate"), o.optLong("Size"), o.list("MediaStreams").map(Stream::from),
            o.optBoolean("SupportsDirectPlay"), o.optBoolean("SupportsDirectStream"), o.s("TranscodingUrl"),
            o.optInt("DefaultAudioStreamIndex", -1).takeIf { it >= 0 }, o.optInt("DefaultSubtitleStreamIndex", -1).takeIf { it >= 0 },
        )
    }
}

data class Segment(val type: String, val startMs: Long, val endMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("Type", type).put("StartTicks", startMs * TICKS_PER_MS).put("EndTicks", endMs * TICKS_PER_MS)

    companion object {
        fun from(o: JSONObject) = Segment(o.optString("Type"), o.optLong("StartTicks") / TICKS_PER_MS, o.optLong("EndTicks") / TICKS_PER_MS)
    }
}

data class Trickplay(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int, val count: Int, val intervalMs: Int)

/** Trickplay info for [itemId], the smallest resolution, which is plenty for a seek preview. */
fun Item.trickplay(): Trickplay? {
    val byRes = json.optJSONObject("Trickplay")?.optJSONObject(id) ?: return null
    val best = byRes.keys().asSequence().mapNotNull { byRes.optJSONObject(it) }.minByOrNull { it.optInt("Width") } ?: return null
    return Trickplay(best.optInt("Width"), best.optInt("Height"), best.optInt("TileWidth"), best.optInt("TileHeight"), best.optInt("ThumbnailCount"), best.optInt("Interval"))
}

class ApiException(val code: Int, message: String) : Exception(message)
