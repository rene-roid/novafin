package xyz.yuuki.novafin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.DownloadInfo
import xyz.yuuki.novafin.Item

@Composable
fun DownloadsScreen() {
    val dl = App.it.downloads
    val items by dl.items.collectAsStateWithLifecycle()
    val states by dl.states.collectAsStateWithLifecycle()
    val play = rememberPlay()
    var paused by remember { mutableStateOf(false) }
    val (episodes, movies) = items.values.partition { it.isEpisode }
    val shows = episodes.groupBy { it.seriesName ?: "" }.toSortedMap()
    val active = states.values.count { !it.done && it.state != Download.STATE_FAILED }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Downloads") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${formatBytes(dl.bytesUsed())} used · ${formatBytes(dl.freeBytes())} free", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (active > 0) TextButton({
                    paused = !paused
                    if (paused) dl.pause() else dl.resume()
                }) { Text(if (paused) "Resume all" else "Pause all") }
            }
        }
        if (items.isEmpty()) item { Empty("Downloaded movies and episodes show up here and play without a connection.") }
        if (movies.isNotEmpty()) item { Text("Movies", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleLarge) }
        items(movies.sortedBy { it.name }, key = { it.id }) { DownloadRow(it, states[it.id], { play(it, null, null, null) }) { dl.remove(it.id) } }
        for ((show, eps) in shows) {
            item(key = "show-$show") { Text(show, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleLarge) }
            items(eps.sortedWith(compareBy({ it.parentIndex ?: 0 }, { it.index ?: 0 })), key = { it.id }) {
                DownloadRow(it, states[it.id], { play(it, null, null, null) }) { dl.remove(it.id) }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: Item, state: DownloadInfo?, onPlay: () -> Unit, onDelete: () -> Unit) {
    val done = state?.done == true
    Row(
        Modifier.fillMaxWidth().clickable(enabled = done, onClick = onPlay).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp))) {
            Picture(item.thumb ?: item.poster, 128.dp, Modifier.fillMaxSize())
            if (done) Icon(Icons.Rounded.PlayArrow, null, Modifier.align(Alignment.Center))
            ProgressLine(item, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f)) {
            Text(if (item.isEpisode) "${item.episodeLabel} · ${item.name}" else item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dl = item.json.optJSONObject("Download")
            val what = if (dl?.optBoolean("original") == true) "Original" else xyz.yuuki.novafin.Quality.of(dl?.optString("quality")).label
            Text(
                when {
                    state == null -> "Waiting"
                    done -> "$what · ${formatBytes(state.bytes)}"
                    state.state == Download.STATE_FAILED -> "Failed, tap delete and try again"
                    state.state == Download.STATE_QUEUED -> "Queued"
                    state.state == Download.STATE_STOPPED -> "Paused"
                    else -> "$what · ${state.percent.toInt().coerceAtLeast(0)} % · ${formatBytes(state.bytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state != null && !done && state.state == Download.STATE_DOWNLOADING) {
                LinearProgressIndicator({ state.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Moon)
            }
        }
        IconButton(onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}
