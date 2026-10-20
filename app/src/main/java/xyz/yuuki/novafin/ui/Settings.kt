package xyz.yuuki.novafin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.yuuki.novafin.Address
import xyz.yuuki.novafin.AddressKind
import xyz.yuuki.novafin.Api
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.BuildConfig
import xyz.yuuki.novafin.Quality
import xyz.yuuki.novafin.addressKind
import xyz.yuuki.novafin.normalizeAddress

private val languages = listOf(
    null to "Not set", "eng" to "English", "jpn" to "Japanese", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian",
    "por" to "Portuguese", "kor" to "Korean", "chi" to "Chinese", "rus" to "Russian", "ara" to "Arabic", "hin" to "Hindi",
)
private val subModes = listOf(null to "Same as server", "Default" to "Default tracks", "Smart" to "When audio is foreign", "Always" to "Always", "OnlyForced" to "Only forced / signs", "None" to "Never")
private val colors = listOf(0xFFFFFFFF.toInt() to "White", 0xFFFFE9D2.toInt() to "Moon", 0xFFFFEB3B.toInt() to "Yellow", 0xFF8FE3FF.toInt() to "Cyan")

@Composable
fun PageTop(title: String) {
    val nav = LocalNav.current
    Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav.stack.size > 1) IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun Group(title: String) = Text(title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Violet)

@Composable
private fun Row2(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        trailing()
    }
}

@Composable
private fun Toggle(title: String, subtitle: String? = null, get: () -> Boolean, set: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(get()) }
    Row2(title, subtitle, { on = !on; set(on) }) { Switch(on, { on = it; set(it) }) }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit) {
    var value by remember { mutableStateOf(get()) }
    var open by remember { mutableStateOf(false) }
    Row2(title, options.firstOrNull { it.first == value }?.second, { open = true })
    if (open) AlertDialog(
        { open = false },
        title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(options) { _, (v, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { value = v; set(v); open = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == v, { value = v; set(v); open = false })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("Close") } },
    )
}

@Composable
private fun SliderRow(title: String, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, get: () -> Float, set: (Float) -> Unit) {
    var v by remember { mutableStateOf(get()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("$title: ${label(v)}", style = MaterialTheme.typography.bodyLarge)
        Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { set(v) })
    }
}

