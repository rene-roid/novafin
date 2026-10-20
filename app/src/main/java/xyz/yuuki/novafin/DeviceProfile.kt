package xyz.yuuki.novafin

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

/** Streaming and download presets. Bitrates in bits per second; [hevc] applies when the server encodes HEVC. */
enum class Quality(val label: String, val height: Int, val video: Int, val hevc: Int, val audio: Int) {
    Auto("Auto", 0, 0, 0, 0),
    Original("Original", 0, 0, 0, 0),
    High("High · 1080p", 1080, 8_000_000, 5_000_000, 192_000),
    Medium("Medium · 720p", 720, 3_000_000, 2_000_000, 160_000),
    Low("Low · 480p", 480, 1_200_000, 800_000, 128_000),
    DataSaver("Data saver · 360p", 360, 600_000, 600_000, 96_000);

    val total get() = if (video == 0) ORIGINAL_BITRATE else video + audio

    /** One step lower, for auto step-down. */
    val lower get() = when (this) {
        Auto, Original -> High
        High -> Medium
        Medium -> Low
        else -> DataSaver
    }

    /** Estimated bytes for [ms] of video at this preset, or null for the original. */
    fun bytesFor(ms: Long) = if (video == 0) null else total.toLong() * ms / 8000

    companion object {
        const val ORIGINAL_BITRATE = 200_000_000
        fun of(name: String?, fallback: Quality = Original) = entries.firstOrNull { it.name == name } ?: fallback

        /** Highest preset that fits in [bps] with headroom, for "Auto". */
        fun forBandwidth(bps: Long) = listOf(High, Medium, Low).firstOrNull { it.total < bps * 0.7 } ?: DataSaver
    }
}

/** What this device decodes. [tenBit]: 10-bit (Main10) support. */
data class VideoCap(val maxWidth: Int, val maxHeight: Int, val tenBit: Boolean)
data class Caps(val video: Map<String, VideoCap>, val audio: Set<String>)

object DeviceCaps {
    val caps: Caps by lazy { probe() }

    private val videoMimes = mapOf("h264" to "video/avc", "hevc" to "video/hevc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9", "vp8" to "video/x-vnd.on2.vp8", "mpeg4" to "video/mp4v-es")
    private val audioMimes = mapOf(
        "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "opus" to "audio/opus", "flac" to "audio/flac", "vorbis" to "audio/vorbis",
        "ac3" to "audio/ac3", "eac3" to "audio/eac3", "alac" to "audio/alac", "pcm_s16le" to "audio/raw",
    )

    private fun probe(): Caps {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val video = videoMimes.mapNotNull { (codec, mime) ->
            val decoders = infos.filter { i -> i.supportedTypes.any { it.equals(mime, true) } }
            if (decoders.isEmpty()) return@mapNotNull null
            val caps = decoders.map { it.getCapabilitiesForType(mime) }
            val w = caps.maxOf { it.videoCapabilities?.supportedWidths?.upper ?: 1920 }
            val h = caps.maxOf { it.videoCapabilities?.supportedHeights?.upper ?: 1080 }
            val main10 = when (codec) {
                "hevc" -> CodecProfileLevel.HEVCProfileMain10
                "av1" -> CodecProfileLevel.AV1ProfileMain10
                "vp9" -> CodecProfileLevel.VP9Profile2
                else -> -1
            }
            codec to VideoCap(w, h, caps.any { c -> c.profileLevels.any { it.profile == main10 } })
        }.toMap()
        val audio = audioMimes.filter { (_, mime) -> infos.any { i -> i.supportedTypes.any { it.equals(mime, true) } } }.keys
        // ExoPlayer reads these with its own extractors and decoders.
        return Caps(video, audio + setOf("aac", "mp3", "opus", "flac", "vorbis"))
    }
}

/**
 * Device profile for PlaybackInfo. Direct play covers everything ExoPlayer can read; transcodes are
 * HLS (MPEG-TS) in HEVC when the server can encode it, else H.264, with AAC (stereo when [downmix]).
 * Text subtitles are always fetched as separate files, image ones (PGS, VobSub) are burned in
 * when the video is transcoded.
 */
fun buildProfile(caps: Caps, maxBitrate: Int, downmix: Boolean, skipAv1: Boolean = false): JSONObject {
    val video = caps.video.keys.filter { !(skipAv1 && it == "av1") }
    val transcodeVideo = listOf("hevc", "h264").filter { it in caps.video }.ifEmpty { listOf("h264") }
    fun cond(prop: String, value: Any, op: String = "LessThanEqual") =
        JSONObject().put("Condition", op).put("Property", prop).put("Value", value.toString()).put("IsRequired", false)

    val codecProfiles = JSONArray()
    for ((codec, cap) in caps.video) {
        val conditions = JSONArray().put(cond("Width", cap.maxWidth)).put(cond("Height", cap.maxHeight))
        if (!cap.tenBit && codec in setOf("hevc", "av1", "vp9")) conditions.put(cond("VideoBitDepth", 8))
        codecProfiles.put(JSONObject().put("Type", "Video").put("Codec", codec).put("Conditions", conditions))
    }

    val subs = JSONArray()
    for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "pgssub", "pgs", "dvdsub", "vobsub", "dvbsub")) subs.put(JSONObject().put("Format", f).put("Method", "Embed"))
    for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")) subs.put(JSONObject().put("Format", f).put("Method", "External"))

    return JSONObject()
        .put("Name", "Novafin")
        .put("MaxStreamingBitrate", maxBitrate)
        .put("MaxStaticBitrate", maxBitrate)
        .put(
            "DirectPlayProfiles",
            JSONArray().put(
                JSONObject().put("Type", "Video").put("Container", "mkv,webm,mp4,m4v,mov,ts,mpegts,avi")
                    .put("VideoCodec", video.joinToString(",")).put("AudioCodec", caps.audio.joinToString(",")),
            ),
        )
        .put(
            "TranscodingProfiles",
            JSONArray().put(
                // MPEG-TS segments: Jellyfin's fMP4 segments can carry negative timestamps after the
                // transcode restarts (seek, resumed download), which ExoPlayer rejects.
                JSONObject().put("Type", "Video").put("Container", "ts").put("Protocol", "hls").put("Context", "Streaming")
                    .put("VideoCodec", transcodeVideo.joinToString(",")).put("AudioCodec", "aac")
                    .put("MaxAudioChannels", if (downmix) "2" else "6").put("MinSegments", 1).put("BreakOnNonKeyFrames", true),
            ),
        )
        .put("CodecProfiles", codecProfiles)
        .put("SubtitleProfiles", subs)
}
