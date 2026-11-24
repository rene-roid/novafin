package xyz.yuuki.novafin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SyncPlayTest {
    private val group = """{"GroupId":"7b2f0c1e9a4d4f6e8c3b5a1d2e3f4a5b","GroupName":"ana's group","State":"Paused","Participants":["ana","luis"],"LastUpdatedAt":"2026-11-24T18:00:00.1234567Z"}"""
    private fun update(type: String, data: String) =
        """{"MessageType":"SyncPlayGroupUpdate","MessageId":"0e1d","Data":{"GroupId":"7b2f0c1e9a4d4f6e8c3b5a1d2e3f4a5b","Type":"$type","Data":$data}}"""
    private val queue = update(
        "PlayQueue",
        """{"Reason":"NewPlaylist","LastUpdate":"2026-11-24T18:00:05.5000000Z","Playlist":[{"ItemId":"aaa1","PlaylistItemId":"p1"},{"ItemId":"bbb2","PlaylistItemId":"p2"}],"PlayingItemIndex":0,"StartPositionTicks":900000000,"IsPlaying":false,"ShuffleMode":"Sorted","RepeatMode":"RepeatNone"}""",
    )

    @Test fun groupInfo() {
        val g = (parseSyncMessage(update("GroupJoined", group)) as SyncMessage.GroupJoined).group
        assertEquals(GroupInfo("7b2f0c1e9a4d4f6e8c3b5a1d2e3f4a5b", "ana's group", GroupState.Paused, listOf("ana", "luis")), g)
    }

    @Test fun groupUpdates() {
        assertEquals(SyncMessage.UserJoined("luis"), parseSyncMessage(update("UserJoined", "\"luis\"")))
        assertEquals(SyncMessage.UserLeft("luis"), parseSyncMessage(update("UserLeft", "\"luis\"")))
        assertEquals(SyncMessage.GroupLeft, parseSyncMessage(update("GroupLeft", "\"7b2f0c1e-9a4d-4f6e-8c3b-5a1d2e3f4a5b\"")))
        assertEquals(SyncMessage.StateUpdate(GroupState.Waiting, "Buffer"), parseSyncMessage(update("StateUpdate", """{"State":"Waiting","Reason":"Buffer"}""")))
        assertEquals(SyncMessage.NotInGroup, parseSyncMessage(update("NotInGroup", "\"\"")))
        assertEquals(SyncMessage.GroupDoesNotExist, parseSyncMessage(update("GroupDoesNotExist", "\"\"")))
        assertEquals(SyncMessage.LibraryAccessDenied, parseSyncMessage(update("LibraryAccessDenied", "\"\"")))
    }

    @Test fun playQueue() {
        val q = (parseSyncMessage(queue) as SyncMessage.PlayQueue).queue
        assertEquals("NewPlaylist", q.reason)
        assertEquals(Instant.parse("2026-11-24T18:00:05.500Z"), q.lastUpdate)
        assertEquals(QueueEntry("aaa1", "p1"), q.current)
        assertEquals(QueueEntry("bbb2", "p2"), q.next)
        assertEquals(90_000L, q.startMs)
        assertEquals(false, q.isPlaying)
        assertNull(q.copy(index = 1).next)
        assertNull(q.copy(index = -1, items = emptyList()).current)
    }

    @Test fun command() {
        val c = parseSyncMessage(
            """{"MessageType":"SyncPlayCommand","MessageId":"ab","Data":{"GroupId":"7b2f","PlaylistItemId":"p1","When":"2026-11-24T18:00:10.2500000Z","PositionTicks":1234560000,"Command":"Unpause","EmittedAt":"2026-11-24T18:00:09.2500000Z"}}""",
        )
        assertEquals(
            SyncMessage.Command(SyncCommand(CommandType.Unpause, "p1", Instant.parse("2026-11-24T18:00:10.250Z"), 123_456L, Instant.parse("2026-11-24T18:00:09.250Z"))),
            c,
        )
        val stop = parseSyncMessage(
            """{"MessageType":"SyncPlayCommand","Data":{"GroupId":"7b2f","PlaylistItemId":"00000000000000000000000000000000","When":"2026-11-24T18:00:10Z","PositionTicks":null,"Command":"Stop","EmittedAt":"2026-11-24T18:00:10Z"}}""",
        ) as SyncMessage.Command
        assertEquals(CommandType.Stop, stop.command.type)
        assertNull(stop.command.positionMs)
    }

    @Test fun otherMessages() {
        assertEquals(SyncMessage.ForceKeepAlive(60), parseSyncMessage("""{"MessageType":"ForceKeepAlive","MessageId":"x","Data":60}"""))
        assertNull(parseSyncMessage("""{"MessageType":"KeepAlive","MessageId":"x"}"""))
        assertNull(parseSyncMessage("""{"MessageType":"UserDataChanged","Data":{"UserId":"u"}}"""))
        assertNull(parseSyncMessage(update("SomethingNew", "\"\"")))
    }

    @Test fun times() {
        assertEquals(1_795_543_200_123L, parseTime("2026-11-24T18:00:00.1234567Z").toEpochMilli())
        assertEquals(1_795_543_200_000L, parseTime("2026-11-24T19:00:00+01:00").toEpochMilli())
    }

    @Test fun stateMachine() {
        val joined = parseSyncMessage(update("GroupJoined", group))!!
        // Nothing was asked for: a stray update doesn't put us in a group.
        assertEquals(SyncState.NotInGroup, SyncState.NotInGroup.reduce(joined))
        var s = SyncState.Joining.reduce(joined)
        assertEquals(SyncState.InGroup("7b2f0c1e9a4d4f6e8c3b5a1d2e3f4a5b", "ana's group", listOf("ana", "luis"), GroupState.Paused), s)
        s = s.reduce(SyncMessage.UserJoined("eva")).reduce(SyncMessage.UserJoined("eva")).reduce(SyncMessage.UserLeft("luis"))
        assertEquals(listOf("ana", "eva"), (s as SyncState.InGroup).participants)
        s = s.reduce(parseSyncMessage(queue)!!).reduce(SyncMessage.StateUpdate(GroupState.Waiting, "Play"))
        assertEquals("aaa1", (s as SyncState.InGroup).itemId)
        assertEquals(GroupState.Waiting, s.state)
        // A rejoin after a reconnect keeps what is loaded.
        assertEquals("aaa1", (s.reduce(joined) as SyncState.InGroup).itemId)
        assertEquals(SyncState.NotInGroup, s.reduce(SyncMessage.GroupLeft))
        assertEquals(SyncState.NotInGroup, SyncState.Leaving.reduce(SyncMessage.GroupLeft))
        assertEquals(SyncState.NotInGroup, SyncState.Joining.reduce(SyncMessage.GroupDoesNotExist))
        assertEquals(SyncState.NotInGroup, SyncState.Joining.reduce(SyncMessage.LibraryAccessDenied))
        assertEquals(SyncState.Joining, SyncState.Joining.reduce(SyncMessage.UserJoined("eva")))
    }

    @Test fun clock() {
        // Server clock 5 s ahead, 40 ms each way, 10 ms spent on the server.
        val s = timeSample(sent = 1_000, received = 1_090, serverReceived = 6_040, serverSent = 6_050)
        assertEquals(TimeSample(offsetMs = 5_000, delayMs = 80), s)
        // The slow sample is skewed by its asymmetric route; the quick one wins.
        val slow = timeSample(sent = 2_000, received = 2_500, serverReceived = 7_400, serverSent = 7_410)
        assertEquals(TimeSample(5_155, 490), slow)
        assertEquals(s, bestSample(listOf(slow, s)))
        assertNull(bestSample(emptyList()))
    }

    @Test fun scheduling() {
        // The command is for server time 106 000 = local 101 000; it is now 100 400 locally.
        assertEquals(600L, commandDelay(serverAtMs = 106_000, offsetMs = 5_000, localNowMs = 100_400))
        assertEquals(-250L, commandDelay(106_000, 5_000, 101_250))
        assertEquals(-900L, commandDelay(serverAtMs = 100_000, offsetMs = -1_000, localNowMs = 101_900))
        assertEquals(30_000L, unpauseTarget(30_000, 600))
        assertEquals(30_250L, unpauseTarget(30_000, -250))
    }

    @Test fun backoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 32_000L), (0..6).map(::reconnectDelay))
    }

    @Test fun errors() {
        assertEquals("Your account can't use SyncPlay. An admin can allow it in Dashboard → Users → <you> → SyncPlay.", syncPlayError(ApiException(403, "Server error 403")))
        assertEquals("You're no longer in the group.", syncPlayError(ApiException(403, "Server error 403"), inGroup = true))
        assertEquals("Can't reach the server", syncPlayError(IOException("timeout")))
        assertEquals("Server error 500", syncPlayError(ApiException(500, "Server error 500")))
    }

    @Test fun localQueue() {
        fun item(id: String) = Item(org.json.JSONObject().put("Id", id))
        val q = Queue(listOf("a", "b", "a", "c").map(::item), 1)
        assertEquals("a", q.next?.id)
        // The repeated title is the next entry, not the first one again.
        assertEquals(2, q.at(item("a"))?.index)
        assertEquals(3, q.at(item("c"))?.index)
        assertNull(q.at(item("z")))
        assertNull(q.copy(index = 3).next)
    }
}