@Composable
fun SettingsScreen() {
    val app = App.it
    val p = app.prefs
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val streamPresets = Quality.entries.map { it to it.label }
    val downloadPresets = Quality.entries.filter { it != Quality.Auto }.map { it to it.label }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { PageTop("Settings") }
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                Group("Account")
                Row2(p.userName ?: "", "${p.serverName ?: "Jellyfin"} · ${app.api.active ?: ""}")
                Row2("Server addresses", "${p.addresses.size} · automatic switching", { nav.go(Route.Addresses) })
                Row2("Sign out", onClick = { confirmLogout = true })

                Group("Playback")
                Toggle("Autoplay next episode", get = { p.autoplayNext }) { p.autoplayNext = it }
                Toggle("Skip intros automatically", "Also recaps, when the server has detected them", { p.autoSkipIntro }) { p.autoSkipIntro = it }
                Toggle("Picture-in-picture when leaving", get = { p.autoPip }) { p.autoPip = it }
                Toggle("Keep audio playing in the background", "When picture-in-picture is off", { p.backgroundAudio }) { p.backgroundAudio = it }
                Toggle("Fill the screen by default", "Crops the edges instead of black bars", { p.zoomFill }) { p.zoomFill = it }
                Toggle("Convert AV1 on the server", "For phones that stutter on AV1", { p.preferTranscodeAv1 }) { p.preferTranscodeAv1 = it }

                Group("Languages")
                Choice("Audio language", languages, { p.audioLang }) { p.audioLang = it }
                Choice("Subtitle language", languages, { p.subLang }) { p.subLang = it }
                Choice("Show subtitles", subModes, { p.subMode }) { p.subMode = it }
                Text(
                    "Changing tracks in a show or movie is remembered for it and wins over these defaults.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                )

                Group("Subtitle style")
                SliderRow("Size", .5f..2f, { "${(it * 100).toInt()} %" }, { p.subSize }) { p.subSize = it }
                Choice("Colour", colors, { p.subColor }) { p.subColor = it }
                Toggle("Background box", get = { p.subBackground }) { p.subBackground = it }
                SliderRow("Position from bottom", 0f..0.3f, { "${(it * 100).toInt()} %" }, { p.subBottom }) { p.subBottom = it }

                Group("Streaming quality")
                Choice("On Wi-Fi", streamPresets, { Quality.of(p.qualityWifi) }) { p.qualityWifi = it.name }
                Choice("On mobile data", streamPresets, { Quality.of(p.qualityMobile) }) { p.qualityMobile = it.name }
                Toggle("Smart original", "Play the original file when it's already smaller than the preset", { p.smartOriginal }) { p.smartOriginal = it }
                Toggle("Stereo downmix", "Converted streams use 2 channels; saves data on phones", { p.downmix }) { p.downmix = it }
                Toggle("Smaller images on mobile data", get = { p.smallImagesOnMobile }) { p.smallImagesOnMobile = it }
                Text(
                    "This server converts video with the CPU in H.264. Turning on hardware acceleration and HEVC encoding in the Jellyfin dashboard makes converted streams and downloads about half the size.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                )

                Group("Downloads")
                Choice("Download quality", downloadPresets, { Quality.of(p.downloadQuality, Quality.Medium) }) { p.downloadQuality = it.name }
                Toggle("Keep all audio languages", "Downloads the original file when the phone can play it", { p.keepBothAudio }) { p.keepBothAudio = it }
                Toggle("Wi-Fi only", get = { p.wifiOnlyDownloads }) { app.downloads.setWifiOnly(it) }
                Choice("Storage limit", listOf(0 to "Unlimited", 10 to "10 GB", 25 to "25 GB", 50 to "50 GB", 100 to "100 GB"), { p.storageLimitGb }) { p.storageLimitGb = it }
                if (app.getExternalFilesDirs(null).size > 1) Toggle(
                    "Save to SD card", "Applies after restarting the app; existing downloads stay where they are", { p.storageSd },
                ) { p.storageSd = it }
                Choice("Delete watched downloads", listOf(-1 to "Never", 0 to "Right away", 1 to "After a day", 7 to "After a week"), { p.autoDeleteDays }) { p.autoDeleteDays = it }
                Choice("Episodes to keep ahead", listOf(1 to "1", 2 to "2", 3 to "3", 5 to "5", 10 to "10"), { p.smartEpisodes }) { p.smartEpisodes = it }
                Row2("Storage used", "${formatBytes(app.downloads.bytesUsed())} of downloads · ${formatBytes(app.downloads.freeBytes())} free")
                Row2("Delete all downloads", onClick = { confirmDeleteAll = true })
                Choice("Image cache", listOf(128 to "128 MB", 256 to "256 MB", 512 to "512 MB", 1024 to "1 GB"), { p.imageCacheMb }) { p.imageCacheMb = it }
                Row2("Clear image cache", onClick = {
                    scope.launch(Dispatchers.IO) {
                        SingletonImageLoader.get(app).diskCache?.clear()
                        SingletonImageLoader.get(app).memoryCache?.clear()
                    }
                })

                Group("About")
                Row2("Novafin ${BuildConfig.VERSION_NAME}", "Jellyfin ${p.serverVersion ?: "?"}")
            }
        }
    }

    if (confirmLogout) AlertDialog(
        { confirmLogout = false },
        title = { Text("Sign out?") },
        text = { Text("Downloads stay on this device.") },
        confirmButton = {
            TextButton({
                confirmLogout = false
                app.playback.stop()
                scope.launch {
                    app.api.logout()
                    app.signOut()
                }
            }) { Text("Sign out") }
        },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancel") } },
    )
    if (confirmDeleteAll) AlertDialog(
        { confirmDeleteAll = false },
        title = { Text("Delete all downloads?") },
        confirmButton = { TextButton({ app.downloads.removeAll(); confirmDeleteAll = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDeleteAll = false }) { Text("Cancel") } },
    )
}

