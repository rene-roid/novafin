package xyz.yuuki.novafin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import xyz.yuuki.novafin.ApiException
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item
import xyz.yuuki.novafin.PlayerActivity

private val entryId = { it: Item -> it.json.optString("PlaylistItemId") }

private fun playQueue(ctx: Context, items: List<Item>, index: Int) {
    if (items.isEmpty()) return
    App.it.playback.playQueue(items, index)
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
}

private fun forgetPlaylists(id: String? = null) {
    forgetUi("playlists")
    id?.let { forgetUi("playlist:$it") }
}

@Composable
private fun NewPlaylistDialog(error: String?, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismiss, title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text("Name") }, isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton({ onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlaylistsScreen() {
    val api = App.it.api
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad("playlists") { api.playlists() }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val list = load.data

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                PageTop("Playlists")
                OutlinedButton({ createError = null; creating = true }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Text("New playlist", Modifier.padding(start = 8.dp))
                }
            }
        }
        if (list != null) items(list.size, key = { list[it].id }) { i ->
            val p = list[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Route.Playlist(p.id, p.name)) },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Picture(p.thumb ?: p.poster, 150.dp, Modifier.width(150.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.childCount.takeIf { it > 0 }?.let { Text(if (it == 1) "1 title" else "$it titles", style = MaterialTheme.typography.bodySmall, color = Sub) }
                }
            }
        }
        when {
            list == null && load.error != null -> item { ErrorState(load.error, load.refresh) }
            list == null -> item { LoadingRows() }
            list.isEmpty() -> item { Empty("No playlists yet. Add titles from their page with Add to playlist.") }
        }
    }
    if (creating) NewPlaylistDialog(createError, { name ->
        scope.launch {
            try {
                val id = api.withFailover { api.createPlaylist(name, emptyList()) }
                forgetPlaylists()
                creating = false
                nav.go(Route.Playlist(id, name))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                createError = loadError(e)
            }
        }
    }, { creating = false })
}

