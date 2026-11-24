package xyz.yuuki.novafin

import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.max

// ---- wire types (Jellyfin 10.11: MediaBrowser.Model/SyncPlay) ---------------------------------

enum class GroupState { Idle, Waiting, Paused, Playing }

data class GroupInfo(val id: String, val name: String, val state: GroupState, val participants: List<String>) {
    companion object {
        fun from(o: JSONObject) = GroupInfo(
            o.getString("GroupId"), o.optString("GroupName"), enumOr(o.optString("State"), GroupState.Idle),
            o.optJSONArray("Participants")?.let { a -> List(a.length(), a::getString) }.orEmpty(),
        )
    }
}

data class QueueEntry(val itemId: String, val playlistItemId: String)

data class QueueUpdate(val reason: String, val lastUpdate: Instant, val items: List<QueueEntry>, val index: Int, val startMs: Long, val isPlaying: Boolean) {
    val current get() = items.getOrNull(index)
    val next get() = if (index < 0) null else items.getOrNull(index + 1)
}

enum class CommandType { Unpause, Pause, Stop, Seek }

/** [at] is the server-clock moment to act; [positionMs] is where playback is at that moment. */
data class SyncCommand(val type: CommandType, val playlistItemId: String, val at: Instant, val positionMs: Long?, val emittedAt: Instant)

sealed interface SyncMessage {
    data class GroupJoined(val group: GroupInfo) : SyncMessage
    data class UserJoined(val user: String) : SyncMessage
    data class UserLeft(val user: String) : SyncMessage
    data object GroupLeft : SyncMessage
    data class StateUpdate(val state: GroupState, val reason: String) : SyncMessage
    data class PlayQueue(val queue: QueueUpdate) : SyncMessage
    data object NotInGroup : SyncMessage
    data object GroupDoesNotExist : SyncMessage
    data object LibraryAccessDenied : SyncMessage
    data class Command(val command: SyncCommand) : SyncMessage
    /** The server drops the socket unless a KeepAlive arrives within [timeoutSeconds]. */
    data class ForceKeepAlive(val timeoutSeconds: Int) : SyncMessage
}

private inline fun <reified T : Enum<T>> enumOr(name: String, default: T) = enumValues<T>().firstOrNull { it.name == name } ?: default

/** Jellyfin writes UTC with seven fractional digits; an offset form is accepted too. */
fun parseTime(s: String): Instant = OffsetDateTime.parse(s).toInstant()

/** A message from `/socket`, or null for the ones SyncPlay doesn't care about. */
fun parseSyncMessage(text: String): SyncMessage? {
    val o = JSONObject(text)
    return when (o.optString("MessageType")) {
        "ForceKeepAlive" -> SyncMessage.ForceKeepAlive(o.optInt("Data", 60))
        "SyncPlayCommand" -> {
            val d = o.getJSONObject("Data")
            val type = enumValues<CommandType>().firstOrNull { it.name == d.optString("Command") } ?: return null
            SyncMessage.Command(
                SyncCommand(
                    type, d.optString("PlaylistItemId"), parseTime(d.getString("When")),
                    if (d.isNull("PositionTicks")) null else d.getLong("PositionTicks") / TICKS_PER_MS, parseTime(d.getString("EmittedAt")),
                ),
            )
        }
        "SyncPlayGroupUpdate" -> {
            val u = o.getJSONObject("Data")
            when (u.optString("Type")) {
                "GroupJoined" -> SyncMessage.GroupJoined(GroupInfo.from(u.getJSONObject("Data")))
                "UserJoined" -> SyncMessage.UserJoined(u.optString("Data"))
                "UserLeft" -> SyncMessage.UserLeft(u.optString("Data"))
                "GroupLeft" -> SyncMessage.GroupLeft
                "StateUpdate" -> u.getJSONObject("Data").let { SyncMessage.StateUpdate(enumOr(it.optString("State"), GroupState.Idle), it.optString("Reason")) }
                "PlayQueue" -> {
                    val d = u.getJSONObject("Data")
                    SyncMessage.PlayQueue(
                        QueueUpdate(
                            d.optString("Reason"), parseTime(d.getString("LastUpdate")),
                            d.list("Playlist").map { QueueEntry(it.getString("ItemId"), it.getString("PlaylistItemId")) },
                            d.optInt("PlayingItemIndex", -1), d.optLong("StartPositionTicks") / TICKS_PER_MS, d.optBoolean("IsPlaying"),
                        ),
                    )
                }
                "NotInGroup" -> SyncMessage.NotInGroup
                "GroupDoesNotExist" -> SyncMessage.GroupDoesNotExist
                "LibraryAccessDenied" -> SyncMessage.LibraryAccessDenied
                else -> null
            }
        }
        else -> null
    }
}

