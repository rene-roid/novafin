package xyz.yuuki.novafin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item

data class HomeData(val resume: List<Item>, val nextUp: List<Item>, val views: List<Item>, val latest: List<Pair<Item, List<Item>>>)

/** Page title with the cast button, and on phones the account button that opens settings. */
@Composable
fun Header(title: String, modifier: Modifier = Modifier, inGrid: Boolean = false) {
    val nav = LocalNav.current
    // Grids already pad their content by 16 dp.
    Row(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = if (inGrid) 0.dp else 16.dp, end = if (inGrid) 0.dp else 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        MediaRouteButton(Modifier.size(48.dp))
        if (!isTablet()) Box(
            Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).background(Violet).clickable { nav.go(Route.Settings) },
            contentAlignment = Alignment.Center,
        ) {
            Text(App.it.prefs.userName?.take(1)?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
            // Users without a Jellyfin picture 404 here, leaving the initial visible.
            AsyncImage(App.it.api.userImageUrl(108), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun HomeScreen() {
    val api = App.it.api
    val nav = LocalNav.current
    val load = rememberLoad("home") {
        coroutineScope {
            val resume = async { api.resume() }
            val next = async { api.nextUp() }
            val views = api.views()
            val latest = views.map { v -> async { v to runCatching { api.latest(v.id) }.getOrDefault(emptyList()) } }
            HomeData(resume.await(), next.await(), views, latest.awaitAll())
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(nav.retap) { if (nav.retap > 0) list.animateScrollToItem(0) }
    val tablet = isTablet()
    val play = rememberPlay()

    PullToRefreshBox(load.loading && load.data != null, load.refresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = 24.dp)) {
            val d = load.data
            val hero = d?.resume?.firstOrNull() ?: d?.nextUp?.firstOrNull()
            if (tablet && hero != null) item { Hero(hero) { play(hero, null, null, null) } }
            else item { Header("Home") }
            when {
                d != null -> {
                    item { ItemRow("Continue watching", d.resume, thumbs = true) }
                    item { ItemRow("Next up", d.nextUp, thumbs = true) }
                    for ((view, items) in d.latest) item(key = "latest-${view.id}") { ItemRow("Latest in ${view.name}", items) }
                    item { Libraries(d.views) }
                }
                load.error != null -> item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        ErrorState(load.error, load.refresh)
                        AuroraButton({ nav.tab(Route.Downloads) }) { Text("Watch downloads") }
                    }
                }
                else -> item { LoadingRows() }
            }
        }
    }
}

/** Tablet home: a large backdrop for what to resume. */
@Composable
private fun Hero(item: Item, onPlay: () -> Unit) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxWidth().heightIn(min = 380.dp).height(460.dp).clickable { nav.open(item) }) {
        Picture(item.backdrop ?: item.thumb, 1600.dp, Modifier.fillMaxSize().scrim())
        TopScrim()
        Header("", Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart).padding(32.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val logo = item.logo
            if (logo != null) AsyncImage(imageModel(logo, 360.dp), item.seriesName ?: item.name, Modifier.height(96.dp).widthIn(max = 360.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
            else Text(item.seriesName ?: item.name, style = MaterialTheme.typography.displaySmall)
            Text(listOfNotNull(item.episodeLabel, item.name.takeIf { item.isEpisode }).joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = Sub)
            item.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Sub, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            AuroraButton(onPlay) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (item.userData.positionMs > 0) "Resume ${formatTime(item.userData.positionMs)}" else "Play")
            }
            ProgressLine(item, Modifier.width(240.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Libraries(views: List<Item>) {
    val nav = LocalNav.current
    Section("Libraries") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(views, key = { it.id }) { v ->
                Column(Modifier.width(220.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    LibraryState.selected = v.id
                    nav.tab(Route.Library)
                }) {
                    Picture(v.poster, 220.dp, Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(8.dp)))
                    Text(v.name, Modifier.padding(top = 6.dp, start = 2.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