/** The server's addresses: live status, order, pinning and which one a Chromecast can use. */
@Composable
fun AddressesScreen() {
    val app = App.it
    val p = app.prefs
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(p.addresses) }
    var pinned by remember { mutableStateOf(p.pinned) }
    val latency by app.api.latency.collectAsStateWithLifecycle()
    val active by app.api.activeFlow.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<String?>(null) }

    fun save(l: List<Address>) {
        list = l
        p.addresses = l
        scope.launch { app.api.pickBest() }
    }

    LaunchedEffect(Unit) {
        app.api.pickBest()
        suggestion = withContext(Dispatchers.IO) { runCatching { Api.publicInfo(active ?: "").localAddress?.let(::normalizeAddress) }.getOrNull() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { PageTop("Server addresses") }
        item {
            Text(
                "Novafin checks every address and uses the best one that answers, earlier ones first when they're nearly as fast. It re-checks when the network changes, when a request fails and every few minutes.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row2("Automatic", if (pinned == null) "Using the best address" else "Pinned to one address", { pinned = null; p.pinned = null; scope.launch { app.api.pickBest() } }) {
                RadioButton(pinned == null, { pinned = null; p.pinned = null; scope.launch { app.api.pickBest() } })
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
        }
        itemsIndexed(list, key = { _, a -> a.url }) { i, a ->
            val ms = latency[a.url]
            val known = a.url in latency
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(when { !known -> Faint; ms == null -> Dusk; else -> Color(0xFF7EE0A1) }))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(a.label + if (a.url == active) " · in use" else "", style = MaterialTheme.typography.titleSmall, color = if (a.url == active) Moon else Color.White)
                    Text(a.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when { !known -> "Checking…"; ms == null -> "Not reachable"; else -> "$ms ms" } + if (a.tv) " · reachable from TV" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton({ save(list.toMutableList().also { it[i] = a.copy(tv = !a.tv) }) }) { Icon(Icons.Rounded.Cast, "Reachable from TV", tint = if (a.tv) Violet else Faint) }
                IconButton({
                    pinned = if (pinned == a.url) null else a.url
                    p.pinned = pinned
                    scope.launch { app.api.pickBest() }
                }) { Icon(if (pinned == a.url) Icons.Rounded.PushPin else Icons.Outlined.PushPin, "Pin", tint = if (pinned == a.url) Moon else Faint) }
                if (i > 0) IconButton({ save(list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                if (i < list.lastIndex) IconButton({ save(list.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                if (list.size > 1) IconButton({ save(list - a) }) { Icon(Icons.Rounded.Delete, "Remove") }
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestion?.takeIf { s -> list.none { it.url == s } }?.let { s ->
                    TextButton({ save(insertByKind(list, Address(s, addressKind(s).name))) }) { Text("Add the server's local address $s") }
                }
                Button({ adding = true }) { Text("Add address") }
            }
        }
    }
    if (adding) AddAddressDialog({ adding = false }) { a ->
        save(insertByKind(list, a))
        adding = false
    }
}

/** New addresses go in LAN → Tailscale → public order; the user can reorder afterwards. */
private fun insertByKind(list: List<Address>, a: Address): List<Address> {
    val at = list.indexOfFirst { addressKind(it.url) > addressKind(a.url) }.takeIf { it >= 0 } ?: list.size
    return list.toMutableList().apply { add(at, a) }
}

@Composable
private fun AddAddressDialog(onDismiss: () -> Unit, onAdd: (Address) -> Unit) {
    val app = App.it
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf<String?>(null) }
    val normalized = normalizeAddress(url)
    val insecure = url.isNotBlank() && normalized.startsWith("http://") && addressKind(normalized) == AddressKind.Public

    AlertDialog(
        onDismiss,
        title = { Text("Add address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it; error = null }, label = { Text("Address") }, placeholder = { Text("https://jellyfin.example.com") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, placeholder = { Text(addressKind(normalized).name) }, singleLine = true)
                if (insecure) Text("Plain http over the internet sends your login unencrypted. Use https for public addresses.", color = Dusk, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                {
                    val address = Address(normalized, label.ifBlank { addressKind(normalized).name })
                    if (unreachable == normalized) {
                        // Nothing gets sent there until a check sees our server's id answering on it.
                        onAdd(address)
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val info = withContext(Dispatchers.IO) { runCatching { Api.publicInfo(normalized) }.getOrNull() }
                        when {
                            app.prefs.addresses.any { it.url == normalized } -> error = "Already in the list"
                            // Only the same server ever gets our token.
                            info != null && info.id != app.prefs.serverId -> error = "That's a different Jellyfin server (${info.name})"
                            info == null -> {
                                error = "Nothing answered there right now (Tailscale off, or away from home?). You can add it anyway; it's only used once it answers as your server."
                                unreachable = normalized
                            }
                            else -> onAdd(address)
                        }
                        busy = false
                    }
                },
                enabled = url.isNotBlank() && !busy,
            ) { Text(if (busy) "Checking…" else if (unreachable == normalized) "Add anyway" else "Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
