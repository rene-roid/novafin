package xyz.yuuki.novafin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import xyz.yuuki.novafin.LibraryFilter
import xyz.yuuki.novafin.QueryFilters
import xyz.yuuki.novafin.Resolution
import xyz.yuuki.novafin.Watched
import xyz.yuuki.novafin.decadeYears
import xyz.yuuki.novafin.toggle

object LibraryState {
    var selected by mutableStateOf<String?>(null)
    // Per library, for the app session only.
    val filters = mutableStateMapOf<String, LibraryFilter>()
}

private val sorts = listOf(
    "Name" to "SortName,Ascending",
    "Recently added" to "DateCreated,Descending",
    "Release date" to "PremiereDate,Descending",
    "Rating" to "CommunityRating,Descending",
    "Recently watched" to "DatePlayed,Descending",
    "New episodes" to "DateLastContentAdded,Descending",
    "Random" to "Random,Ascending",
)

// Series only; movies don't have it.
private fun supportsNewEpisodes(view: Item?) = view?.collectionType !in setOf("movies", "boxsets", "musicvideos")

private fun typesFor(view: Item) = when (view.collectionType) {
    "movies" -> "Movie"
    "tvshows" -> "Series"
    "boxsets" -> "BoxSet"
    "musicvideos" -> "MusicVideo"
    else -> "Movie,Series,Video"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val api = App.it.api
    val prefs = App.it.prefs
    val views = rememberLoad("views") { api.views() }
    val all = views.data.orEmpty()
    val view = all.firstOrNull { it.id == LibraryState.selected } ?: all.firstOrNull()
    var sort by remember(view?.id) { mutableStateOf(view?.let { prefs.sort(it.id) } ?: "SortName,Ascending") }
    val filter = view?.let { LibraryState.filters[it.id] } ?: LibraryFilter()
    fun setFilter(f: LibraryFilter) = view?.let { LibraryState.filters[it.id] = f }
    var sheet by remember { mutableStateOf(false) }
    var surprise by remember(view?.id, filter) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Paged: 100 at a time, the next page loads near the end.
    val items = remember(view?.id, sort, filter) { mutableStateOf(listOf<Item>()) }
    var total by remember(view?.id, sort, filter) { mutableStateOf(-1) }
    var error by remember(view?.id, sort, filter) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val nav = LocalNav.current
    LaunchedEffect(nav.retap) { if (nav.retap > 0) grid.animateScrollToItem(0) }

    LaunchedEffect(view?.id, sort, filter) {
        val v = view ?: return@LaunchedEffect
        val q = filter.query
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            val have = items.value.size
            if ((total < 0 || have < total) && last >= have - 20) {
                try {
                    val page = api.withFailover { api.items(parentId = v.id, types = typesFor(v), sort = sort, filters = q.filters, start = have, extra = q.extra) }
                    if (items.value.size == have) {
                        items.value = items.value + page.items
                        total = page.total
                    }
                    error = null
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = loadError(e)
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SortMenu(sort, supportsNewEpisodes(view)) {
                        sort = it
                        view?.let { v -> prefs.setSort(v.id, it) }
                    }
                    OutlinedButton({ sheet = true }, Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                        Text(if (filter.chips.isEmpty()) "Filters" else "Filters (${filter.chips.size})", Modifier.padding(start = 8.dp))
                    }
                    IconButton({
                        val v = view ?: return@IconButton
                        scope.launch {
                            // Only unwatched, whatever the watched filter says.
                            val q = filter.copy(watched = Watched.Unwatched).query
                            surprise = try {
                                val pick = api.withFailover { api.items(parentId = v.id, types = typesFor(v), sort = "Random,Ascending", filters = q.filters, limit = 1, extra = q.extra) }.items.firstOrNull()
                                if (pick != null) nav.open(pick)
                                if (pick == null) "No unwatched titles to pick from" else null
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                loadError(e)
                            }
                        }
                    }) { Icon(Icons.Rounded.Shuffle, "Surprise me") }
                }
                surprise?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Sub) }
                if (!filter.isEmpty) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    filter.chips.forEach { c ->
                        InputChip(true, { setFilter(c.without) }, { Text(c.label) }, trailingIcon = { Icon(Icons.Rounded.Close, "Remove ${c.label}", Modifier.size(18.dp)) })
                    }
                    TextButton({ setFilter(LibraryFilter()) }) { Text("Clear all") }
                }
                if (total >= 0) Text(if (total == 1) "1 title" else "$total titles", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(items.value, key = { it.id }) { item -> PosterCard(item, if (tablet) 160.dp else 100.dp, Modifier.fillMaxWidth()) { nav.open(item) } }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error!!, null) }
        if (total == 0) item(span = { GridItemSpan(maxLineSpan) }) {
            if (filter.isEmpty) Empty("Nothing here") else Empty("No titles match these filters", "Clear filters") { setFilter(LibraryFilter()) }
        }
    }
    if (sheet && view != null) FilterSheet(view, filter, ::setFilter) { sheet = false }
}

