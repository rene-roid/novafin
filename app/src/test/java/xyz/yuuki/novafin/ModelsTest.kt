package xyz.yuuki.novafin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun recommendation() {
        val r = Recommendation.from(JSONObject("""{"BaselineItemName":"Arrival","RecommendationType":"SimilarToRecentlyPlayed","Items":[{"Id":"a","Name":"Dune"}]}"""))
        assertEquals("Because you watched Arrival", r.title)
        assertEquals(listOf("a"), r.items.map { it.id })
    }

    @Test fun recommendationTitles() {
        fun title(t: String) = Recommendation(t, "X", emptyList()).title
        assertEquals("Because you liked X", title("SimilarToLikedItem"))
        assertEquals("Directed by X", title("HasDirectorFromRecentlyPlayed"))
        assertEquals("Directed by X", title("HasLikedDirector"))
        assertEquals("Starring X", title("HasActorFromRecentlyPlayed"))
        assertEquals("Starring X", title("HasLikedActor"))
        assertEquals("Recommended", title("Other"))
    }

    @Test fun session() {
        val s = Session.from(JSONObject("""{"Id":"s1","DeviceId":"d","DeviceName":"Living room TV","Client":"Jellyfin Web","UserName":"ana","NowPlayingItem":{"Id":"m","Name":"Dune"},"PlayState":{"PositionTicks":600000000,"IsPaused":true}}"""))
        assertEquals("Living room TV", s.deviceName)
        assertEquals("Dune", s.nowPlaying?.name)
        assertEquals(60_000L, s.positionMs)
        assertTrue(s.paused)
        val idle = Session.from(JSONObject("""{"Id":"s2","DeviceName":"Phone"}"""))
        assertNull(idle.nowPlaying)
        assertFalse(idle.paused)
        assertEquals(0L, idle.positionMs)
    }

    @Test fun queryFilters() {
        val f = QueryFilters.from(JSONObject("""{"Genres":["Drama"],"OfficialRatings":["PG","R"],"Years":[1999,2021,2005]}"""))
        assertEquals(listOf("Drama"), f.genres)
        assertEquals(listOf("PG", "R"), f.ratings)
        assertEquals(listOf(2021, 2005, 1999), f.years)
    }

    @Test fun homeLayoutKeepsOrderHidesAndAddsNewRows() {
        assertEquals(HomeSection.entries, homeLayout(null, emptySet()))
        assertEquals(
            listOf(HomeSection.TopUnwatched, HomeSection.Resume, HomeSection.Latest, HomeSection.Libraries, HomeSection.Recommended),
            homeLayout(listOf("TopUnwatched", "Resume", "NextUp", "Latest", "Libraries"), setOf("NextUp")),
        )
    }
}
