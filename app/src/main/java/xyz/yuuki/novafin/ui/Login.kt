package xyz.yuuki.novafin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import xyz.yuuki.novafin.Address
import xyz.yuuki.novafin.AddressKind
import xyz.yuuki.novafin.Api
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.R
import xyz.yuuki.novafin.DiscoveredServer
import xyz.yuuki.novafin.ServerInfo
import xyz.yuuki.novafin.addressKind
import xyz.yuuki.novafin.addressProblem
import xyz.yuuki.novafin.connectError
import xyz.yuuki.novafin.discoverServers
import xyz.yuuki.novafin.isOldServer
import xyz.yuuki.novafin.probeServer
import xyz.yuuki.novafin.NotJellyfinException
import xyz.yuuki.novafin.normalizeAddress

@Composable
fun LoginScreen(adding: Boolean = false) {
    val app = App.it
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var server by remember { mutableStateOf<Pair<String, ServerInfo>?>(null) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quickCode by remember { mutableStateOf<String?>(null) }
    var quickJob by remember { mutableStateOf<Job?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf(0) }
    LaunchedEffect(server == null, searchKey) {
        if (server != null) return@LaunchedEffect
        searching = true
        found = discoverServers()
        searching = false
    }

    fun finish(url: String, info: ServerInfo, login: Api.Login) = scope.launch {
        val p = app.prefs
        p.serverId = info.id
        p.serverName = info.name
        p.serverVersion = info.version
        p.token = login.token
        p.userId = login.userId
        p.userName = login.userName
        p.addresses = listOf(Address(url, addressKind(url).name))
        p.active = url
        // The server's own LAN address, when it is a different way in and really the same server.
        // Checked in the background: away from home it would only hold up signing in until it times out.
        info.localAddress?.let(::normalizeAddress)?.takeIf { it != url }?.let { local ->
            app.scope.launch(Dispatchers.IO) {
                if (runCatching { Api.publicInfo(local).id == info.id }.getOrDefault(false)) {
                    p.addresses = listOf(Address(local, addressKind(local).name)) + p.addresses
                    app.api.pickBest()
                }
            }
        }
        app.api.reconnect()
        runCatching { app.api.loadUserConfig() }
        app.loggedIn.value = true
    }

    fun connect(raw: String = address) {
        busy = true
        error = null
        scope.launch {
            val url = normalizeAddress(raw)
            // No scheme typed: https first for public domains, http first on the LAN or Tailscale.
            val https = url.replaceFirst("http://", "https://")
            val candidates = when {
                raw.trim().startsWith("http", true) -> listOf(url)
                addressKind(url) == AddressKind.Public -> listOf(https, url)
                else -> listOf(url, https)
            }
            // All at once; the first in preference order that answers wins.
            val tries = candidates.map { c -> async(Dispatchers.IO) { runCatching { c to probeServer(c) } } }
            val results = tries.map { it.await() }
            val ok = results.firstNotNullOfOrNull { it.getOrNull() }
            if (ok != null) server = ok else {
                // Keep the most informative failure: a non-Jellyfin answer beats TLS, which beats timeouts and refusals.
                fun rank(e: Throwable) = when (e) {
                    is NotJellyfinException -> 0
                    is javax.net.ssl.SSLException -> 1
                    is java.net.ConnectException -> 3
                    is java.io.InterruptedIOException -> 2
                    else -> 4
                }
                val i = results.indices.minBy { rank(results[it].exceptionOrNull()!!) }
                error = connectError(results[i].exceptionOrNull()!!, candidates[i])
            }
            busy = false
        }
    }

    fun signIn() {
        val s = server ?: return
        if (user.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                finish(s.first, s.second, app.api.login(s.first, user.trim(), password))
            } catch (e: Exception) {
                error = e.message ?: "Couldn't sign in"
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.padding(top = 32.dp).size(112.dp).clip(RoundedCornerShape(28.dp)))
            Text("Novafin", style = MaterialTheme.typography.displaySmall)
            val s = server
            if (s == null) {
                Text("Connect to your Jellyfin server", style = MaterialTheme.typography.bodyMedium, color = Sub, textAlign = TextAlign.Center)
                val problem = addressProblem(address)
                OutlinedTextField(
                    address, { address = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Server address") },
                    placeholder = { Text("192.168.1.10:8096 or jellyfin.example.com") }, singleLine = true,
                    isError = problem != null, supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank() && problem == null && !busy) connect() }),
                )
                AuroraButton({ connect() }, Modifier.fillMaxWidth(), enabled = address.isNotBlank() && problem == null && !busy) { Text(if (busy) "Connecting…" else "Continue") }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Servers on this network", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton({ searchKey++ }, enabled = !searching && !busy) { Text("Search again") }
                }
                if (searching) Text("Looking for servers…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                else if (found.isEmpty()) Text("None found. Type the address instead.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                else found.forEach { d ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) { address = d.address; connect(d.address) }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(d.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (quickCode != null) {
                Text("Enter this code on a device that's already signed in: Settings → Quick Connect.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(quickCode!!, style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, letterSpacing = 8.sp), color = Glow)
                Text("An admin can enable Quick Connect in the dashboard.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                CircularProgressIndicator(Modifier.size(28.dp))
                TextButton({ quickJob?.cancel(); quickCode = null }) { Text("Cancel") }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.second.name, style = MaterialTheme.typography.titleMedium)
                        Text("Jellyfin ${s.second.version} · ${s.first}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ server = null; error = null }) { Text("Change") }
                }
                if (isOldServer(s.second.version)) Text(
                    "Novafin is built for Jellyfin 10.11. Some features may not work on ${s.second.version}.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }, label = { Text("Username") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.Password }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { signIn() }),
                )
                AuroraButton(::signIn, Modifier.fillMaxWidth(), enabled = user.isNotBlank() && !busy) { Text(if (busy) "Signing in…" else "Sign in") }
                OutlinedButton(
                    {
                        error = null
                        quickJob = scope.launch {
                            try {
                                val (code, secret) = app.api.quickConnectStart(s.first)
                                quickCode = code
                                finish(s.first, s.second, app.api.quickConnectWait(s.first, secret))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message
                                quickCode = null
                            }
                        }
                    },
                    Modifier.fillMaxWidth(), enabled = !busy,
                ) { Text("Use Quick Connect") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        }
    }
}
