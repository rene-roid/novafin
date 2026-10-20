package xyz.yuuki.novafin

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.MediaQueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class Method(val label: String) { DirectPlay("Direct play"), Remux("Remux"), Transcode("Transcode"), Offline("Downloaded") }

/** What is loaded in the player and how. [audio]/[sub] are Jellyfin stream indices, sub -1 = off. */
data class NowPlaying(
    val item: Item,
    val sourceId: String,
    val playSessionId: String?,
    val method: Method,
    val audio: Int?,
    val sub: Int,
    /** The preset asked for (may be Auto) and the one in use. */
    val quality: Quality,
    val effective: Quality,
    val streams: List<Stream>,
    val segments: List<Segment> = emptyList(),
    val next: Item? = null,
    val note: String? = null,
    val transcodeInfo: String? = null,
)

/**
 * The player and everything around it: choosing direct play, remux or transcode, picking and
 * remembering tracks, reporting progress (or queueing it when offline), skip intro, up next,
 * falling back to a transcode when the device can't decode a file, stepping quality down when the
 * connection can't keep up, and handing over to a Chromecast.
 */
class Playback(private val app: App) {
    companion object {
        /** Something is loaded; keeps the address re-race ticking while the app is in the background. */
        @Volatile var active = false

        fun defaults(p: Prefs) = TrackDefaults(
            p.audioLangs.ifEmpty { listOfNotNull(p.serverAudioLang?.ifEmpty { null }) },
            p.subLangs.ifEmpty { listOfNotNull(p.serverSubLang?.ifEmpty { null }) },
            // Picking subtitle languages in the app means wanting them when the audio is foreign.
            p.subMode ?: if (p.subLangs.isNotEmpty()) "Smart" else p.serverSubMode ?: "Default",
        )

        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }

    private val scope = MainScope()
    private val api get() = app.api
    private val prefs get() = app.prefs

