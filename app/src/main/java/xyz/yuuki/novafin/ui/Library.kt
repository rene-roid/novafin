package xyz.yuuki.novafin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item

object LibraryState {
    var selected by mutableStateOf<String?>(null)
}

private val sorts = listOf(
    "Name" to "SortName,Ascending",
    "Recently added" to "DateCreated,Descending",
    "Release date" to "PremiereDate,Descending",
    "Rating" to "CommunityRating,Descending",
    "Recently watched" to "DatePlayed,Descending",
    "Random" to "Random,Ascending",
)

private fun typesFor(view: Item) = when (view.collectionType) {
    "movies" -> "Movie"
    "tvshows" -> "Series"
    "boxsets" -> "BoxSet"
    "musicvideos" -> "MusicVideo"
    else -> "Movie,Series,Video"
}

@Composable
fun LibraryScreen() {
    val api = App.it.api
    val prefs = App.it.prefs
    val views = rememberLoad("views") { api.views() }
    val all = views.data.orEmpty()
    val view = all.firstOrNull { it.id == LibraryState.selected } ?: all.firstOrNull()
    var sort by remember(view?.id) { mutableStateOf(view?.let { prefs.sort(it.id) } ?: "SortName,Ascending") }
    var filter by rememberSaveable(view?.id) { mutableStateOf<String?>(null) }
    // Paged: 100 at a time, the next page loads near the end.
    val items = remember(view?.id, sort, filter) { mutableStateOf(listOf<Item>()) }
    var total by remember(view?.id, sort, filter) { mutableStateOf(-1) }
    var error by remember(view?.id, sort, filter) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val nav = LocalNav.current
    LaunchedEffect(nav.retap) { if (nav.retap > 0) grid.animateScrollToItem(0) }

    LaunchedEffect(view?.id, sort, filter) {
        val v = view ?: return@LaunchedEffect
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            val have = items.value.size
            if ((total < 0 || have < total) && last >= have - 20) {
                try {
                    val page = api.items(parentId = v.id, types = typesFor(v), sort = sort, filters = filter, start = have)
                    if (items.value.size == have) {
                        items.value = items.value + page.items
                        total = page.total
                    }
                    error = null
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: "Couldn't load"
                }
            }
        }
    }

    val tablet = isTablet()
    LazyVerticalGrid(
        GridCells.Adaptive(if (tablet) 160.dp else 100.dp), Modifier.fillMaxSize(), grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Header("Library", inGrid = true)
                if (views.error != null && all.isEmpty()) ErrorState(views.error, views.refresh)
                LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(all, key = { it.id }) { v ->
                        FilterChip(v.id == view?.id, { LibraryState.selected = v.id }, { Text(v.name) }, shape = RoundedCornerShape(20.dp))
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sorts) { (label, value) ->
                        FilterChip(sort == value, {
                            sort = value
                            view?.let { prefs.setSort(it.id, value) }
                        }, { Text(label) })
                    }
                    item { FilterChip(filter == "IsUnplayed", { filter = if (filter == "IsUnplayed") null else "IsUnplayed" }, { Text("Unwatched") }) }
                    item { FilterChip(filter == "IsFavorite", { filter = if (filter == "IsFavorite") null else "IsFavorite" }, { Text("Favourites") }) }
                }
                if (total >= 0) Text("$total titles", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(items.value, key = { it.id }) { item -> PosterCard(item, if (tablet) 160.dp else 100.dp, Modifier.fillMaxWidth()) { nav.open(item) } }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error!!, null) }
        if (total == 0) item(span = { GridItemSpan(maxLineSpan) }) { Empty("Nothing here") }
    }
}

@Composable
fun SearchScreen() {
    val api = App.it.api
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debounced = query.trim()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    val results = if (debounced.length >= 2) rememberLoad("search:$debounced") { api.items(search = debounced, types = "Movie,Series,Episode", sort = "SortName,Ascending", limit = 60).items } else null
    val tablet = isTablet()

    LazyVerticalGrid(
        GridCells.Adaptive(if (tablet) 160.dp else 100.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Header("Search", inGrid = true)
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
                    placeholder = { Text("Movies, shows, episodes") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp),
                )
            }
        }
        val found = results?.data.orEmpty()
        val (episodes, titles) = found.partition { it.isEpisode }
        items(titles, key = { it.id }) { item -> PosterCard(item, if (tablet) 160.dp else 100.dp, Modifier.fillMaxWidth()) { nav.open(item) } }
        if (episodes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Episodes", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge) }
        items(episodes, key = { it.id }, span = { GridItemSpan(minOf(2, maxLineSpan)) }) { item -> ThumbCard(item, if (tablet) 340.dp else 230.dp, Modifier.fillMaxWidth()) { nav.open(item) } }
        if (results != null && !results.loading && found.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            if (results.error != null) ErrorState(results.error, results.refresh) else Empty("No results for “$debounced”")
        }
    }
}