// ---- group state machine ----------------------------------------------------------------------

sealed interface SyncState {
    data object NotInGroup : SyncState
    data object Joining : SyncState
    /** [itemId] is what the group has loaded, if anything. */
    data class InGroup(val groupId: String, val name: String, val participants: List<String>, val state: GroupState, val itemId: String? = null) : SyncState
    data object Leaving : SyncState
}

/** The group state after [m]. Messages that make no sense in the current state leave it alone. */
fun SyncState.reduce(m: SyncMessage): SyncState = when (m) {
    is SyncMessage.GroupJoined -> {
        val g = m.group
        when (this) {
            SyncState.Joining -> SyncState.InGroup(g.id, g.name, g.participants, g.state)
            // Rejoining after a reconnect keeps what is loaded.
            is SyncState.InGroup -> SyncState.InGroup(g.id, g.name, g.participants, g.state, itemId.takeIf { g.id == groupId })
            else -> this
        }
    }
    SyncMessage.GroupLeft, SyncMessage.NotInGroup, SyncMessage.GroupDoesNotExist, SyncMessage.LibraryAccessDenied -> SyncState.NotInGroup
    else -> if (this !is SyncState.InGroup) this else when (m) {
        is SyncMessage.UserJoined -> copy(participants = (participants + m.user).distinct())
        is SyncMessage.UserLeft -> copy(participants = participants - m.user)
        is SyncMessage.StateUpdate -> copy(state = m.state)
        is SyncMessage.PlayQueue -> copy(itemId = m.queue.current?.itemId)
        else -> this
    }
}

// ---- clock ------------------------------------------------------------------------------------

/** One `/GetUtcTime` round trip: add [offsetMs] to the local clock to get the server's. */
data class TimeSample(val offsetMs: Long, val delayMs: Long)

/** NTP: [sent]/[received] on the local clock, [serverReceived]/[serverSent] on the server's, all in ms. */
fun timeSample(sent: Long, received: Long, serverReceived: Long, serverSent: Long) =
    TimeSample(((serverReceived - sent) + (serverSent - received)) / 2, (received - sent) - (serverSent - serverReceived))

/** The sample with the shortest round trip has the least room for asymmetry. */
fun bestSample(samples: List<TimeSample>) = samples.minByOrNull { it.delayMs }

/** Ms from [localNowMs] until the server-clock moment [serverAtMs]; negative when it has already passed. */
fun commandDelay(serverAtMs: Long, offsetMs: Long, localNowMs: Long) = serverAtMs - offsetMs - localNowMs

/** Where the group is by now when an Unpause meant for [positionMs] is applied [delayMs] from its moment (negative = late). */
fun unpauseTarget(positionMs: Long, delayMs: Long) = positionMs + max(0, -delayMs)

fun reconnectDelay(attempt: Int) = 1_000L shl attempt.coerceIn(0, 5)

const val SYNCPLAY_NO_ACCESS = "Your account can't use SyncPlay. An admin can allow it in Dashboard → Users → <you> → SyncPlay."

/**
 * Message for a failed SyncPlay request. Every endpoint answers 403 without the permission; the
 * playback ones also do when the user is in no group, which is what it means with [inGroup].
 */
fun syncPlayError(e: Exception, inGroup: Boolean = false) = when {
    e is ApiException && e.code == 403 -> if (inGroup) "You're no longer in the group." else SYNCPLAY_NO_ACCESS
    e is IOException -> "Can't reach the server"
    else -> e.message ?: "SyncPlay failed"
}