@Composable
fun PlaylistScreen(id: String, name: String) {
    val api = App.it.api
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad("playlist:$id") { api.playlistItems(id) }
    val items = load.data.orEmpty()
    var edit by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun change(block: suspend () -> Unit) = scope.launch {
        try {
            api.withFailover { block() }
            forgetPlaylists()
            load.refresh()
            note = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ApiException && e.code == 403) { note = "Only the owner can change this playlist"; edit = false } else note = loadError(e)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(end = 16.dp)) {
                PageTop(name)
                if (load.data != null) {
                    val total = items.sumOf { it.runTimeMs }
                    Text(
                        (if (items.size == 1) "1 title" else "${items.size} titles") + if (total > 0) " · ${formatRuntime(total)}" else "",
                        Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = Sub,
                    )
                    Row(Modifier.padding(start = 16.dp, top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (items.isNotEmpty()) {
                            AuroraButton({ playQueue(ctx, items, 0) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all", Modifier.padding(start = 6.dp)) }
                            OutlinedButton({ playQueue(ctx, items.shuffled(), 0) }, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Text("Shuffle", Modifier.padding(start = 6.dp)) }
                        }
                        FilterChip(edit, { edit = !edit }, { Text("Edit") }, shape = RoundedCornerShape(20.dp))
                    }
                }
                note?.let { Text(it, Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Sub) }
            }
        }
        itemsIndexed(items, key = { i, it -> entryId(it).ifEmpty { it.id + i } }) { i, it ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.open(it) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(130.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp))) {
                    Picture(it.thumb ?: it.poster, 130.dp, Modifier.fillMaxSize())
                    ProgressLine(it, Modifier.align(Alignment.BottomCenter))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (it.isEpisode) it.seriesName ?: it.name else it.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(if (it.isEpisode) "${it.episodeLabel} · ${it.name}" else it.year?.toString(), it.runTimeMs.takeIf { m -> m > 0 }?.let(::formatRuntime)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (edit) {
                    IconButton({ change { api.movePlaylistItem(id, entryId(it), i - 1) } }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                    IconButton({ change { api.movePlaylistItem(id, entryId(it), i + 1) } }, enabled = i < items.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                    IconButton({ change { api.removeFromPlaylist(id, listOf(entryId(it))) } }) { Icon(Icons.Rounded.Close, "Remove") }
                } else IconButton({ playQueue(ctx, items, i) }) { Icon(Icons.Rounded.PlayArrow, "Play from here") }
            }
        }
        when {
            load.data == null && load.error != null -> item { ErrorState(load.error, load.refresh) }
            load.data == null -> item { LoadingRows() }
            items.isEmpty() -> item { Empty("This playlist is empty") }
        }
    }
}

@Composable
fun AddToPlaylistDialog(item: Item, onDismiss: () -> Unit) {
    val app = App.it
    val api = app.api
    val scope = rememberCoroutineScope()
    val load = rememberLoad("playlists") { api.playlists() }
    var top by remember { mutableStateOf(app.prefs.playlistAddToTop) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun add(pick: Item?, newName: String? = null) = scope.launch {
        busy = true
        try {
            val target = api.withFailover {
                if (pick == null) return@withFailover api.createPlaylist(newName!!, listOf(item.id)) to newName
                api.addToPlaylist(pick.id, listOf(item.id))
                if (top) {
                    // The new entry has no id until the list is read again.
                    val entry = api.playlistItems(pick.id).lastOrNull { it.id == item.id }?.let(entryId)
                    if (!entry.isNullOrEmpty()) api.movePlaylistItem(pick.id, entry, 0)
                }
                pick.id to pick.name
            }
            forgetPlaylists(target.first)
            failed = false
            message = "Added to ${target.second}"
            creating = false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
            message = if (e is ApiException && e.code == 403) "Only the owner can change this playlist" else loadError(e)
        }
        busy = false
    }

    AlertDialog(
        onDismiss, title = { Text("Add to playlist") },
        text = {
            Column {
                if (message != null) Text(message!!, color = if (failed) MaterialTheme.colorScheme.error else Glow, style = MaterialTheme.typography.bodyMedium)
                else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "Add to the end", true to "Add to the start").forEach { (v, label) ->
                            FilterChip(top == v, { top = v; app.prefs.playlistAddToTop = v }, { Text(label) }, shape = RoundedCornerShape(20.dp))
                        }
                    }
                    Gap(8.dp)
                    val list = load.data
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        TextButton({ creating = true }, enabled = !busy) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("New playlist…", Modifier.padding(start = 8.dp)) }
                        list?.forEach { p -> TextButton({ add(p) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(p.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        when {
                            list == null && load.error != null -> ErrorState(load.error, load.refresh)
                            list == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = Sub)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(if (message != null && !failed) "Done" else "Cancel") } },
    )
    if (creating) NewPlaylistDialog(if (failed) message else null, { add(null, it) }, { creating = false })
}

private enum class FavType(val label: String, val types: String) {
    All("All", "Movie,Series,Episode"), Movies("Movies", "Movie"), Shows("Shows", "Series"), Episodes("Episodes", "Episode")
}

private val favSorts = listOf("Name" to "SortName,Ascending", "Date added" to "DateCreated,Descending", "Release date" to "PremiereDate,Descending", "Recently watched" to "DatePlayed,Descending")

@Composable
fun FavoritesScreen() {
    val api = App.it.api
    val prefs = App.it.prefs
    val nav = LocalNav.current
    var type by rememberSaveable { mutableStateOf(FavType.All) }
    var sort by remember { mutableStateOf(prefs.sort("favorites")) }
    var menu by remember { mutableStateOf(false) }
    val items = remember(type, sort) { mutableStateOf(listOf<Item>()) }
    var total by remember(type, sort) { mutableStateOf(-1) }
    var error by remember(type, sort) { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val grid = rememberLazyGridState()

    LaunchedEffect(type, sort, retry) {
        grid.scrollToItem(0)
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            val have = items.value.size
            if ((total < 0 || have < total) && last >= have - 20) {
                try {
                    val page = api.withFailover { api.items(types = type.types, sort = sort, filters = "IsFavorite", start = have) }
                    if (items.value.size == have) {
                        items.value = items.value + page.items
                        total = page.total
                    }
                    error = null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
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
                PageTop("Favourites")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FavType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }, shape = RoundedCornerShape(20.dp)) }
                    Box {
                        OutlinedButton({ menu = true }, Modifier.heightIn(min = 48.dp)) {
                            Text("Sort: ${favSorts.firstOrNull { it.second == sort }?.first ?: "Name"}")
                            Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            favSorts.forEach { (label, v) ->
                                DropdownMenuItem({ Text(label, color = if (v == sort) Glow else Color.Unspecified) }, { menu = false; sort = v; prefs.setSort("favorites", v) })
                            }
                        }
                    }
                }
                if (total >= 0) Text(if (total == 1) "1 title" else "$total titles", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(items.value, key = { it.id }, span = { GridItemSpan(if (it.isEpisode) minOf(2, maxLineSpan) else 1) }) { item ->
            if (item.isEpisode) ThumbCard(item, if (tablet) 340.dp else 230.dp, Modifier.fillMaxWidth()) { nav.open(item) }
            else PosterCard(item, if (tablet) 160.dp else 100.dp, Modifier.fillMaxWidth()) { nav.open(item) }
        }
        if (total < 0 && error == null) item(span = { GridItemSpan(maxLineSpan) }) { LoadingRows() }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error!!, { error = null; retry++ }) }
        if (total == 0) item(span = { GridItemSpan(maxLineSpan) }) { Empty("No favourites yet. Tap the heart on a title's page.") }
    }
}
