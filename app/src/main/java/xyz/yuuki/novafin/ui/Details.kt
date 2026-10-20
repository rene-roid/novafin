package xyz.yuuki.novafin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item
import xyz.yuuki.novafin.Playback
import xyz.yuuki.novafin.Quality
import xyz.yuuki.novafin.Stream
import xyz.yuuki.novafin.memoryFor
import xyz.yuuki.novafin.pickAudio
import xyz.yuuki.novafin.pickSubtitle

/** Loads an item, falling back to its downloaded copy when the server can't be reached. */
private suspend fun loadItem(id: String): Item = try {
    App.it.api.item(id)
} catch (e: Exception) {
    App.it.downloads.offlineItem(id) ?: throw e
}

@Composable
fun DetailsScreen(id: String) {
    val load = rememberLoad("item:$id") { loadItem(id) }
    val item = load.data
    when {
        item == null && load.error != null -> Column { BackBar(); ErrorState(load.error, load.refresh) }
        item == null -> Column { BackBar(); LoadingRows() }
        else -> Details(item, load.refresh)
    }
}

@Composable
private fun BackBar(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    IconButton({ nav.back() }, modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp).background(Bg.copy(alpha = .5f), CircleShape)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
    }
}

@Composable
private fun Details(item: Item, refresh: () -> Unit) {
    val tablet = widthClass() == Width.Expanded
    val seasons = if (item.isSeries || item.isEpisode) rememberLoad("seasons:${item.seriesId ?: item.id}") {
        val sid = item.seriesId ?: item.id
        runCatching { App.it.api.seasons(sid) }.getOrElse { App.it.downloads.offlineSeasons(sid).ifEmpty { throw it } }
    } else null

    if (tablet) {
        // Two panes: artwork and actions on the left, everything else on the right.
        Box(Modifier.fillMaxSize()) {
            Picture(item.backdrop ?: item.thumb, 1600.dp, Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)))
            Box(Modifier.fillMaxSize().background(Bg.copy(alpha = .82f)))
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(360.dp).fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                    item { BackBar(Modifier.padding(bottom = 12.dp)) }
                    item { Picture(if (item.isEpisode) item.thumb else item.poster ?: item.thumb, 320.dp, Modifier.fillMaxWidth().aspectRatio(if (item.isEpisode) 16f / 9 else 2f / 3).clip(RoundedCornerShape(12.dp))) }
                    item { Gap(16.dp); Actions(item, refresh) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = PaddingValues(top = 48.dp, end = 24.dp, bottom = 32.dp)) {
                    item { Title(item) }
                    body(item, seasons, refresh)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10)) {
                    Picture(item.backdrop ?: item.thumb, 800.dp, Modifier.fillMaxSize().scrim())
                    TopScrim()
                    BackBar()
                }
            }
            item { Title(item) }
            item { Actions(item, refresh) }
            body(item, seasons, refresh)
        }
    }
}

