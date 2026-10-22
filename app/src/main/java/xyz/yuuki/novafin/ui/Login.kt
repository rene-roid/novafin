package xyz.yuuki.novafin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import xyz.yuuki.novafin.ServerInfo
import xyz.yuuki.novafin.addressKind
import xyz.yuuki.novafin.normalizeAddress

@Composable
fun LoginScreen() {
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

    fun connect() {
        busy = true
        error = null
        scope.launch {
            val url = normalizeAddress(address)
            // No scheme typed: https first for public domains, http first on the LAN or Tailscale.
            val https = url.replaceFirst("http://", "https://")
            val candidates = when {
                address.trim().startsWith("http", true) -> listOf(url)
                addressKind(url) == AddressKind.Public -> listOf(https, url)
                else -> listOf(url, https)
            }
            // All at once; the first in preference order that answers wins.
            val found = candidates
                .map { c -> async(Dispatchers.IO) { runCatching { c to Api.publicInfo(c) }.getOrNull() } }
                .firstNotNullOfOrNull { it.await() }
            if (found == null) error = "No Jellyfin server answered at $url" else server = found
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
            Text(
                if (server == null) "Connect to your Jellyfin server" else "Sign in to ${server!!.second.name}",
                style = MaterialTheme.typography.bodyMedium, color = Sub, textAlign = TextAlign.Center,
            )
            val s = server
            if (s == null) {
                OutlinedTextField(
                    address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Server address") },
                    placeholder = { Text("192.168.1.10:8096 or jellyfin.example.com") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank() && !busy) connect() }),
                )
                AuroraButton(::connect, Modifier.fillMaxWidth(), enabled = address.isNotBlank() && !busy) { Text("Continue") }
            } else if (quickCode != null) {
                Text("Enter this code in Jellyfin under Settings → Quick Connect", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(quickCode!!, style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, letterSpacing = 8.sp), color = Glow)
                CircularProgressIndicator(Modifier.size(28.dp))
                TextButton({ quickJob?.cancel(); quickCode = null }) { Text("Cancel") }
            } else {
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                AuroraButton(
                    {
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
                    },
                    Modifier.fillMaxWidth(), enabled = user.isNotBlank() && !busy,
                ) { Text("Sign in") }
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
                    Modifier.fillMaxWidth(),
                ) { Text("Use Quick Connect") }
                TextButton({ server = null }) { Text("Change server") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(28.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        }
    }
}
