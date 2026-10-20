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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
        api = Api(prefs, scope) { signOut() }
        loggedIn.value = prefs.loggedIn
        runCatching { Cast.getSingletonInstance(this).initialize() }
        if (prefs.loggedIn) api.reconnect()

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
                if (--started == 0) backgroundSince = System.currentTimeMillis()
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

    fun signOut() {
        prefs.logout()
        loggedIn.value = false
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
