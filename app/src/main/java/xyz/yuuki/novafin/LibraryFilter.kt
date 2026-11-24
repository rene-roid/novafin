package xyz.yuuki.novafin

enum class Watched(val label: String, val param: String?) {
    All("All", null), Unwatched("Unwatched", "IsUnplayed"), InProgress("In progress", "IsResumable"), Watched("Watched", "IsPlayed"),
}

/** One choice: Jellyfin ANDs `isHd` and `is4K`, and 4K files also count as HD. */
enum class Resolution(val label: String, val param: Pair<String, Boolean>) { SD("SD", "isHd" to false), HD("HD and up", "isHd" to true), UHD("4K", "is4K" to true) }

class FilterQuery(val filters: String?, val extra: List<Pair<String, Any?>>)

class FilterChipInfo(val label: String, val without: LibraryFilter)

data class LibraryFilter(
    val watched: Watched = Watched.All,
    val favourites: Boolean = false,
    val genres: Set<String> = emptySet(),
    val decades: Set<Int> = emptySet(),
    val resolution: Resolution? = null,
) {
    val isEmpty get() = this == LibraryFilter()

    val query: FilterQuery
        get() = FilterQuery(
            listOfNotNull(watched.param, "IsFavorite".takeIf { favourites }).joinToString(",").ifEmpty { null },
            buildList {
                if (genres.isNotEmpty()) add("genres" to genres.joinToString("|"))
                if (decades.isNotEmpty()) add("years" to decades.sorted().flatMap(::decadeYears).joinToString(","))
                resolution?.let { add(it.param) }
            },
        )

    val chips: List<FilterChipInfo>
        get() = buildList {
            if (watched != Watched.All) add(FilterChipInfo(watched.label, copy(watched = Watched.All)))
            if (favourites) add(FilterChipInfo("Favourites", copy(favourites = false)))
            genres.sorted().forEach { add(FilterChipInfo(it, copy(genres = genres - it))) }
            decades.sorted().forEach { add(FilterChipInfo("${it}s", copy(decades = decades - it))) }
            resolution?.let { add(FilterChipInfo(it.label, copy(resolution = null))) }
        }
}

fun decadeYears(decade: Int) = (decade..decade + 9).toList()

fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
