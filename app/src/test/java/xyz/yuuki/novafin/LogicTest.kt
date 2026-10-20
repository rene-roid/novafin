package xyz.yuuki.novafin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LogicTest {
    private fun s(index: Int, type: String, lang: String?, title: String? = null, forced: Boolean = false, default: Boolean = false, channels: Int = 0) =
        Stream(index, type, null, lang, title, title ?: lang ?: "", default, forced, false, true, channels, 0, 0, 0, 0, null, null)

    // A typical dual-audio anime episode.
    private val anime = listOf(
        s(0, "Video", null),
        s(1, "Audio", "eng", "Surround 5.1", default = true, channels = 6),
        s(2, "Audio", "jpn", "Stereo", channels = 2),
        s(3, "Subtitle", "eng", "Signs & Songs"),
        s(4, "Subtitle", "eng", "Full Subtitles"),
    )
    private val none = TrackDefaults(emptyList(), emptyList(), "Default")

    @Test fun remembersJapaneseWithFullSubs() {
        val mem = memoryFor(anime, 2, 4)
        assertEquals("jpn", mem.audioLang)
        assertFalse(mem.subForced)
        assertEquals(2, pickAudio(anime, mem, none))
        assertEquals(4, pickSubtitle(anime, mem, none, "jpn"))
    }

    @Test fun remembersSignsOverFull() {
        val mem = memoryFor(anime, 1, 3)
        assertTrue(mem.subForced)
        // Next episode lists the tracks in a different order.
        val next = listOf(anime[0], anime[2], anime[1], s(3, "Subtitle", "eng", "English"), s(4, "Subtitle", "eng", "Signs/Songs"))
        assertEquals(4, pickSubtitle(next, mem, none, "eng"))
    }

    @Test fun remembersSubtitlesOff() {
        val mem = memoryFor(anime, 2, -1)
        assertEquals(-1, pickSubtitle(anime, mem, TrackDefaults(emptyList(), listOf("eng"), "Always"), "jpn"))
    }

    @Test fun fallsBackToDefaults() {
        assertEquals(1, pickAudio(anime, null, none))
        assertEquals(2, pickAudio(anime, null, TrackDefaults(listOf("jpn"), emptyList(), "Default")))
        assertEquals(4, pickSubtitle(anime, null, TrackDefaults(listOf("jpn"), listOf("eng"), "Smart"), "jpn"))
        assertEquals(3, pickSubtitle(anime, null, TrackDefaults(listOf("eng"), listOf("eng"), "Smart"), "eng"))
        assertEquals(-1, pickSubtitle(anime, null, none, "eng"))
    }

    @Test fun rememberedLanguageMissingUsesDefaults() {
        val mem = TrackMemory(audioLang = "spa", subLang = "spa")
        assertEquals(1, pickAudio(anime, mem, none))
        assertEquals(4, pickSubtitle(anime, mem, TrackDefaults(emptyList(), listOf("eng"), "Always"), "eng"))
    }

    @Test fun languagePreferencesInOrder() {
        // Spanish isn't there, so the second choice wins.
        assertEquals(2, pickAudio(anime, null, TrackDefaults(listOf("spa", "jpn"), emptyList(), "Default")))
        // Settings, files and ExoPlayer spell languages differently.
        assertTrue(sameLang("fre", "fra"))
        assertTrue(sameLang("en", "eng"))
        assertTrue(sameLang("ja", "jpn"))
        assertFalse(sameLang("eng", "jpn"))
        val french = listOf(s(1, "Audio", "fre"), s(2, "Subtitle", "fre", "Full"), s(3, "Subtitle", "eng", "Full"))
        assertEquals(1, pickAudio(french, null, TrackDefaults(listOf("fra"), emptyList(), "Default")))
        assertEquals(3, pickSubtitle(french, null, TrackDefaults(emptyList(), listOf("spa", "eng"), "Always"), "fre"))
    }

    @Test fun syncNewerWins() {
        val ours = Instant.parse("2026-10-10T10:00:00Z")
        assertTrue(shouldUpload(null, ours))
        assertTrue(shouldUpload(Instant.parse("2026-10-09T10:00:00Z"), ours))
        assertFalse(shouldUpload(Instant.parse("2026-10-11T10:00:00Z"), ours))
    }

    @Test fun choosesAddress() {
        val order = listOf("lan", "ts", "pub")
        assertEquals("lan", chooseAddress(order, mapOf("lan" to 120L, "ts" to 300L, "pub" to 40L), 250))
        assertEquals("pub", chooseAddress(order, mapOf("lan" to 400L, "ts" to null, "pub" to 40L), 250))
        assertEquals("ts", chooseAddress(order, mapOf("lan" to null, "ts" to 90L, "pub" to 60L), 250))
        assertNull(chooseAddress(order, mapOf("lan" to null, "ts" to null, "pub" to null), 250))
    }

    @Test fun addressKinds() {
        assertEquals(AddressKind.LAN, addressKind("http://192.168.68.63:8091"))
        assertEquals(AddressKind.Tailscale, addressKind("http://100.101.2.3:8096"))
        assertEquals(AddressKind.Tailscale, addressKind("https://box.tail1234.ts.net"))
        assertEquals(AddressKind.Public, addressKind("https://jellyfin.example.com"))
        val list = listOf(Address("http://100.101.2.3", "TS"), Address("https://jf.example.com", "Public"), Address("http://192.168.1.2", "LAN"))
        assertEquals("http://192.168.1.2", castAddress(list, "http://192.168.1.2"))
        assertEquals("https://jf.example.com", castAddress(list, "http://100.101.2.3"))
    }

    @Test fun normalizesAddresses() {
        assertEquals("http://192.168.68.63:8091", normalizeAddress("192.168.68.63:8091/web/#/home"))
        assertEquals("https://jf.example.com/jellyfin", normalizeAddress("https://jf.example.com/jellyfin/web/index.html"))
        assertEquals("http://host", normalizeAddress("Http://Host/"))
    }

    @Test fun profileFollowsCaps() {
        val caps = Caps(mapOf("h264" to VideoCap(1920, 1080, false), "av1" to VideoCap(1920, 1080, false)), setOf("aac", "opus"))
        val p = buildProfile(caps, 3_000_000, downmix = true, skipAv1 = true)
        val dp = p.getJSONArray("DirectPlayProfiles").getJSONObject(0)
        assertEquals("h264", dp.getString("VideoCodec"))
        assertEquals("h264", p.getJSONArray("TranscodingProfiles").getJSONObject(0).getString("VideoCodec"))
        assertEquals("2", p.getJSONArray("TranscodingProfiles").getJSONObject(0).getString("MaxAudioChannels"))
        val av1 = (0 until p.getJSONArray("CodecProfiles").length()).map { p.getJSONArray("CodecProfiles").getJSONObject(it) }.first { it.getString("Codec") == "av1" }
        assertTrue(av1.getJSONArray("Conditions").toString().contains("VideoBitDepth"))
    }

    @Test fun parsesItem() {
        val o = JSONObject(
            """{"Id":"e1","Name":"What I Can Do For Now","Type":"Episode","SeriesId":"s1","RunTimeTicks":14310130000,"IndexNumber":5,"ParentIndexNumber":4,
            "SeriesPrimaryImageTag":"t","ParentBackdropItemId":"s1","ParentBackdropImageTags":["b"],
            "UserData":{"PlaybackPositionTicks":12065801039,"Played":false,"LastPlayedDate":"2026-10-12T11:04:16.8794793Z"},
            "MediaStreams":[{"Index":1,"Type":"Audio","Language":"jpn","Codec":"opus","Channels":2,"DisplayTitle":"Stereo - Japanese - OPUS"}]}""",
        )
        val item = Item(o)
        assertEquals("S4:E5", item.episodeLabel)
        assertEquals(1_431_013L, item.runTimeMs)
        assertEquals(1_206_580L, item.userData.positionMs)
        assertEquals(Img("s1", "Primary", "t"), item.poster)
        assertEquals(Img("s1", "Backdrop", "b"), item.backdrop)
        assertEquals("s1", item.memoryKey)
        assertEquals("jpn", item.streams.single().language)
        assertTrue(item.userData.lastPlayed!!.isAfter(Instant.parse("2026-10-12T11:00:00Z")))
    }

    @Test fun qualityAuto() {
        assertEquals(Quality.High, Quality.forBandwidth(20_000_000))
        assertEquals(Quality.Low, Quality.forBandwidth(2_000_000))
        assertEquals(Quality.DataSaver, Quality.forBandwidth(300_000))
    }
}
