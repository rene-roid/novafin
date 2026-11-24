package xyz.yuuki.novafin.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.GroupInfo
import xyz.yuuki.novafin.PlayerActivity
import xyz.yuuki.novafin.SyncState

/** Watch together (Jellyfin SyncPlay): start or join a group, see who is in it. [itemId] is what a new group starts with. */
@Composable
fun WatchTogetherScreen(itemId: String?) {
    val sync = App.it.playback.syncPlay
    val state by sync.state.collectAsStateWithLifecycle()
    val error by sync.error.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    fun openPlayer() {
        sync.follow()
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }
    // Set by Start and Join: the player opens once the server says what the group is watching.
    var wantPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(state, wantPlayer) {
        if (wantPlayer && (state as? SyncState.InGroup)?.itemId != null) {
            wantPlayer = false
            openPlayer()
        }
    }
    LaunchedEffect(error) { if (error != null) wantPlayer = false }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { PageTop("Watch together") }
        error?.let { e -> item { Text(e, Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
        when (val s = state) {
            SyncState.Joining, SyncState.Leaving -> item { Busy(if (s == SyncState.Joining) "Joining…" else "Leaving…") }
            is SyncState.InGroup -> item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge)
                    Text(groupStateLabel(s.state), style = MaterialTheme.typography.bodyMedium, color = Glow)
                    Text("In this group", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Violet)
                    for (p in s.participants) Text(p, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (s.itemId == null) "Nothing is playing yet. Play anything from the library and it starts for everyone in the group."
                        else "Play, pause and seek apply to everyone. Others join from this screen, or from the SyncPlay menu of Jellyfin's own apps.",
                        Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = Sub,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuroraButton({ openPlayer() }, enabled = s.itemId != null) { Text("Open player") }
                        OutlinedButton({ wantPlayer = false; sync.leave() }) { Text("Leave") }
                    }
                }
            }
            SyncState.NotInGroup -> {
                item {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            "Watch the same thing at the same time, with play, pause and seek shared by everyone. Each person needs an account on this server " +
                                "with SyncPlay allowed, and must be able to reach the server. There are no invite links: others join from this screen, " +
                                "or from the SyncPlay menu of Jellyfin's own apps.",
                            style = MaterialTheme.typography.bodyMedium, color = Sub,
                        )
                        AuroraButton({ sync.create("${App.it.prefs.userName ?: "My"}'s group", itemId); wantPlayer = itemId != null }, Modifier.padding(vertical = 16.dp)) { Text("Start a group") }
                        Text("Open groups", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Violet)
                    }
                }
                groups { sync.join(it.id); wantPlayer = true }
            }
        }
    }
}

@Composable
private fun Busy(label: String) = Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(24.dp), color = Glow, strokeWidth = 2.dp)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Sub)
}

/** Groups live only in the server's memory and come and go, so the list refreshes while it shows. */
private fun androidx.compose.foundation.lazy.LazyListScope.groups(onJoin: (GroupInfo) -> Unit) = item {
    val load = rememberLoad("syncplay") { App.it.playback.syncPlay.groups() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(5_000)
                load.refresh()
            }
        }
    }
    val list = load.data
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        when {
            load.error != null -> ErrorState(load.error, load.refresh)
            list == null -> LoadingRows()
            list.isEmpty() -> Text("No one is watching together right now.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Faint)
            else -> for (g in list) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${g.participants.joinToString()} · ${groupStateLabel(g.state)}", style = MaterialTheme.typography.bodySmall, color = Sub)
                }
                FilledTonalButton({ onJoin(g) }) { Text("Join") }
            }
        }
    }
}
