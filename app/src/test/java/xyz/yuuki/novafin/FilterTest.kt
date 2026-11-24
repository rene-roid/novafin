package xyz.yuuki.novafin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    private val everything = LibraryFilter(Watched.Unwatched, true, setOf("Drama", "Comedy"), setOf(1990), Resolution.UHD)

    @Test fun empty() {
        val q = LibraryFilter().query
        assertNull(q.filters)
        assertEquals(emptyList<Pair<String, Any?>>(), q.extra)
        assertTrue(LibraryFilter().isEmpty && LibraryFilter().chips.isEmpty())
    }

    @Test fun everythingCombined() {
        val q = everything.query
        assertEquals("IsUnplayed,IsFavorite", q.filters)
        assertEquals(
            listOf("genres" to "Drama|Comedy", "years" to "1990,1991,1992,1993,1994,1995,1996,1997,1998,1999", "is4K" to true),
            q.extra,
        )
    }

    @Test fun twoDecades() {
        val q = LibraryFilter(decades = setOf(2000, 1980)).query
        assertEquals(listOf("years" to "1980,1981,1982,1983,1984,1985,1986,1987,1988,1989,2000,2001,2002,2003,2004,2005,2006,2007,2008,2009"), q.extra)
    }

    @Test fun removingOneChip() {
        val chips = everything.chips
        assertEquals(listOf("Unwatched", "Favourites", "Comedy", "Drama", "1990s", "4K"), chips.map { it.label })
        val q = chips.first { it.label == "Drama" }.without.query
        assertEquals("IsUnplayed,IsFavorite", q.filters)
        assertEquals("Comedy", q.extra.first().second)
        assertEquals(LibraryFilter(), LibraryFilter(favourites = true).chips.single().without)
    }

    @Test fun sdMeansNotHd() {
        assertEquals(listOf("isHd" to false), LibraryFilter(resolution = Resolution.SD).query.extra)
    }

    @Test fun decadeExpansion() {
        assertEquals(listOf(1950, 1951, 1952, 1953, 1954, 1955, 1956, 1957, 1958, 1959), decadeYears(1950))
    }
}
