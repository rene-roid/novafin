package xyz.yuuki.novafin

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.media3.cast.Cast
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.yuuki.novafin.ui.forgetUi
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var it: App
            private set
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
    lateinit var api: Api
    val loggedIn = MutableStateFlow(false)
    val downloads by lazy { Downloads(this) }
    /** Created on first use, on the main thread (Media3 players are main-thread objects). */
    val playback by lazy { Playback(this) }

    private var started = 0
    private var backgroundSince = 0L

    override fun onCreate() {
        super.onCreate()
        it = this
        prefs = Prefs(this)
        // A 401 that comes in right after a switch answers the previous account's request.
        api = Api(prefs, scope) { if (System.currentTimeMillis() - switchedAt > 5_000) signOut() }
        loggedIn.value = prefs.loggedIn
        runCatching { Cast.getSingletonInstance(this).initialize() }
        if (prefs.loggedIn) {
            api.reconnect()
            // Admin status and the server's language preferences can change after sign-in.
            scope.launch { runCatching { api.withFailover { api.loadUserConfig() } } }
        }

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                api.networkChanged(true)
                if (prefs.loggedIn) reconnectAndSync()
            }

            override fun onLost(network: Network) = api.networkChanged(false)
        })

        // Re-race on returning after 5+ minutes; the ticker below covers the time in between.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && prefs.loggedIn) {
                    // Picks up downloads left queued when the app was last closed (allowed while in the foreground).
                    runCatching { androidx.media3.exoplayer.offline.DownloadService.start(this@App, DownloadSvc::class.java) }
                    if (System.currentTimeMillis() - backgroundSince > 5 * 60_000) reconnectAndSync() else downloads.syncPending()
                }
            }

            override fun onActivityStopped(a: Activity) {
                if (--started == 0) {
                    backgroundSince = System.currentTimeMillis()
                    HomeWidget.refresh(this@App)
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // Every 5 minutes while in use, so arriving home moves from Tailscale/public to the LAN.
        scope.launch {
            while (true) {
                delay(5 * 60_000)
                if (prefs.loggedIn && (started > 0 || Playback.active)) api.pickBest()
            }
        }
    }

    fun reconnectAndSync() = scope.launch {
        api.reconnect()
        if (api.pickBest() != null) downloads.syncPending()
    }

    @Volatile private var switchedAt = 0L

    /**
     * Makes [target] the account in use (see [switchAccount]) and drops what was attached to the
     * previous one: playback, addresses and latency, the UI cache and the widget snapshots.
     */
    suspend fun useAccount(target: Account) = withContext(Dispatchers.Main) {
        switchedAt = System.currentTimeMillis()
        if (Playback.active) {
            playback.stop()
            // The stop report gets its address and token on another thread; let it leave for the old server.
            delay(250)
        }
        val (current, others) = switchAccount(prefs.account, prefs.otherAccounts, target)
        // No token goes out until the race has checked the new addresses against the new server id.
        prefs.switching = true
        val online = try {
            prefs.setAccounts(current, others)
            api.accountChanged()
            api.pickBest() != null
        } finally {
            prefs.switching = false
            switchedAt = System.currentTimeMillis()
        }
        forgetUi("")
        HomeWidget.forget(this@App)
        HomeWidget.refresh(this@App)
        downloads.accountChanged()
        if (online) {
            runCatching { api.loadUserConfig() }
            downloads.syncPending()
        }
        loggedIn.value = true
    }

    /** Best effort: asks [a]'s server to forget its token. Its own client and header, and only if that server answers there. */
    fun revoke(a: Account) = scope.launch(Dispatchers.IO) {
        runCatching {
            val base = a.active ?: a.addresses.first().url
            val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            if (Api.publicInfo(base, http).id != a.serverId) return@launch
            http.newCall(
                Request.Builder().url("$base/Sessions/Logout").header("Authorization", Api.authHeader(prefs.deviceId, a.token)).post(ByteArray(0).toRequestBody()).build(),
            ).execute().close()
        }
    }

    /** Forgets the account in use; a saved one takes over, else it's back to the login screen. */
    fun signOut(): Job? {
        val next = prefs.otherAccounts.firstOrNull()
        prefs.logout()
        if (next != null) return scope.launch { useAccount(next) }
        loggedIn.value = false
        HomeWidget.refresh(this)
        return null
    }

    /**
     * Not on an unmetered network. Looks past the VPN: Tailscale over mobile data counts as mobile
     * data even though the VPN itself reports unmetered.
     */
    @Suppress("DEPRECATION")
    val metered: Boolean
        get() = getSystemService(ConnectivityManager::class.java).run {
            allNetworks.none { n ->
                getNetworkCapabilities(n)?.run {
                    hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
        }

    val streamQuality get() = Quality.of(if (metered) prefs.qualityMobile else prefs.qualityWifi)

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .crossfade(150)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(prefs.imageCacheMb * 1024L * 1024).build() }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
        .build()
}
