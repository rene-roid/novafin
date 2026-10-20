package xyz.yuuki.novafin

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import xyz.yuuki.novafin.ui.NovafinTheme
import xyz.yuuki.novafin.ui.PlayerScreen

/**
 * Fullscreen player in its own task, so the app can be browsed while the video sits in
 * picture-in-picture. The player itself lives in [Playback] and outlives this screen.
 */
class PlayerActivity : ComponentActivity() {
    private val playback get() = App.it.playback
    private var controller: ListenableFuture<MediaController>? = null
    val inPip = mutableStateOf(false)
    private var wasInPip = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The phone may sleep while the TV plays.
            if (isPlaying && !playback.casting.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePip()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()
    }

    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = playback.player
            when (intent.getIntExtra("action", 0)) {
                1 -> p.seekBack()
                2 -> if (p.isPlaying) p.pause() else p.play()
                3 -> p.seekForward()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.getInsetsController(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Binding a controller starts the media session service: notification, lock screen, headset buttons.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        playback.player.addListener(listener)
        ContextCompat.registerReceiver(this, pipActions, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePip()
        // Casting turns the player into a remote: any orientation, system bars visible, screen may sleep.
        lifecycleScope.launch {
            playback.casting.collect { casting ->
                requestedOrientation = if (casting) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.getInsetsController(window, window.decorView).run {
                    if (casting) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
                }
                if (casting) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updatePip()
            }
        }
        setContent {
            NovafinTheme {
                PlayerScreen(this, inPip.value, onClose = {
                    // Leaving the remote keeps the TV playing; leaving local playback stops it.
                    if (!playback.casting.value) playback.stop()
                    finish()
                })
            }
        }
    }

    var rotationLocked = false
        private set

    fun toggleRotationLock() {
        rotationLocked = !rotationLocked
        requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** -1 follows the system brightness. */
    fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    val brightness get() = window.attributes.screenBrightness

    private fun pipParams(): PictureInPictureParams {
        val size = playback.exo.videoSize
        val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height).coerceRatio() else Rational(16, 9)
        fun action(id: Int, icon: Int, title: String) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, id, Intent(PIP_ACTION).setPackage(packageName).putExtra("action", id), PendingIntent.FLAG_IMMUTABLE),
        )
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(
            listOf(
                action(1, androidx.media3.session.R.drawable.media3_icon_skip_back_10, "Back 10 seconds"),
                if (playback.player.isPlaying) action(2, androidx.media3.session.R.drawable.media3_icon_pause, "Pause")
                else action(2, androidx.media3.session.R.drawable.media3_icon_play, "Play"),
                action(3, androidx.media3.session.R.drawable.media3_icon_skip_forward_10, "Forward 10 seconds"),
            ),
        )
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(App.it.prefs.autoPip && playback.player.isPlaying && !playback.casting.value).setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** PiP accepts ratios between 1:2.39 and 2.39:1. */
    private fun Rational.coerceRatio(): Rational = when {
        toFloat() > 2.39f -> Rational(239, 100)
        toFloat() < 1 / 2.39f -> Rational(100, 239)
        else -> this
    }

    private fun updatePip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    fun enterPip() {
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < 31 && App.it.prefs.autoPip && playback.player.isPlaying && !playback.casting.value) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) wasInPip = true
    }

    override fun onStart() {
        super.onStart()
        wasInPip = false
        // Back from background audio: show the picture again.
        playback.exo.trackSelectionParameters = playback.exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }

    override fun onStop() {
        super.onStop()
        when {
            // Casting: the TV keeps playing whatever happens to this screen (back, home, screen off).
            playback.casting.value -> Unit
            // Back out of the player, or the PiP window was closed.
            wasInPip || isFinishing -> {
                playback.stop()
                finish()
            }
            App.it.prefs.backgroundAudio && playback.player.isPlaying ->
                playback.exo.trackSelectionParameters = playback.exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            else -> playback.player.pause()
        }
    }

    override fun onDestroy() {
        playback.player.removeListener(listener)
        unregisterReceiver(pipActions)
        controller?.let(MediaController::releaseFuture)
        super.onDestroy()
    }

    companion object {
        private const val PIP_ACTION = "xyz.yuuki.novafin.PIP"
    }
}