    val exo: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(app.downloads.dataSource))
        // Start after 1.5 s of video instead of 2.5 s; keep up to 50 s ahead.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 50_000, 1_500, 3_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    /** Plays locally and moves to a Chromecast when one is picked. Falls back to [exo] without Play services. */
    val player: Player = runCatching {
        CastPlayer.Builder(app).setLocalPlayer(exo)
            .setRemotePlayer(RemoteCastPlayer.Builder(app).setMediaItemConverter(CastConverter()).build()).build()
    }.getOrDefault(exo)

    val now = MutableStateFlow<NowPlaying?>(null)
    val casting = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    private var loadJob: Job? = null
    private var triedTranscode = false
    private var networkRetries = 0
    private var rebuffers = mutableListOf<Long>()
    private var lastSeekAt = 0L
    private var wasReady = false
    private var started = false
    private val skipped = mutableSetOf<Segment>()

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = applyTracks()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !started) {
                    started = true
                    report("")
                } else report("/Progress")
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    lastSeekAt = System.currentTimeMillis()
                    report("/Progress")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        wasReady = true
                        networkRetries = 0
                    }
                    Player.STATE_BUFFERING -> if (wasReady && player.playWhenReady && System.currentTimeMillis() - lastSeekAt > 2_000) rebuffered()
                    Player.STATE_ENDED -> {
                        stopped(ended = true)
                        val n = now.value?.next
                        if (n != null && prefs.autoplayNext) play(n) else active = false
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) = recover(e)

            override fun onDeviceInfoChanged(info: DeviceInfo) {
                val remote = info.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (remote == casting.value) return
                casting.value = remote
                // Back from the TV: reload locally with the right tracks and method at the same spot.
                val n = now.value
                if (!remote && n != null) load(n.item, player.currentPosition, n.audio, n.sub, n.quality)
            }
        })

        // Progress every 10 s; auto skip intro.
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                val n = now.value ?: continue
                if (prefs.autoSkipIntro && !casting.value) {
                    val pos = player.currentPosition
                    n.segments.firstOrNull { it.type in setOf("Intro", "Recap") && pos in it.startMs until it.endMs - 1_000 && it !in skipped }?.let {
                        skipped += it
                        player.seekTo(it.endMs)
                    }
                }
                if (++tick % 20 == 0 && player.isPlaying) report("/Progress")
            }
        }
    }

    // ---- loading ----

    /** Plays [item] from [startMs] (null: resume where it was left), with the given tracks or the remembered ones. */
    fun play(item: Item, startMs: Long? = null, audio: Int? = null, sub: Int? = null) {
        if (now.value?.item?.id != item.id) stopped()
        triedTranscode = false
        skipped.clear()
        // Speed is per item.
        player.setPlaybackSpeed(1f)
        load(item, startMs, audio, sub, null)
    }

    private fun load(listed: Item, startMs: Long?, audio: Int?, sub: Int?, quality: Quality?, forceTranscode: Boolean = false, note: String? = null, stream: Boolean = false) {
        loadJob?.cancel()
        loading.value = true
        error.value = null
        active = true
        loadJob = scope.launch {
            try {
                doLoad(listed, startMs, audio, sub, quality, forceTranscode, note, stream)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't play this"
            } finally {
                loading.value = false
            }
        }
    }

    private suspend fun doLoad(listed: Item, startMs: Long?, audioArg: Int?, subArg: Int?, qualityArg: Quality?, forceTranscode: Boolean, note: String?, stream: Boolean) {
        val downloaded = if (stream) null else app.downloads.mediaItem(listed.id)
        val offline = app.downloads.offlineItem(listed.id)
        val item = if (downloaded != null && offline != null) {
            // Prefer the server's watch state when it answers quickly; the download plays either way.
            runCatching { kotlinx.coroutines.withTimeout(2_000) { api.userData(listed.id) } }.getOrNull()
                ?.takeIf { s -> (s.lastPlayed ?: java.time.Instant.EPOCH) > (offline.userData.lastPlayed ?: java.time.Instant.EPOCH) }
                ?.let { offline.withUserData(it) } ?: offline
        } else runCatching { api.item(listed.id) }.getOrElse { if (listed.json.has("MediaSources")) listed else throw it }

        val start = startMs ?: item.userData.positionMs.takeIf { !item.userData.played } ?: 0
        val streams = item.streams
        val mem = prefs.memory(item.memoryKey)
        val defaults = defaults(prefs)
        val audio = audioArg ?: pickAudio(streams, mem, defaults)
        val sub = subArg ?: pickSubtitle(streams, mem, defaults, streams.firstOrNull { it.index == audio }?.language)

        val quality = qualityArg ?: app.streamQuality
        val effective = if (quality == Quality.Auto) Quality.forBandwidth(DefaultBandwidthMeter.getSingletonInstance(app).bitrateEstimate) else quality
        val meta = metadata(item)
        val extras = Bundle().apply {
            putString("itemId", item.id)
            audio?.let { putInt("audio", it) }
            putInt("sub", sub)
        }

        val np: NowPlaying
        val media: MediaItem
        if (downloaded != null) {
            val subs = app.downloads.dir(item.id).listFiles().orEmpty().filter { it.name.startsWith("sub-") }.map { f ->
                val index = f.nameWithoutExtension.removePrefix("sub-").toInt()
                val s = streams.firstOrNull { it.index == index }
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setId("ext-$index").setMimeType(subtitleMime(f.extension))
                    .setLanguage(s?.language).setLabel(s?.displayTitle).build()
            }
            val dl = item.json.optJSONObject("Download")
            val original = dl?.optBoolean("original") == true
            media = downloaded.setMediaId(item.id).setSubtitleConfigurations(subs).setMediaMetadata(meta.setExtras(extras).build()).build()
            // A converted download has only the audio it was made with.
            val audioUsed = if (original) audio else dl?.optInt("audio", -1)?.takeIf { it >= 0 } ?: audio
            np = NowPlaying(item, item.id, null, Method.Offline, audioUsed, sub, Quality.of(dl?.optString("quality")), Quality.of(dl?.optString("quality")), streams)
        } else {
            val maxBitrate = effective.total
            val allowDirect = !forceTranscode && (quality == Quality.Original || quality == Quality.Auto || prefs.smartOriginal)
            val profile = buildProfile(DeviceCaps.caps, maxBitrate, prefs.downmix, prefs.preferTranscodeAv1)
            val (session, source) = api.playbackInfo(item.id, profile, maxBitrate, start, audio, sub, allowDirect, allowDirect)
            val subStream = source.streams.firstOrNull { it.index == sub }
            val builder = MediaItem.Builder().setMediaId(item.id).setMediaMetadata(meta.setExtras(extras.apply { putString("session", session) }).build())
            val method: Method
            var info: String? = null
            if (source.directPlay) {
                method = Method.DirectPlay
                builder.setUri(api.directUrl(item.id, source.id))
                // External subtitle files; embedded ones come with the stream.
                builder.setSubtitleConfigurations(source.streams.filter { it.isSub && it.isExternal && it.isText }.map { s -> externalSub(item.id, source.id, s) })
            } else {
                val url = source.transcodingUrl ?: throw ApiException(0, "The server can't play this")
                // Direct stream: the server only repackages the video, no re-encoding.
                method = if (source.directStream) Method.Remux else Method.Transcode
                val u = if (method == Method.Remux) SERVER + url else tuneTranscode(SERVER + url, effective)
                info = Uri.parse(u).let { p -> listOfNotNull(p.getQueryParameter("VideoCodec")?.substringBefore(','), p.getQueryParameter("VideoBitrate")?.toIntOrNull()?.let { "${it / 1000} kbps" }).joinToString(" · ") }
                builder.setUri(u).setMimeType(MimeTypes.APPLICATION_M3U8)
                // Text subtitles come as a file; image ones are burned into the video.
                if (subStream != null && subStream.deliveryMethod == "External" && subStream.deliveryUrl != null) {
                    builder.setSubtitleConfigurations(listOf(externalSub(item.id, source.id, subStream, SERVER + subStream.deliveryUrl)))
                }
            }
            media = builder.build()
            np = NowPlaying(item, source.id, session, method, audio, sub, quality, effective, source.streams.ifEmpty { streams }, transcodeInfo = info)
        }

        now.value = np.copy(note = note)
        wasReady = false
        started = false
        rebuffers.clear()
        player.setMediaItem(media, start)
        player.prepare()
        player.play()

        // Segments and the next episode don't hold up the start.
        val segments = item.json.optJSONArray("Segments")?.objects()?.map(Segment::from)
            ?: runCatching { api.segments(item.id) }.getOrDefault(emptyList())
        val next = nextEpisode(item)
        now.value = now.value?.takeIf { it.item.id == item.id }?.copy(segments = segments, next = next)
    }

    private suspend fun nextEpisode(item: Item): Item? {
        if (!item.isEpisode) return null
        val sid = item.seriesId ?: return null
        return runCatching { api.episodes(sid, startItemId = item.id, limit = 2).getOrNull(1) }.getOrNull()
            ?: app.downloads.items.value.values.filter { it.seriesId == sid }
                .sortedWith(compareBy({ it.parentIndex ?: 0 }, { it.index ?: 0 }))
                .firstOrNull { (it.parentIndex ?: 0) * 10_000 + (it.index ?: 0) > (item.parentIndex ?: 0) * 10_000 + (item.index ?: 0) }
    }

    private fun externalSub(id: String, sourceId: String, s: Stream, url: String? = null): MediaItem.SubtitleConfiguration {
        val ext = when (s.codec) { "ass", "ssa" -> "ass"; "srt", "subrip" -> "srt"; else -> "vtt" }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url ?: api.subtitleUrl(id, sourceId, s.index, ext)))
            .setId("ext-${s.index}").setMimeType(subtitleMime(url?.substringAfterLast('.')?.substringBefore('?') ?: ext))
            .setLanguage(s.language).setLabel(s.displayTitle).build()
    }

    private fun subtitleMime(ext: String) = when (ext.lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        else -> MimeTypes.TEXT_VTT
    }

    private fun metadata(item: Item) = MediaMetadata.Builder()
        .setTitle(item.name)
        .setDisplayTitle(item.name)
        .setSubtitle(listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" · ").ifEmpty { item.year?.toString() })
        .setArtist(item.seriesName)
        .setArtworkUri(
            app.downloads.file(item.id, "Thumb.webp")?.let(Uri::fromFile) ?: app.downloads.file(item.id, "Primary.webp")?.let(Uri::fromFile)
                ?: item.thumb?.let { Uri.parse(api.imageUrl(it.id, it.type, 640, it.tag)) },
        )
        .setDurationMs(item.runTimeMs.takeIf { it > 0 })
        .setMediaType(if (item.isEpisode) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE)
        .setIsPlayable(true)
        .setIsBrowsable(false)

    // ---- tracks ----

    /**
     * Applies the chosen Jellyfin streams to ExoPlayer's tracks when the file itself is played.
     * Embedded streams map to ExoPlayer's track groups by order within their type, checked against
     * the language in case ExoPlayer skipped a track it can't decode.
     */
    private fun applyTracks() {
        val n = now.value ?: return
        if (n.method != Method.DirectPlay && n.method != Method.Offline) {
            // Transcodes carry one audio track; only side-loaded subtitles may need picking.
            selectSub(n.sub)
            return
        }
        val tracks = exo.currentTracks
        val params = exo.trackSelectionParameters.buildUpon()
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val jfAudio = n.streams.filter { it.isAudio && !it.isExternal }
        val target = matchGroup(audioGroups, jfAudio, n.audio)
        if (target != null) params.setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        exo.trackSelectionParameters = params.build()
        selectSub(n.sub)
    }

    private fun selectSub(sub: Int) {
        val n = now.value ?: return
        val params = exo.trackSelectionParameters.buildUpon()
        val textGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (sub < 0 || n.streams.firstOrNull { it.index == sub }?.let { !it.isText && n.method == Method.Transcode } == true) {
            // Off, or burned into the picture by the server.
            exo.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return
        }
        val external = textGroups.firstOrNull { g -> g.getTrackFormat(0).id?.endsWith("ext-$sub") == true }
        val group = external ?: if (n.method == Method.Transcode) null else
            matchGroup(textGroups.filter { g -> g.getTrackFormat(0).id?.contains("ext-") != true }, n.streams.filter { it.isSub && !it.isExternal }, sub)
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        if (group != null) params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        exo.trackSelectionParameters = params.build()
    }

    private fun matchGroup(groups: List<Tracks.Group>, jf: List<Stream>, index: Int?): Tracks.Group? {
        val pos = jf.indexOfFirst { it.index == index }
        if (pos < 0) return null
        val want = jf[pos]
        val byOrder = groups.getOrNull(pos)
        if (byOrder != null && (byOrder.getTrackFormat(0).language == null || sameLanguage(byOrder.getTrackFormat(0).language, want.language))) return byOrder
        return groups.firstOrNull { sameLanguage(it.getTrackFormat(0).language, want.language) && (want.channels == 0 || it.getTrackFormat(0).channelCount == want.channels) }
            ?: groups.firstOrNull { sameLanguage(it.getTrackFormat(0).language, want.language) } ?: byOrder
    }

    private fun sameLanguage(exoLang: String?, jfLang: String?) = sameLang(exoLang, jfLang)

    /** User picked an audio track: switch and remember it for the show. */
    fun selectAudio(index: Int) {
        val n = now.value ?: return
        prefs.remember(n.item.memoryKey, memoryFor(n.streams, index, n.sub))
        // The TV gets a new stream with the chosen track; locally the file's tracks switch in place.
        if (casting.value) load(n.item, player.currentPosition, index, n.sub, n.quality)
        else if (n.method == Method.DirectPlay || (n.method == Method.Offline && n.item.json.optJSONObject("Download")?.optBoolean("original") == true)) {
            now.value = n.copy(audio = index)
            applyTracks()
        } else if (n.method != Method.Offline) load(n.item, player.currentPosition, index, n.sub, n.quality)
        report("/Progress")
    }

    /** User picked a subtitle (-1 = off): switch and remember it for the show. */
    fun selectSubtitle(index: Int) {
        val n = now.value ?: return
        prefs.remember(n.item.memoryKey, memoryFor(n.streams, n.audio, index))
        val burned = n.method == Method.Transcode && listOf(index, n.sub).any { i -> n.streams.firstOrNull { it.index == i }?.isText == false }
        val sideloaded = exo.currentTracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.endsWith("ext-$index") == true }
        if (casting.value || n.method == Method.Transcode && index >= 0 && !sideloaded || burned) {
            load(n.item, player.currentPosition, n.audio, index, n.quality)
        } else {
            now.value = n.copy(sub = index)
            selectSub(index)
        }
        report("/Progress")
    }

    fun setQuality(q: Quality) {
        val n = now.value ?: return
        if (n.method == Method.Offline) return
        load(n.item, player.currentPosition, n.audio, n.sub, q)
    }

    /** Moving on to the next episode counts the current one as watched, even from the credits. */
    fun playNext() {
        val next = now.value?.next ?: return
        stopped(ended = true)
        play(next, 0)
    }

    fun skip(segment: Segment) {
        skipped += segment
        player.seekTo(segment.endMs)
    }

    // ---- recovery ----

    private fun recover(e: PlaybackException) {
        val n = now.value ?: return
        val pos = player.currentPosition
        when {
            // Some AV1 / Hi10P files are too much for the phone: let the server convert them.
            e.errorCode in DECODER_ERRORS && n.method == Method.DirectPlay && !triedTranscode -> {
                triedTranscode = true
                load(n.item, pos, n.audio, n.sub, n.quality, forceTranscode = true, note = "Converting: this device can't play the original")
            }
            // A dropped connection: find the best address and carry on from the same spot.
            e.errorCode in 2000..2999 && n.method != Method.Offline && networkRetries++ < 4 -> scope.launch {
                delay(1_000L * networkRetries)
                api.pickBest()
                if (n.method == Method.Transcode) load(n.item, pos, n.audio, n.sub, n.quality) else {
                    player.prepare()
                    player.play()
                }
            }
            // A broken download: stream it instead while the server is reachable.
            n.method == Method.Offline && api.online.value && !triedTranscode -> {
                triedTranscode = true
                load(n.item, pos, n.audio, n.sub, null, note = "The download couldn't be read; streaming instead", stream = true)
            }
            else -> error.value = when {
                e.errorCode in 2000..2999 -> "Can't reach the server"
                e.errorCode in DECODER_ERRORS -> "This device can't play this video"
                else -> "Couldn't play this (${e.errorCodeName})"
            }
        }
    }

    /** Two stalls within a minute on Auto: drop a preset at the same position. Never goes back up mid-item. */
    private fun rebuffered() {
        val n = now.value ?: return
        val t = System.currentTimeMillis()
        rebuffers.removeAll { t - it > 60_000 }
        rebuffers += t
        if (rebuffers.size >= 2 && n.quality == Quality.Auto && n.method != Method.Offline && n.effective != Quality.DataSaver) {
            rebuffers.clear()
            val lower = if (n.method == Method.DirectPlay) Quality.High.takeIf { it.total < (n.streams.firstOrNull { it.isVideo }?.bitrate ?: 0) } ?: Quality.Medium else n.effective.lower
            load(n.item, player.currentPosition, n.audio, n.sub, lower, note = "Lowered quality to ${lower.label}")
        }
    }

    // ---- reporting ----

    private fun body(n: NowPlaying, pos: Long) = JSONObject()
        .put("ItemId", n.item.id).put("MediaSourceId", n.sourceId).put("PlaySessionId", n.playSessionId)
        .put("PositionTicks", pos * TICKS_PER_MS).put("IsPaused", !player.isPlaying).put("CanSeek", true)
        .put("PlayMethod", when (n.method) { Method.Transcode -> "Transcode"; Method.Remux -> "DirectStream"; else -> "DirectPlay" })
        .put("AudioStreamIndex", n.audio).put("SubtitleStreamIndex", n.sub)

    private fun report(path: String) {
        val n = now.value ?: return
        val pos = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: n.item.runTimeMs
        if (n.method == Method.Offline) app.downloads.updateUserData(n.item.id, n.item.userData.copy(positionMs = pos, lastPlayed = java.time.Instant.now()))
        val b = body(n, pos)
        app.scope.launch(Dispatchers.IO) {
            try {
                api.reportNow(path, b)
            } catch (_: Exception) {
                app.downloads.queueProgress(n.item.id, pos, duration > 0 && pos > duration * 0.9)
            }
        }
    }

    /** Ends the reporting session for what's loaded, marking it watched past 90 %. */
    private fun stopped(ended: Boolean = false) {
        val n = now.value ?: return
        if (!started && !ended) return
        started = false
        val duration = player.duration.takeIf { it > 0 } ?: n.item.runTimeMs
        val pos = if (ended) duration else player.currentPosition
        val played = ended || (duration > 0 && pos > duration * 0.9)
        val u = n.item.userData.copy(positionMs = if (played) 0 else pos, played = played || n.item.userData.played, lastPlayed = java.time.Instant.now())
        if (app.downloads.offlineItem(n.item.id) != null) app.downloads.updateUserData(n.item.id, u)
        val b = body(n, pos)
        app.scope.launch(Dispatchers.IO) {
            try {
                api.reportNow("/Stopped", b)
                if (played) runCatching { api.setPlayed(n.item.id, true) }
            } catch (_: Exception) {
                app.downloads.queueProgress(n.item.id, if (played) 0 else pos, played)
            }
            if (played) app.downloads.syncPending()
        }
    }

    /** Name of the Chromecast being played to, if any. */
    fun castDeviceName(): String? = runCatching { androidx.media3.cast.Cast.getSingletonInstance(app).currentCastSession?.castDevice?.friendlyName }.getOrNull()

    /** Leaves the TV and carries on playing on this device from the same spot. */
    fun playHere() = runCatching { androidx.media3.cast.Cast.getSingletonInstance(app).endCurrentSession(true) }

    /** Stops and clears the player, reporting the position. */
    fun stop() {
        stopped()
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        now.value = null
        active = false
    }

    // ---- Chromecast ----

    /**
     * The TV fetches the stream itself, so it gets a real address it can reach and an H.264/AAC HLS
     * transcode with the chosen audio and the subtitle burned in, which the default receiver always plays.
     */
    private inner class CastConverter : MediaItemConverter {
        private val default = DefaultMediaItemConverter()

        override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
            val extras = item.mediaMetadata.extras
            val id = extras?.getString("itemId") ?: item.mediaId
            val q = Quality.of(prefs.qualityWifi).takeIf { it.video > 0 } ?: Quality.High
            val params = buildString {
                append("/Videos/$id/master.m3u8?MediaSourceId=$id&DeviceId=${prefs.deviceId}&api_key=${prefs.token}")
                append("&VideoCodec=h264&AudioCodec=aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&BreakOnNonKeyFrames=true")
                append("&VideoBitrate=${q.video}&AudioBitrate=${q.audio}&MaxHeight=${q.height}&MaxWidth=${q.height * 16 / 9}")
                extras?.getInt("audio", -1)?.takeIf { it >= 0 }?.let { append("&AudioStreamIndex=$it") }
                extras?.getInt("sub", -1)?.takeIf { it >= 0 }?.let { append("&SubtitleStreamIndex=$it&SubtitleMethod=Encode") }
                extras?.getString("session")?.let { append("&PlaySessionId=$it") }
            }
            val art = item.mediaMetadata.artworkUri?.toString()?.takeIf { it.startsWith(SERVER) }?.let { Uri.parse(api.absolute(it)) }
            val cast = MediaItem.Builder().setMediaId(id).setUri(api.absolute(params)).setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(art).build()).build()
            return default.toMediaQueueItem(cast)
        }

        override fun toMediaItem(item: MediaQueueItem): MediaItem = default.toMediaItem(item)
    }
}

/** Media session for the notification, lock screen, headset buttons and Bluetooth. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val playback = (application as App).playback
        session = MediaSession.Builder(this, playback.player)
            // Artwork URLs use the app's placeholder host; load them through the same client as everything else.
            .setBitmapLoader(
                androidx.media3.session.CacheBitmapLoader(
                    androidx.media3.datasource.DataSourceBitmapLoader.Builder(this)
                        .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory((application as App).api.http)))
                        .build(),
                ),
            )
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiped away from recents while paused: stop and report. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = (application as App).playback
        if (!p.player.playWhenReady || p.player.mediaItemCount == 0) {
            p.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
