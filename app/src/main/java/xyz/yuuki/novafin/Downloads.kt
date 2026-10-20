package xyz.yuuki.novafin

import android.app.Notification
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

data class DownloadInfo(val state: Int, val percent: Float, val bytes: Long) {
    val done get() = state == Download.STATE_COMPLETED
}

/**
 * Downloads for offline viewing, the offline copy of their metadata, and the queue of watch
 * progress that couldn't be sent and goes up on reconnect.
 *
 * Originals are plain progressive downloads; smaller copies are the server's HLS transcode at the
 * download preset. Both go through Media3's DownloadManager into one cache, keyed by the
 * address-independent URL, so they resume after a pause or an address switch and play from disk.
 */
class Downloads(private val app: App) {
    private val db = StandaloneDatabaseProvider(app)
    private val root: File = (if (app.prefs.storageSd) app.getExternalFilesDirs(null).getOrNull(1) else null) ?: app.getExternalFilesDir(null) ?: app.filesDir
    val cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), db)
    /** Metadata, images and subtitles per item id, also show and season pages for offline browsing. */
    private val meta = File(app.filesDir, "offline").apply { mkdirs() }
    private val pendingFile = File(app.filesDir, "pending.json")

    private val upstream = OkHttpDataSource.Factory(app.api.http)
    /** Plays downloads from disk and streams the rest. */
    val dataSource: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheWriteDataSinkFactory(null)

    val manager = DownloadManager(
        app, androidx.media3.exoplayer.offline.DefaultDownloadIndex(db),
        // One segment at a time: the server transcodes in order and restarts on out-of-order requests.
        DefaultDownloaderFactory(CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream), Executors.newSingleThreadExecutor()),
    ).apply {
        maxParallelDownloads = 1
        requirements = requirementsFor(app.prefs.wifiOnlyDownloads)
    }
    val notifications by lazy { DownloadNotificationHelper(app, CHANNEL) }

    val states = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    /** Offline copies of downloaded items, by id. */
    val items = MutableStateFlow<Map<String, Item>>(emptyMap())

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(m: DownloadManager) = refresh()
            override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) {
                refresh()
                if (d.state == Download.STATE_COMPLETED) syncPending()
            }
            override fun onDownloadRemoved(m: DownloadManager, d: Download) = refresh()
        })
        app.scope.launch(Dispatchers.IO) {
            items.value = meta.listFiles().orEmpty().mapNotNull { dir ->
                File(dir, "item.json").takeIf { it.exists() }?.let { f -> runCatching { Item(JSONObject(f.readText())) }.getOrNull() }
            }.filter { it.playable }.associateBy { it.id }
        }
        // DownloadManager doesn't report progress; poll while something downloads.
        app.scope.launch {
            while (true) {
                delay(1_000)
                if (manager.currentDownloads.isNotEmpty()) withContext(Dispatchers.Main) { refresh() }
            }
        }
    }

    private fun refresh() {
        val map = HashMap<String, DownloadInfo>()
        manager.downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) c.download.let { map[it.request.id] = DownloadInfo(it.state, it.percentDownloaded, it.bytesDownloaded) }
        }
        for (d in manager.currentDownloads) map[d.request.id] = DownloadInfo(d.state, d.percentDownloaded, d.bytesDownloaded)
        states.value = map
    }

    fun isDownloaded(id: String) = states.value[id]?.done == true

    fun setWifiOnly(on: Boolean) {
        app.prefs.wifiOnlyDownloads = on
        manager.requirements = requirementsFor(on)
    }

    private fun requirementsFor(wifiOnly: Boolean) = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun bytesUsed() = cache.cacheSpace + meta.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes() = StatFs(root.path).availableBytes

    // ---- offline metadata ----

    fun dir(id: String) = File(meta, id)
    fun file(id: String, name: String) = File(dir(id), name).takeIf { it.exists() }

    fun offlineItem(id: String): Item? = items.value[id] ?: file(id, "item.json")?.let { Item(JSONObject(it.readText())) }

    fun offlineSeasons(seriesId: String) = items.value.values.filter { it.seriesId == seriesId }.mapNotNull { it.seasonId }.distinct()
        .mapNotNull { offlineItem(it) }.sortedBy { it.index ?: 0 }

    fun offlineEpisodes(seasonId: String) = items.value.values.filter { it.seasonId == seasonId }.sortedBy { it.index ?: 0 }

    private fun saveItem(item: Item) {
        dir(item.id).mkdirs()
        File(dir(item.id), "item.json").writeText(item.json.toString())
        if (item.playable) items.update { it + (item.id to item) }
    }

    /** Local watch state for a downloaded item; also keeps the offline copy in step with the server. */
    fun updateUserData(id: String, u: UserData) {
        val item = offlineItem(id) ?: return
        val updated = item.withUserData(u)
        if (u.played && item.json.optLong("WatchedAt") == 0L) updated.json.put("WatchedAt", System.currentTimeMillis())
        app.scope.launch(Dispatchers.IO) { saveItem(updated) }
    }

    // ---- downloading ----

    data class Plan(val original: Boolean, val bytes: Long, val error: String?)

    /** What downloading [items] at [quality] would take, and whether it fits. */
    fun estimate(items: List<Item>, quality: Quality): Plan {
        var original = true
        var bytes = 0L
        for (item in items) {
            val src = item.sources.firstOrNull()
            val orig = src?.size?.takeIf { it > 0 } ?: (src?.bitrate ?: 0).toLong() * item.runTimeMs / 8000
            val small = quality.bytesFor(item.runTimeMs)
            val useOriginal = small == null || (app.prefs.smartOriginal && orig <= small) || app.prefs.keepBothAudio
            if (!useOriginal) original = false
            bytes += if (useOriginal) orig else small!!
        }
        val limit = app.prefs.storageLimitGb * (1L shl 30)
        val error = when {
            bytes > freeBytes() -> "Not enough free space"
            limit > 0 && bytesUsed() + bytes > limit -> "Over your ${app.prefs.storageLimitGb} GB download limit"
            else -> null
        }
        return Plan(original, bytes, error)
    }

    private val queueLock = Mutex()

    /** Queues [items] (movies or episodes) at [quality]. */
    fun download(items: List<Item>, quality: Quality = Quality.of(app.prefs.downloadQuality, Quality.Medium)) = app.scope.launch {
        queueLock.withLock { for (item in items) runCatching { enqueue(item, quality) } }
    }

    private suspend fun enqueue(listed: Item, quality: Quality) = withContext(Dispatchers.IO) {
        if (states.value[listed.id]?.let { it.state != Download.STATE_FAILED } == true) return@withContext
        val api = app.api
        val item = api.item(listed.id)
        val segments = runCatching { api.segments(item.id) }.getOrDefault(emptyList())
        item.json.put("Segments", org.json.JSONArray(segments.map { it.toJson() }))
        // Show and season pages, so they can be browsed offline.
        item.seriesId?.let { sid -> if (file(sid, "item.json") == null) runCatching { saveItem(api.item(sid)); fetchImages(api.item(sid)) } }
        item.seasonId?.let { sid -> if (file(sid, "item.json") == null) runCatching { saveItem(api.item(sid)); fetchImages(api.item(sid)) } }

        val streams = item.streams
        val mem = app.prefs.memory(item.memoryKey)
        val defaults = Playback.defaults(app.prefs)
        val audio = pickAudio(streams, mem, defaults)
        val sub = pickSubtitle(streams, mem, defaults, streams.firstOrNull { it.index == audio }?.language)

        val plan = estimate(listOf(item), quality)
        val profile = buildProfile(DeviceCaps.caps, if (plan.original) Quality.ORIGINAL_BITRATE else quality.total, downmix = true, skipAv1 = app.prefs.preferTranscodeAv1)
        val (_, source) = api.playbackInfo(item.id, profile, if (plan.original) Quality.ORIGINAL_BITRATE else quality.total, 0, audio, sub.takeIf { it >= 0 }, directStream = false)
        val original = source.directPlay && plan.original || quality == Quality.Original
        val request = if (original) {
            DownloadRequest.Builder(item.id, Uri.parse(api.downloadUrl(item.id))).setCustomCacheKey("orig-${item.id}")
        } else {
            val url = source.transcodingUrl ?: throw ApiException(0, "Server can't convert this item")
            DownloadRequest.Builder(item.id, Uri.parse(tuneTranscode(SERVER + url, quality))).setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        item.json.put("Download", JSONObject().put("original", original).put("quality", quality.name).put("audio", audio).put("sub", sub))

        // Text subtitles as files: external ones for originals (embedded ones are in the file), the chosen language for transcodes.
        val subLang = streams.firstOrNull { it.index == sub }?.language
        val wanted = streams.filter { it.isSub && it.isText && (if (original) it.isExternal else sameLang(it.language, subLang)) }
        for (s in wanted) runCatching {
            val ext = when (s.codec) { "ass", "ssa" -> "ass"; "srt", "subrip" -> "srt"; else -> "vtt" }
            fetch(api.subtitleUrl(item.id, source.id, s.index, ext), File(dir(item.id), "sub-${s.index}.$ext"))
        }
        saveItem(item)
        fetchImages(item)
        withContext(Dispatchers.Main) { DownloadService.sendAddDownload(app, DownloadSvc::class.java, request.build(), false) }
    }

    private fun fetchImages(item: Item) {
        dir(item.id).mkdirs()
        listOf("Primary" to 500, "Backdrop" to 1280, "Thumb" to 640, "Logo" to 500).forEach { (type, w) ->
            val tag = if (type == "Backdrop") item.backdropTag else item.imageTag(type)
            if (tag != null) runCatching { fetch(app.api.imageUrl(item.id, type, w, tag), File(dir(item.id), "$type.webp")) }
        }
    }

    private fun fetch(url: String, to: File) {
        app.api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return
            val tmp = File(to.path + ".part")
            tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
            tmp.renameTo(to)
        }
    }

    /** Media item that plays a download from disk, or null if [id] isn't fully downloaded. */
    fun mediaItem(id: String): MediaItem.Builder? {
        val d = manager.downloadIndex.getDownload(id)?.takeIf { it.state == Download.STATE_COMPLETED } ?: return null
        return d.request.toMediaItem().buildUpon()
    }

    fun remove(id: String) {
        DownloadService.sendRemoveDownload(app, DownloadSvc::class.java, id, false)
        items.update { it - id }
        app.scope.launch(Dispatchers.IO) {
            dir(id).deleteRecursively()
            // Drop show and season pages nothing points at any more.
            val keep = items.value.values.flatMap { listOfNotNull(it.seriesId, it.seasonId) }.toSet()
            meta.listFiles().orEmpty().filter { d -> d.name !in items.value && d.name !in keep }.forEach { it.deleteRecursively() }
        }
    }

    fun removeAll() {
        DownloadService.sendRemoveAllDownloads(app, DownloadSvc::class.java, false)
        items.value = emptyMap()
        app.scope.launch(Dispatchers.IO) { meta.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun pause() = DownloadService.sendPauseDownloads(app, DownloadSvc::class.java, false)
    fun resume() = DownloadService.sendResumeDownloads(app, DownloadSvc::class.java, false)

    // ---- offline progress ----

    private val pendingLock = Any()

    /** Remembers progress that couldn't be reported; the newest entry per item wins. */
    fun queueProgress(id: String, positionMs: Long, played: Boolean) = synchronized(pendingLock) {
        val o = runCatching { JSONObject(pendingFile.readText()) }.getOrDefault(JSONObject())
        o.put(id, JSONObject().put("positionMs", positionMs).put("played", played).put("at", Instant.now().toString()))
        pendingFile.writeText(o.toString())
    }

    private val syncing = Mutex()

    /** Sends queued progress, then tidies watched downloads and tops up "keep next episodes" shows. */
    fun syncPending() = app.scope.launch {
        if (!syncing.tryLock()) return@launch
        try {
            val pending = synchronized(pendingLock) { runCatching { JSONObject(pendingFile.readText()) }.getOrNull() }
            if (pending != null) for (id in pending.keys().asSequence().toList()) {
                val e = pending.getJSONObject(id)
                val at = Instant.parse(e.getString("at"))
                try {
                    val server = app.api.userData(id)
                    if (shouldUpload(server.lastPlayed, at)) app.api.setUserData(id, e.getLong("positionMs"), e.getBoolean("played"), at)
                } catch (_: ApiException) {
                    // Item gone from the server: nothing to sync.
                } catch (_: Exception) {
                    return@launch
                }
                synchronized(pendingLock) {
                    val now = runCatching { JSONObject(pendingFile.readText()) }.getOrDefault(JSONObject())
                    // Only drop it if it wasn't replaced while we were sending.
                    if (now.optJSONObject(id)?.optString("at") == e.getString("at")) now.remove(id)
                    pendingFile.writeText(now.toString())
                }
            }
            autoDelete()
            topUpShows()
        } finally {
            syncing.unlock()
        }
    }

    private suspend fun autoDelete() {
        val days = app.prefs.autoDeleteDays
        if (days < 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val old = items.value.values.filter { it.json.optLong("WatchedAt").let { w -> w in 1..cutoff } }
        withContext(Dispatchers.Main) { old.forEach { remove(it.id) } }
    }

    private suspend fun topUpShows() {
        if (app.metered) return
        for (show in app.prefs.smartShows) runCatching {
            val next = app.api.items(parentId = show, types = "Episode", sort = "ParentIndexNumber,Ascending", filters = "IsUnplayed", limit = app.prefs.smartEpisodes).items
            download(next).join()
        }
    }

    companion object {
        const val CHANNEL = "downloads"
    }
}

/**
 * Applies a preset to a server transcode URL: resolution, video and audio bitrate, stereo. Jellyfin
 * takes these from the URL, and the profile alone can't express the resolution cap.
 */
fun tuneTranscode(url: String, q: Quality): String {
    if (q.video == 0) return url
    val u = url.toHttpUrl()
    val hevc = u.queryParameter("VideoCodec")?.startsWith("hevc") == true
    return u.newBuilder()
        .setQueryParameter("MaxHeight", q.height.toString())
        .setQueryParameter("MaxWidth", (q.height * 16 / 9).toString())
        .setQueryParameter("VideoBitrate", (if (hevc) q.hevc else q.video).toString())
        .setQueryParameter("AudioBitrate", q.audio.toString())
        .setQueryParameter("TranscodingMaxAudioChannels", "2")
        .build().toString()
}

class DownloadSvc : DownloadService(1, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, Downloads.CHANNEL, R.string.downloads, 0) {
    private val downloads get() = (application as App).downloads

    override fun getDownloadManager() = downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        this.downloads.notifications.buildProgressNotification(this, R.drawable.ic_download, null, null, downloads, notMetRequirements)
}