@Composable
private fun SortMenu(sort: String, newEpisodes: Boolean, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (by, order) = sort.split(',').let { it[0] to it.getOrElse(1) { "Ascending" } }
    val desc = order == "Descending"
    Box {
        OutlinedButton({ open = true }, Modifier.heightIn(min = 48.dp)) {
            Text("Sort: ${sorts.firstOrNull { it.second.substringBefore(',') == by }?.first ?: by}")
            Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, if (desc) "Descending" else "Ascending", Modifier.padding(start = 6.dp).size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            sorts.filter { newEpisodes || it.second != "DateLastContentAdded,Descending" }.forEach { (label, value) ->
                DropdownMenuItem({ Text(label, color = if (value.substringBefore(',') == by) Glow else Color.Unspecified) }, { open = false; onSort(value) })
            }
            HorizontalDivider()
            DropdownMenuItem(
                { Text(if (desc) "Descending" else "Ascending") }, { onSort("$by,${if (desc) "Ascending" else "Descending"}") },
                trailingIcon = { Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(view: Item, f: LibraryFilter, onChange: (LibraryFilter) -> Unit, onDismiss: () -> Unit) {
    val facets = rememberLoad("filters:${view.id}") {
        val api = App.it.api
        runCatching { api.queryFilters(view.id, typesFor(view)) }.getOrElse { QueryFilters(api.genres(view.id, typesFor(view)), emptyList(), emptyList()) }
    }
    val decades = remember { (1950..java.time.Year.now().value step 10).map { it / 10 * 10 }.distinct() }
    @Composable fun Title(t: String) = Text(t, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
    ModalBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ onChange(LibraryFilter()) }, enabled = !f.isEmpty) { Text("Clear all") }
            }
            Title("Watched")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Watched.entries.forEach { w -> FilterChip(f.watched == w, { onChange(f.copy(watched = w)) }, { Text(w.label) }) }
            }
            Title("Favourites")
            FilterChip(f.favourites, { onChange(f.copy(favourites = !f.favourites)) }, { Text("Favourites only") })
            Title("Genres")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                facets.data?.genres.orEmpty().forEach { g -> FilterChip(g in f.genres, { onChange(f.copy(genres = f.genres.toggle(g))) }, { Text(g) }) }
            }
            if (facets.data == null) {
                if (facets.error != null) ErrorState(facets.error, facets.refresh) else CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
            }
            if (facets.data?.ratings?.isNotEmpty() == true) {
                Title("Rating")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.data.ratings.forEach { g -> FilterChip(g in f.ratings, { onChange(f.copy(ratings = f.ratings.toggle(g))) }, { Text(g) }) }
                }
            }
            Title("Decades")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                decades.forEach { d -> FilterChip(d in f.decades, { onChange(f.copy(decades = f.decades.toggle(d))) }, { Text("${d}s") }) }
            }
            Title("Resolution")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Resolution.entries.forEach { r -> FilterChip(f.resolution == r, { onChange(f.copy(resolution = r.takeIf { f.resolution != r })) }, { Text(r.label) }) }
            }
        }
    }
}