// ---- client -----------------------------------------------------------------------------------

/**
 * Watch together over Jellyfin SyncPlay. The server keeps the group's state and tells every
 * client what to do and when (on its own clock); each client loads the media, reports when it is
 * ready or stalls, and acts on the commands at the right local moment.
 *
 * The group is only followed while the player is open: with it closed the server is told not to
 * wait for this device, so the others aren't held up by someone browsing.
 */
class SyncPlay(private val app: App, private val playback: Playback) {
    private val scope = MainScope()
    private val api get() = app.api
    private val raw get() = playback.raw

    val state = MutableStateFlow<SyncState>(SyncState.NotInGroup)
    val error = MutableStateFlow<String?>(null)
    val inGroup get() = state.value is SyncState.InGroup

    /** Playing along with the group; player controls become requests to it. */
    var following = false
        private set

    private var queue: QueueUpdate? = null
    /** Playlist entry handed to the player. */
    private var loaded: String? = null
    private var offsetMs = 0L
    private var pingMs = 0L
    private var needReady = false
    private var lastCommand: SyncCommand? = null
    /** Arrived while its media was still loading. */
    private var pending: SyncCommand? = null

    private var socket: WebSocket? = null
    private var generation = 0
    private var attempts = 0
    private var opened = CompletableDeferred<Unit>()
    private var keepAlive: Job? = null
    private var clock: Job? = null
    private var scheduled: Job? = null
    private var stall: Job? = null

    /** The player holds the group's current entry (not the previous title while the new one loads). */
    private val cued get() = queue?.current?.let { it.playlistItemId == loaded && playback.now.value?.item?.id == it.itemId } == true

    val nextItemId get() = queue?.next?.itemId

