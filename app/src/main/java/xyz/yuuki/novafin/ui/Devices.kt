package xyz.yuuki.novafin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Session
import java.time.Duration
import java.time.Instant

@Composable
fun DevicesScreen() {
    val api = App.it.api
    val admin = App.it.prefs.isAdmin
    val me = App.it.prefs.userName
    val load = rememberLoad("sessions") { api.sessions(600) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10_000)
                load.refresh()
            }
        }
    }
    val play = rememberPlay()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf<String?>(null) }
    fun command(s: Session, c: String) = scope.launch {
        failed = null
        try { api.sessionCommand(s.id, c); load.refresh() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed = "Couldn't reach ${s.deviceName}" }
    }

    PullToRefreshBox(load.loading && load.data != null, load.refresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { PageTop("Your devices") }
            val list = load.data
            when {
                list == null && load.error != null -> item { ErrorState(load.error, load.refresh) }
                list == null -> item { LoadingRows() }
                else -> {
                    failed?.let { m -> item { Text(m, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                    if (list.isEmpty()) item { Empty(if (admin) "No other sessions are active." else "No other devices of yours are active right now.") }
                    val (mine, others) = if (admin) list.partition { it.userName == me } else list to emptyList()
                    fun group(title: String?, l: List<Session>) {
                        if (l.isEmpty()) return
                        if (title != null) item { Text(title, Modifier.widthIn(max = 720.dp).padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Violet) }
                        items(l, key = { it.id }) { s -> SessionRow(s, admin && title == "Other people", { play(s.nowPlaying!!, s.positionMs, null, null) }, { command(s, it) }) }
                    }
                    group(if (admin) "Your devices" else null, mine)
                    group("Other people", others)
                    if (!admin) item { Text("Jellyfin only shows your own devices.", Modifier.widthIn(max = 720.dp).padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Faint) }
                }
            }
        }
    }
}

private fun ago(t: Instant?): String {
    val m = t?.let { Duration.between(it, Instant.now()).toMinutes() } ?: return ""
    return if (m < 1) "now" else "active $m min ago"
}

@Composable
private fun SessionRow(s: Session, showUser: Boolean, onContinue: () -> Unit, onCommand: (String) -> Unit) {
    val item = s.nowPlaying
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(s.deviceName, style = MaterialTheme.typography.bodyLarge)
        Text(listOfNotNull(s.client, if (showUser) s.userName else null, ago(s.lastActivity).ifEmpty { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Sub)
        if (item != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(120.dp)) {
                Picture(item.thumb ?: item.poster, 120.dp, Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(6.dp)))
                if (item.runTimeMs > 0) LinearProgressIndicator({ (s.positionMs.toFloat() / item.runTimeMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp), color = Glow, trackColor = Color.White.copy(alpha = .25f), drawStopIndicator = {}, gapSize = 0.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(item.seriesName ?: item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (item.isEpisode) Text("${item.episodeLabel} · ${item.name}", style = MaterialTheme.typography.bodySmall, color = Sub, maxLines = 1)
                if (s.paused) Text("Paused", style = MaterialTheme.typography.labelMedium, color = Glow)
            }
        }
        if (item != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Someone else's position isn't yours to resume from.
            if (!showUser) TextButton(onContinue) { Text("Continue here") }
            Spacer(Modifier.weight(1f))
            if (s.remoteControl) {
                IconButton({ onCommand(if (s.paused) "Unpause" else "Pause") }) { Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (s.paused) "Resume on ${s.deviceName}" else "Pause on ${s.deviceName}") }
                IconButton({ onCommand("Stop") }) { Icon(Icons.Rounded.Stop, "Stop on ${s.deviceName}") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = .08f))
    }
}