private enum class SearchType(val label: String, val types: String?) {
    All("All", "Movie,Series,Episode"), Movies("Movies", "Movie"), Shows("Shows", "Series"), Episodes("Episodes", "Episode"), People("People", null)
}

@Composable
fun SearchScreen() {
    val api = App.it.api
    val prefs = App.it.prefs
    val nav = LocalNav.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(SearchType.All) }
    var recents by remember { mutableStateOf(prefs.recentSearches) }
    fun save(q: String) {
        if (q.isEmpty()) return
        recents = (listOf(q) + recents.filterNot { it.equals(q, true) }).take(8)
        prefs.recentSearches = recents
    }
    LaunchedEffect(query) {
        delay(300)
        debounced = query.trim()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    val searching = debounced.length >= 2
    val titles = if (searching && type.types != null) rememberLoad("search:$debounced:${type.name}") { api.items(search = debounced, types = type.types, sort = "SortName,Ascending", limit = 60).items } else null
    val people = if (searching && (type == SearchType.All || type == SearchType.People)) rememberLoad("people:$debounced") { api.people(debounced) } else null
    val tablet = isTablet()
    val poster = if (tablet) 160.dp else 100.dp
    fun open(item: Item) {
        save(debounced)
        nav.open(item)
    }

    LazyVerticalGrid(
        GridCells.Adaptive(poster), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Header("Search", inGrid = true)
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
                    placeholder = { Text("Movies, shows, episodes, people") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        debounced = query.trim()
                        save(debounced)
                        keyboard?.hide()
                    }),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }, shape = RoundedCornerShape(20.dp)) }
                }
            }
        }
        if (!searching) {
            if (recents.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Empty("Search your libraries by title or person") }
            else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ recents = emptyList(); prefs.recentSearches = emptyList() }) { Text("Clear") }
                    }
                }
                items(recents, key = { "r:$it" }, span = { GridItemSpan(maxLineSpan) }) { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { query = r; debounced = r; save(r) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.History, null, tint = Sub)
                        Text(r, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@LazyVerticalGrid
        }
        val found = titles?.data.orEmpty()
        val folks = people?.data.orEmpty()
        val loaders = listOfNotNull(titles, people)
        val all = type == SearchType.All
        fun header(text: String) = if (all) item(span = { GridItemSpan(maxLineSpan) }) { Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge) } else Unit
        val movies = found.filter { !it.isEpisode && !it.isSeries }
        val shows = found.filter { it.isSeries }
        val episodes = found.filter { it.isEpisode }
        if (movies.isNotEmpty()) {
            header("Movies")
            items(movies, key = { it.id }) { item -> PosterCard(item, poster, Modifier.fillMaxWidth()) { open(item) } }
        }
        if (shows.isNotEmpty()) {
            header("Shows")
            items(shows, key = { it.id }) { item -> PosterCard(item, poster, Modifier.fillMaxWidth()) { open(item) } }
        }
        if (episodes.isNotEmpty()) {
            header("Episodes")
            items(episodes, key = { it.id }, span = { GridItemSpan(minOf(2, maxLineSpan)) }) { item -> ThumbCard(item, if (tablet) 340.dp else 230.dp, Modifier.fillMaxWidth()) { open(item) } }
        }
        if (folks.isNotEmpty()) {
            header("People")
            items(folks, key = { "p:${it.id}" }) { p ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Picture(p.poster, poster, Modifier.fillMaxWidth(.8f).aspectRatio(1f).clip(CircleShape))
                    Text(p.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        if (found.isEmpty() && folks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            val failed = loaders.firstOrNull { it.error != null }
            when {
                loaders.any { it.loading } && failed == null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                failed != null -> ErrorState(failed.error!!, { loaders.forEach { it.refresh() } })
                else -> Empty("No results for “$debounced”" + if (all) "" else " in ${type.label}")
            }
        }
    }
}