    init {
        raw.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (!following || !cued) return
                stall?.cancel()
                when (s) {
                    // Seeks and track switches rebuffer for a moment; only a real stall holds the group up.
                    Player.STATE_BUFFERING -> stall = scope.launch {
                        delay(1_000)
                        needReady = true
                        report("Buffering")
                    }
                    Player.STATE_READY -> if (needReady) {
                        needReady = false
                        report("Ready")
                    }
                }
            }
        })
        scope.launch { playback.now.collect { if (cued) pending?.let(::onCommand) } }
    }

    // ---- joining and leaving ----

    suspend fun groups(): List<GroupInfo> = try {
        JSONArray(api.getJson("/SyncPlay/List")).objects().map(GroupInfo::from)
    } catch (e: ApiException) {
        throw if (e.code == 403) ApiException(403, SYNCPLAY_NO_ACCESS) else e
    }

    /** Starts a group and, with [itemId], plays it for everyone. */
    fun create(name: String, itemId: String? = null) = enter {
        following = false
        val g = GroupInfo.from(JSONObject(post("New", JSONObject().put("GroupName", name))))
        state.value = state.value.reduce(SyncMessage.GroupJoined(g))
        if (itemId != null) setQueue(listOf(itemId), 0, 0) else ignoreWait(true)
    }

    fun join(groupId: String) = enter {
        // The group's queue arrives right behind the join and the others wait for this device to load it.
        following = true
        post("Join", JSONObject().put("GroupId", groupId))
        // Join answers 204 either way; the outcome comes over the socket.
        val s = withTimeout(8_000) { state.first { it != SyncState.Joining } }
        if (s is SyncState.InGroup && s.state == GroupState.Idle) halt()
    }

    private fun enter(block: suspend () -> Unit) {
        if (state.value != SyncState.NotInGroup) return
        error.value = null
        state.value = SyncState.Joining
        scope.launch {
            try {
                syncClock()
                connect()
                withTimeout(8_000) { opened.await() }
                block()
                if (inGroup) sendPing()
            } catch (e: Exception) {
                if (state.value == SyncState.Joining) left()
                error.value = if (e is CancellationException) "The server didn't answer" else syncPlayError(e)
            }
        }
    }

    fun leave() {
        if (!inGroup) return
        state.value = SyncState.Leaving
        scope.launch {
            runCatching { post("Leave") }
            left()
        }
    }

    private fun left() {
        state.value = SyncState.NotInGroup
        following = false
        queue = null
        loaded = null
        pending = null
        lastCommand = null
        generation++
        socket?.close(1000, null)
        socket = null
        listOf(keepAlive, clock, scheduled, stall).forEach { it?.cancel() }
    }

    /** The player was closed: stay in the group without holding it up. */
    fun halt() {
        if (!following) return
        following = false
        loaded = null
        pending = null
        scheduled?.cancel()
        stall?.cancel()
        if (inGroup) ignoreWait(true)
    }

    /** The player is open again: catch up with whatever the group has on. */
    fun follow() {
        if (following || !inGroup) return
        following = true
        ignoreWait(false)
        cue()
    }

    // ---- requests the player makes instead of acting on its own ----

    fun setQueue(ids: List<String>, index: Int, startMs: Long) {
        following = true
        scope.launch {
            try {
                post("SetIgnoreWait", JSONObject().put("IgnoreWait", false))
                post("SetNewQueue", JSONObject().put("PlayingQueue", JSONArray(ids)).put("PlayingItemPosition", index).put("StartPositionTicks", startMs * TICKS_PER_MS))
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    fun unpause() = request("Unpause")

    fun pause() = request("Pause")

    fun seek(positionMs: Long) = request("Seek", JSONObject().put("PositionTicks", positionMs.coerceAtLeast(0) * TICKS_PER_MS))

    /** Names the entry being left, so several clients reaching the end together advance once. */
    fun nextItem() {
        request("NextItem", JSONObject().put("PlaylistItemId", queue?.current?.playlistItemId ?: return))
    }

    private fun ignoreWait(on: Boolean) = request("SetIgnoreWait", JSONObject().put("IgnoreWait", on))

    private fun report(kind: String) {
        val entry = queue?.current ?: return
        request(
            kind,
            JSONObject().put("When", Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs).toString())
                .put("PositionTicks", raw.currentPosition * TICKS_PER_MS).put("IsPlaying", raw.playWhenReady).put("PlaylistItemId", entry.playlistItemId),
        )
    }

    private suspend fun post(path: String, body: JSONObject? = null) = api.postJson("/SyncPlay/$path", body)

    private fun request(path: String, body: JSONObject? = null) = scope.launch {
        try {
            post(path, body)
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun failed(e: Exception) {
        if (e is CancellationException) throw e
        val gone = e is ApiException && e.code == 403 && inGroup
        error.value = syncPlayError(e, inGroup)
        // The player is what's on screen while following.
        if (following) playback.error.value = error.value
        if (gone) left()
    }

    // ---- socket ----

    private fun connect() {
        val gen = ++generation
        opened = CompletableDeferred()
        fun onMain(block: () -> Unit) = scope.launch { if (gen == generation) block() }
        fun lost() = onMain {
            keepAlive?.cancel()
            scope.launch {
                delay(reconnectDelay(attempts++))
                if (gen == generation && state.value != SyncState.NotInGroup) connect()
            }
        }
        socket?.cancel()
        // Api.http swaps the placeholder host for the active address and adds the Authorization header, which identifies the session.
        socket = api.http.newWebSocket(Request.Builder().url("$SERVER/socket").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onMain {
                    attempts = 0
                    opened.complete(Unit)
                    // A reconnect: joining the same group again restores the session and resends its state.
                    (state.value as? SyncState.InGroup)?.let { request("Join", JSONObject().put("GroupId", it.groupId)) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMain { runCatching { parseSyncMessage(text) }.getOrNull()?.let(::handle) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost()
            }
        })
        clock?.cancel()
        clock = scope.launch {
            while (isActive) {
                delay(if (inGroup) 60_000 else 3_000)
                syncClock()
            }
        }
    }

    private fun handle(m: SyncMessage) {
        when (m) {
            is SyncMessage.ForceKeepAlive -> {
                keepAlive?.cancel()
                keepAlive = scope.launch {
                    while (isActive) {
                        socket?.send("""{"MessageType":"KeepAlive"}""")
                        delay(m.timeoutSeconds * 500L)
                    }
                }
            }
            is SyncMessage.Command -> onCommand(m.command)
            else -> {
                val before = state.value
                val after = before.reduce(m)
                state.value = after
                when (m) {
                    is SyncMessage.PlayQueue -> if (after is SyncState.InGroup) onQueue(m.queue)
                    // Someone joining or leaving changes the group's state without a StateUpdate, and a name stays while that user has another device in.
                    is SyncMessage.UserJoined, is SyncMessage.UserLeft -> if (after is SyncState.InGroup) scope.launch {
                        runCatching { GroupInfo.from(JSONObject(api.getJson("/SyncPlay/${after.groupId}"))) }.onSuccess { state.value = state.value.reduce(SyncMessage.GroupJoined(it)) }
                    }
                    SyncMessage.GroupDoesNotExist -> error.value = "That group no longer exists."
                    SyncMessage.LibraryAccessDenied -> error.value = "You don't have access to what that group is watching."
                    else -> Unit
                }
                if (after == SyncState.NotInGroup && before != after) left()
            }
        }
    }

    private suspend fun syncClock() {
        val samples = ArrayList<TimeSample>()
        for (i in 1..4) samples += runCatching {
            val sent = System.currentTimeMillis()
            val o = JSONObject(api.getJson("/GetUtcTime"))
            timeSample(sent, System.currentTimeMillis(), parseTime(o.getString("RequestReceptionTime")).toEpochMilli(), parseTime(o.getString("ResponseTransmissionTime")).toEpochMilli())
        }.getOrNull() ?: break
        val best = bestSample(samples) ?: return
        offsetMs = best.offsetMs
        pingMs = best.delayMs / 2
        if (inGroup) sendPing()
    }

    /** The server starts playback twice the slowest ping ahead, so every client gets the command in time. */
    private suspend fun sendPing() = runCatching { post("Ping", JSONObject().put("Ping", pingMs)) }

    // ---- playing along ----

    private fun onQueue(q: QueueUpdate) {
        if (queue?.let { q.lastUpdate < it.lastUpdate } == true) return
        queue = q
        if (following) cue()
    }

    /** Loads the group's current entry paused, or confirms it is already here. The group starts once everyone is ready. */
    private fun cue() {
        val q = queue ?: return
        val entry = q.current ?: return
        if (entry.playlistItemId != loaded) {
            loaded = entry.playlistItemId
            scheduled?.cancel()
            lastCommand = null
            pending = null
            needReady = true
            raw.pause()
            val elapsed = if (q.isPlaying) max(0, System.currentTimeMillis() + offsetMs - q.lastUpdate.toEpochMilli()) else 0
            playback.start(Item(JSONObject().put("Id", entry.itemId)), q.startMs + elapsed)
        } else if (cued && raw.playbackState == Player.STATE_READY) report("Ready") else needReady = true
    }

    private fun onCommand(c: SyncCommand) {
        if (!following || c.playlistItemId != queue?.current?.playlistItemId) return
        if (!cued) {
            pending = c
            return
        }
        pending = null
        if (c == lastCommand && scheduled?.isActive == true) return
        lastCommand = c
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(commandDelay(c.at.toEpochMilli(), offsetMs, System.currentTimeMillis()).coerceAtLeast(0))
            apply(c)
        }
    }

    // ponytail: synced at each command only (skip-to-sync); jellyfin-web also nudges speed to correct drift between commands. Add if long sessions drift.
    private fun apply(c: SyncCommand) {
        val pos = c.positionMs
        when (c.type) {
            CommandType.Unpause -> {
                if (pos != null) {
                    val target = unpauseTarget(pos, commandDelay(c.at.toEpochMilli(), offsetMs, System.currentTimeMillis()))
                    if (abs(raw.currentPosition - target) > 500) raw.seekTo(target)
                }
                raw.play()
            }
            CommandType.Pause -> {
                raw.pause()
                if (pos != null && abs(raw.currentPosition - pos) > 200) raw.seekTo(pos)
            }
            // Everyone seeks paused and reports Ready; the server then resumes the group together.
            CommandType.Seek -> {
                raw.pause()
                needReady = true
                raw.seekTo(pos ?: 0)
            }
            CommandType.Stop -> raw.pause()
        }
    }
}