@Composable
private fun Title(item: Item) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.isEpisode) Text(item.seriesName ?: "", style = MaterialTheme.typography.titleMedium, color = Dusk)
        val logo = item.logo?.takeIf { !item.isEpisode }
        if (logo != null) AsyncImage(imageModel(logo, 320.dp), item.name, Modifier.height(80.dp).widthIn(max = 320.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
        else Text(item.name, style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val bits = listOfNotNull(
                item.episodeLabel, item.year?.toString(), item.officialRating,
                item.runTimeMs.takeIf { it > 0 }?.let(::formatRuntime),
                item.childCount.takeIf { item.isSeries && it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
            )
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Sub)
            item.communityRating?.let {
                Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Glow)
                Text("%.1f".format(it), style = MaterialTheme.typography.bodyMedium, color = Sub)
            }
        }
        if (item.genres.isNotEmpty()) Text(item.genres.take(4).joinToString(", "), style = MaterialTheme.typography.bodySmall)
        Gap(8.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(item: Item, refresh: () -> Unit) {
    val app = App.it
    val scope = rememberCoroutineScope()
    val play = rememberPlay()
    var played by remember(item) { mutableStateOf(item.userData.played) }
    var fav by remember(item) { mutableStateOf(item.userData.favorite) }
    var downloading by remember { mutableStateOf(false) }
    val dl by app.downloads.states.collectAsStateWithLifecycle()
    val streams = item.streams
    val mem = app.prefs.memory(item.memoryKey)
    val defaults = Playback.defaults(app.prefs)
    var audio by remember(item) { mutableStateOf(pickAudio(streams, mem, defaults)) }
    var sub by remember(item) { mutableStateOf(pickSubtitle(streams, mem, defaults, streams.firstOrNull { it.index == audio }?.language)) }
    // Shows play their next episode.
    val nextUp = if (item.isSeries) rememberLoad("nextup:${item.id}") {
        app.api.episodes(item.id).let { eps -> eps.firstOrNull { !it.userData.played } ?: eps.firstOrNull() }
    }.data else null
    val target = if (item.isSeries) nextUp else item.takeIf { it.playable }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target != null) {
                val resumeAt = target.userData.positionMs.takeIf { it > 0 && !target.userData.played }
                AuroraButton({ play(target, null, audio.takeIf { target == item }, sub.takeIf { target == item }) }) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            item.isSeries -> "Play ${target.episodeLabel ?: ""}".trim()
                            resumeAt != null -> "Resume ${formatTime(resumeAt)}"
                            else -> "Play"
                        },
                    )
                }
                if (resumeAt != null && !item.isSeries) OutlinedButton({ play(target, 0, audio, sub) }) {
                    Icon(Icons.Rounded.Replay, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start over")
                }
            }
            FilledTonalIconButton({
                played = !played
                scope.launch { runCatching { app.api.setPlayed(item.id, played) }.onFailure { played = !played }; forgetUi("home"); refresh() }
            }) { Icon(if (played) Icons.Rounded.CheckCircle else Icons.Rounded.Check, if (played) "Mark unwatched" else "Mark watched", tint = if (played) Violet else Color.White) }
            FilledTonalIconButton({
                fav = !fav
                scope.launch { runCatching { app.api.setFavorite(item.id, fav) }.onFailure { fav = !fav } }
            }) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = if (fav) Dusk else Color.White) }
            if (item.playable || item.isSeries || item.isSeason) {
                val state = dl[item.id]
                FilledTonalIconButton({ if (state?.done == true) app.downloads.remove(item.id) else downloading = true }) {
                    when {
                        state?.done == true -> Icon(Icons.Rounded.DownloadDone, "Remove download", tint = Glow)
                        state != null && state.state != androidx.media3.exoplayer.offline.Download.STATE_FAILED ->
                            CircularProgressIndicator({ state.percent / 100f }, Modifier.size(22.dp), strokeWidth = 2.dp)
                        else -> Icon(Icons.Rounded.Download, "Download")
                    }
                }
            }
        }
        if (item.playable && streams.any { it.isAudio || it.isSub }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackPicker(Icons.Rounded.VolumeUp, streams.filter { it.isAudio }, audio, allowOff = false) {
                    audio = it
                    app.prefs.remember(item.memoryKey, memoryFor(streams, audio, sub))
                }
                TrackPicker(Icons.Rounded.Subtitles, streams.filter { it.isSub }, sub, allowOff = true) {
                    sub = it ?: -1
                    app.prefs.remember(item.memoryKey, memoryFor(streams, audio, sub))
                }
            }
        }
    }
    if (downloading) DownloadDialog(item) { downloading = false }
}

