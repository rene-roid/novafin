package xyz.yuuki.novafin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Item

/** Titles across every library for one genre or one person. */
@Composable
fun BrowseScreen(route: Route.Browse) {
    val api = App.it.api
    val nav = LocalNav.current
    val items = remember(route) { mutableStateOf(listOf<Item>()) }
    var total by remember(route) { mutableStateOf(-1) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var retry by remember(route) { mutableStateOf(0) }
    val person = route.personId?.let { id -> rememberLoad("person:$id") { api.item(id) }.data }
    val grid = rememberLazyGridState()

    LaunchedEffect(route, retry) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            val have = items.value.size
            if ((total < 0 || have < total) && last >= have - 20) {
                try {
                    val page = api.withFailover {
                        if (route.personId != null) api.items(types = "Movie,Series", sort = "PremiereDate,Descending", start = have, extra = listOf("personIds" to route.personId))
                        else api.items(types = "Movie,Series", sort = "CommunityRating,Descending", start = have, extra = listOf("genres" to route.genre))
                    }
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
                PageTop(route.title)
                if (route.personId != null) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(Raised)) {
                        AsyncImage(api.personImageUrl(route.personId, 240), route.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    person?.overview?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Sub, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                }
                if (total >= 0) Text(if (total == 1) "1 title" else "$total titles", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(items.value, key = { it.id }) { item -> PosterCard(item, if (tablet) 160.dp else 100.dp, Modifier.fillMaxWidth()) { nav.open(item) } }
        if (total < 0 && error == null) item(span = { GridItemSpan(maxLineSpan) }) { LoadingRows() }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error!!, { error = null; retry++ }) }
        if (total == 0) item(span = { GridItemSpan(maxLineSpan) }) { Empty("Nothing here") }
    }
}