@Composable
private fun TrackPicker(icon: androidx.compose.ui.graphics.vector.ImageVector, streams: List<Stream>, selected: Int?, allowOff: Boolean, onPick: (Int?) -> Unit) {
    if (streams.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(streams.firstOrNull { it.index == selected }?.displayTitle ?: "Off", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        DropdownMenu(open, { open = false }) {
            if (allowOff) DropdownMenuItem({ Text("Off") }, { onPick(null); open = false }, trailingIcon = { if (selected == null || selected < 0) Icon(Icons.Rounded.Check, null) })
            streams.forEach { s ->
                DropdownMenuItem({ Text(s.displayTitle) }, { onPick(s.index); open = false }, trailingIcon = { if (s.index == selected) Icon(Icons.Rounded.Check, null) })
            }
        }
    }
}

private fun LazyListScope.body(item: Item, seasons: Load<List<Item>>?, refresh: () -> Unit) {
    item.overview?.let { o ->
        item { Text(o, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Sub) }
    }
    if (seasons != null) item { Seasons(item, seasons) }
    val people = item.people
    if (people.isNotEmpty()) item {
        Section("Cast") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(people.take(30), key = { it.id + it.role }) { p ->
                    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(80.dp).clip(CircleShape).background(Raised)) {
                            if (p.imageTag != null) AsyncImage(App.it.api.personImageUrl(p.id, 200), p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        p.role?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    if (item.type == "BoxSet") item {
        val children = rememberLoad("children:${item.id}") { App.it.api.items(parentId = item.id, recursive = false).items }
        ItemRow("In this collection", children.data.orEmpty())
    }
}

@Composable
private fun Seasons(item: Item, seasons: Load<List<Item>>) {
    val app = App.it
    val list = seasons.data.orEmpty()
    val seriesId = item.seriesId ?: item.id
    var selected by rememberSaveable(seriesId) { mutableStateOf(item.seasonId) }
    val season = list.firstOrNull { it.id == selected }
        ?: list.firstOrNull { s -> s.userData.unplayed > 0 && (s.index ?: 0) > 0 } ?: list.firstOrNull()
    val nav = LocalNav.current
    val play = rememberPlay()
    var downloadSeason by remember { mutableStateOf<Item?>(null) }

    Column(Modifier.padding(top = 8.dp)) {
        if (seasons.error != null && list.isEmpty()) ErrorState(seasons.error, seasons.refresh)
        val chips = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(list.size) { list.indexOfFirst { it.id == season?.id }.takeIf { it > 0 }?.let { chips.scrollToItem(it) } }
        LazyRow(state = chips, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { s -> FilterChip(s.id == season?.id, { selected = s.id }, { Text(s.name) }) }
        }
        if (season != null) {
            val eps = rememberLoad("episodes:${season.id}") {
                runCatching { app.api.episodes(seriesId, season.id) }.getOrElse { app.downloads.offlineEpisodes(season.id).ifEmpty { throw it } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${eps.data?.size ?: 0} episodes", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ downloadSeason = season }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download season")
                }
            }
            eps.data?.forEach { ep ->
                EpisodeRow(ep, current = ep.id == item.id, onOpen = { if (ep.id != item.id) nav.go(Route.Details(ep.id)) }) { play(ep, null, null, null) }
            }
            if (eps.data == null && eps.error != null) ErrorState(eps.error, eps.refresh)
        }
    }
    downloadSeason?.let { DownloadDialog(it) { downloadSeason = null } }
}

@Composable
private fun EpisodeRow(ep: Item, current: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (current) Raised else Color.Transparent).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlay)) {
            Picture(ep.thumb, 150.dp, Modifier.fillMaxSize())
            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.align(Alignment.Center).size(36.dp).background(Bg.copy(alpha = .55f), CircleShape).padding(4.dp))
            Badges(ep, Modifier.align(Alignment.TopEnd))
            ProgressLine(ep, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f)) {
            Text("${ep.index ?: ""}. ${ep.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ep.runTimeMs.takeIf { it > 0 }?.let(::formatRuntime) ?: "", style = MaterialTheme.typography.bodySmall)
            ep.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Download a movie, an episode, a season or a whole show at a chosen quality, showing the estimated size. */
@Composable
fun DownloadDialog(item: Item, onDismiss: () -> Unit) {
    val app = App.it
    var quality by remember { mutableStateOf(Quality.of(app.prefs.downloadQuality, Quality.Medium)) }
    var unwatchedOnly by remember { mutableStateOf(true) }
    var keepNext by remember { mutableStateOf(item.isSeries && item.id in app.prefs.smartShows) }
    val group = item.isSeries || item.isSeason
    val episodes = if (group) rememberLoad("dl-eps:${item.id}") {
        if (item.isSeries) app.api.episodes(item.id) else app.api.episodes(item.seriesId ?: "", item.id)
    } else null
    val targets = if (group) episodes?.data.orEmpty().filter { !unwatchedOnly || !it.userData.played } else listOf(item)
    val plan = remember(targets, quality) { if (targets.isEmpty()) null else app.downloads.estimate(targets, quality) }

    AlertDialog(
        onDismiss,
        title = { Text("Download ${if (group) item.name else "“${item.name}”"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Quality.entries.filter { it != Quality.Auto }.forEach { q ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { quality = q }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(quality == q, { quality = q })
                        Text(q.label)
                    }
                }
                if (group) Row(Modifier.clickable { unwatchedOnly = !unwatchedOnly }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(unwatchedOnly, { unwatchedOnly = it })
                    Text("Unwatched only")
                }
                if (item.isSeries) Row(Modifier.clickable { keepNext = !keepNext }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keepNext, { keepNext = it })
                    Text("Keep the next ${app.prefs.smartEpisodes} unwatched episodes downloaded")
                }
                Gap(8.dp)
                when {
                    episodes?.loading == true && episodes.data == null -> Text("Counting episodes…", style = MaterialTheme.typography.bodySmall)
                    plan != null -> {
                        Text(
                            "${targets.size} ${if (targets.size == 1) "item" else "items"} · about ${formatBytes(plan.bytes)}" +
                                (if (plan.original) " (original files)" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("${formatBytes(app.downloads.freeBytes())} free", style = MaterialTheme.typography.bodySmall)
                        plan.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    else -> Text("Nothing to download", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    if (item.isSeries) app.prefs.smartShows = if (keepNext) app.prefs.smartShows + item.id else app.prefs.smartShows - item.id
                    app.downloads.download(if (keepNext) targets.take(app.prefs.smartEpisodes) else targets, quality)
                    app.downloads.resume()
                    onDismiss()
                },
                enabled = plan != null && plan.error == null,
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
