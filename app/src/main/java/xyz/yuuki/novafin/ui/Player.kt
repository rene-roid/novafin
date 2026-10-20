package xyz.yuuki.novafin.ui

import android.graphics.Typeface
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Method
import xyz.yuuki.novafin.NowPlaying
import xyz.yuuki.novafin.PlayerActivity
import xyz.yuuki.novafin.Quality
import xyz.yuuki.novafin.trickplay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(activity: PlayerActivity, inPip: Boolean, onClose: () -> Unit) {
    val playback = App.it.playback
    val player = playback.player
    val prefs = App.it.prefs
    val now by playback.now.collectAsStateWithLifecycle()
    val loading by playback.loading.collectAsStateWithLifecycle()
    val error by playback.error.collectAsStateWithLifecycle()
    val casting by playback.casting.collectAsStateWithLifecycle()

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: now?.item?.runTimeMs ?: 0
            buffered = player.bufferedPosition
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(250)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var interacted by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(prefs.zoomFill) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var gestureLabel by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    // Auto-hide after 3 s of no interaction while playing.
    LaunchedEffect(controls, interacted, playing, sheet) {
        if (controls && playing && sheet == null) {
            delay(3_000)
            controls = false
        }
    }
    LaunchedEffect(gestureLabel) { if (gestureLabel != null) { delay(900); gestureLabel = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val n = now
        if (casting) CastingBackdrop(n) else {
            ContentFrame(playback.exo, Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
            Subtitles(inPip)
        }

        if (!inPip) {
            Gestures(
                activity, locked,
                onTap = { controls = !controls; interacted++ },
                onSeekPreview = { seekPreview = it; if (it != null) { controls = true; interacted++ } },
                onLabel = { gestureLabel = it },
                onPinch = { zoomIn -> fill = zoomIn; prefs.zoomFill = zoomIn },
            )
        }

        if (loading || buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Moon)

        gestureLabel?.let { (icon, text) ->
            Row(Modifier.align(Alignment.Center).background(Bg.copy(alpha = .75f), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.padding(end = 8.dp)) }
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (!inPip && n != null) {
            AnimatedVisibility(controls && !locked, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    activity, n, position, duration, buffered, playing, seekPreview,
                    onSeek = { seekPreview = it; interacted++ },
                    onSeekDone = { seekPreview?.let(player::seekTo); seekPreview = null },
                    onClose = onClose, onSheet = { sheet = it }, onLock = { locked = true }, onInteract = { interacted++ },
                    fill = fill, onFill = { fill = !fill; prefs.zoomFill = fill },
                )
            }
            if (locked) IconButton(
                { locked = false; controls = true },
                Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).background(Bg.copy(alpha = .6f), CircleShape),
            ) { Icon(Icons.Rounded.Lock, "Unlock") }

            SegmentButton(n, position, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp))
            UpNext(n, position, duration, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp))
            n.note?.let { Note(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) }
        }

        error?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Bg.copy(alpha = .9f), RoundedCornerShape(16.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClose) { Text("Close") }
                    Button({ n?.let { playback.play(it.item, position) } }) { Text("Retry") }
                }
            }
        }
    }

    when (sheet) {
        "tracks" -> now?.let { TracksSheet(it) { sheet = null } }
        "settings" -> now?.let { SettingsSheet(it) { sheet = null } }
    }
}

@Composable
private fun CastingBackdrop(n: NowPlaying?) {
    Box(Modifier.fillMaxSize()) {
        Picture(n?.item?.backdrop ?: n?.item?.thumb, 1280.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Bg.copy(alpha = .75f)))
        Text("Playing on your TV", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall, color = Moon)
    }
}

/** Text and image (PGS) subtitles, styled from settings; ASS keeps its own styling. */
@Composable
private fun Subtitles(inPip: Boolean) {
    val playback = App.it.playback
    val p = App.it.prefs
    var cues by remember { mutableStateOf(CueGroup.EMPTY_TIME_ZERO) }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup
            }
        }
        playback.exo.addListener(l)
        onDispose { playback.exo.removeListener(l) }
    }
    AndroidView(
        { SubtitleView(it) },
        Modifier.fillMaxSize(),
        update = { v ->
            v.setStyle(
                CaptionStyleCompat(
                    p.subColor, if (p.subBackground) 0xAA000000.toInt() else 0, 0,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
                ),
            )
            v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * p.subSize * if (inPip) 1.4f else 1f)
            v.setBottomPaddingFraction(p.subBottom)
            v.setApplyEmbeddedStyles(true)
            v.setCues(cues.cues)
        },
    )
}

/**
 * Double-tap the sides to skip 10 s (taps add up), swipe up/down on the left for brightness and on
 * the right for volume, swipe sideways to scrub, pinch to fill or fit.
 */
@Composable
private fun Gestures(
    activity: PlayerActivity,
    locked: Boolean,
    onTap: () -> Unit,
    onSeekPreview: (Long?) -> Unit,
    onLabel: (Pair<ImageVector?, String>?) -> Unit,
    onPinch: (Boolean) -> Unit,
) {
    val player = App.it.playback.player
    val audio = remember { activity.getSystemService(AudioManager::class.java) }
    var pinching by remember { mutableStateOf(false) }
    var skipTotal by remember { mutableIntStateOf(0) }
    var skipAt by remember { mutableLongStateOf(0L) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val forward = o.x > width / 2
                            val t = System.currentTimeMillis()
                            skipTotal = if (t - skipAt < 1_500 && (skipTotal > 0) == forward) skipTotal + (if (forward) 10 else -10) else if (forward) 10 else -10
                            skipAt = t
                            player.seekTo((player.currentPosition + if (forward) 10_000 else -10_000).coerceAtLeast(0))
                            onLabel((if (forward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10) to "${if (skipTotal > 0) "+" else ""}$skipTotal s")
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.size >= 2) {
                                pinching = true
                                zoom *= e.calculateZoom()
                            }
                        } while (e.changes.any { it.pressed })
                        if (pinching && abs(zoom - 1f) > .15f) onPinch(zoom > 1f)
                        pinching = false
                    }
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var axis = 0 // 1 horizontal, 2 vertical
                    var startX = 0f
                    var total = Offset.Zero
                    var startPos = 0L
                    var startLevel = 0f
                    detectDragGestures(
                        onDragStart = { o ->
                            axis = 0
                            startX = o.x
                            total = Offset.Zero
                            startPos = player.currentPosition
                        },
                        onDragEnd = {
                            if (axis == 1) {
                                onSeekPreview(null)
                                player.seekTo((startPos + (total.x / width * 120_000).toLong()).coerceAtLeast(0))
                            }
                        },
                        onDragCancel = { if (axis == 1) onSeekPreview(null) },
                    ) { _, drag ->
                        if (pinching) return@detectDragGestures
                        total += drag
                        if (axis == 0 && total.getDistance() > 24f) {
                            axis = if (abs(total.x) > abs(total.y)) 1 else 2
                            startLevel = if (startX < width / 2) activity.brightness.takeIf { it >= 0 } ?: .5f
                            else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        }
                        when (axis) {
                            // A full-width swipe moves two minutes.
                            1 -> {
                                val target = (startPos + (total.x / width * 120_000).toLong()).coerceAtLeast(0)
                                onSeekPreview(target)
                                onLabel(null to "${formatTime(target)} (${if (target >= startPos) "+" else "-"}${formatTime(abs(target - startPos))})")
                            }
                            2 -> {
                                val level = (startLevel - total.y / (height * .8f)).coerceIn(0f, 1f)
                                if (startX < width / 2) {
                                    activity.setBrightness(level.coerceAtLeast(.01f))
                                    onLabel(Icons.Rounded.Brightness6 to "${(level * 100).roundToInt()} %")
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                                    onLabel(Icons.Rounded.VolumeUp to "${(level * 100).roundToInt()} %")
                                }
                            }
                        }
                    }
                },
        )
    }
}

@Composable
private fun Controls(
    activity: PlayerActivity,
    n: NowPlaying,
    position: Long,
    duration: Long,
    buffered: Long,
    playing: Boolean,
    seekPreview: Long?,
    onSeek: (Long) -> Unit,
    onSeekDone: () -> Unit,
    onClose: () -> Unit,
    onSheet: (String) -> Unit,
    onLock: () -> Unit,
    onInteract: () -> Unit,
    fill: Boolean,
    onFill: () -> Unit,
) {
    val playback = App.it.playback
    val player = playback.player
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))) {
        // Top: back, title, cast, tracks, settings.
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close player") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(n.item.seriesName ?: n.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.item.isEpisode) Text("${n.item.episodeLabel} · ${n.item.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MediaRouteButton(Modifier.size(48.dp))
            IconButton({ onSheet("tracks") }) { Icon(Icons.Rounded.Subtitles, "Audio and subtitles") }
            IconButton({ onSheet("settings") }) { Icon(Icons.Rounded.Settings, "Quality and speed") }
        }

        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ player.seekBack(); onInteract() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(40.dp)) }
            IconButton(
                { if (playing) player.pause() else player.play(); onInteract() },
                Modifier.size(80.dp).background(Color.White.copy(alpha = .14f), CircleShape),
            ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(52.dp)) }
            IconButton({ player.seekForward(); onInteract() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", Modifier.size(40.dp)) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SeekBar(n, position, duration, buffered, seekPreview, onSeek, onSeekDone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(seekPreview ?: position)} / ${formatTime(duration)}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onLock) { Icon(Icons.Rounded.LockOpen, "Lock controls") }
                IconButton({ activity.toggleRotationLock(); onInteract() }) {
                    Icon(if (activity.rotationLocked) Icons.Rounded.ScreenLockRotation else Icons.Rounded.ScreenRotation, "Rotation lock")
                }
                IconButton(onFill) { Icon(Icons.Rounded.AspectRatio, if (fill) "Fit to screen" else "Fill screen", tint = if (fill) Moon else Color.White) }
                IconButton({ activity.enterPip() }) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture") }
                if (n.next != null) IconButton({ playback.playNext() }) { Icon(Icons.Rounded.SkipNext, "Next episode") }
            }
        }
    }
}

/** Seek bar with the buffered range, chapter ticks and a trickplay thumbnail while dragging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(n: NowPlaying, position: Long, duration: Long, buffered: Long, preview: Long?, onSeek: (Long) -> Unit, onDone: () -> Unit) {
    val d = duration.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        if (preview != null) Trickplay(n, preview, Modifier.align(Alignment.BottomStart).offset(x = ((w - 160.dp) * (preview.toFloat() / d)).coerceAtLeast(0.dp), y = (-40).dp))
        Canvas(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)) {
            drawRect(Color.White.copy(alpha = .3f), size = size.copy(width = size.width * (buffered.toFloat() / d).coerceIn(0f, 1f)))
            for (c in n.item.chapters) if (c > 0) drawCircle(Moon, 3.dp.toPx(), Offset(size.width * c / d, size.height / 2))
        }
        Slider(
            ((preview ?: position).toFloat() / d).coerceIn(0f, 1f), { onSeek((it * d).toLong()) },
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Moon, activeTrackColor = Moon, inactiveTrackColor = Color.White.copy(alpha = .18f)),
        )
    }
}

@Composable
private fun Trickplay(n: NowPlaying, at: Long, modifier: Modifier) {
    val info = remember(n.item.id) { n.item.trickplay() }
    Column(modifier.width(160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (info != null && info.intervalMs > 0) {
            val index = (at / info.intervalMs).toInt().coerceAtMost(info.count - 1)
            val perTile = info.tileWidth * info.tileHeight
            val tile = index / perTile
            val x = index % perTile % info.tileWidth
            val y = index % perTile / info.tileWidth
            // One thumbnail is 160 dp wide; the tile image holds a grid of them.
            val thumbH = 160.dp * info.height / info.width
            Box(Modifier.width(160.dp).height(thumbH).clip(RoundedCornerShape(6.dp)).background(Bg)) {
                AsyncImage(
                    App.it.api.trickplayUrl(n.item.id, info.width, tile), null,
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(160.dp * info.tileWidth, thumbH * info.tileHeight)
                        .offset(x = -(160.dp * x), y = -(thumbH * y)),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        Text(formatTime(at), Modifier.padding(top = 2.dp).background(Bg.copy(alpha = .7f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** "Skip intro" / "Skip recap" while inside one, unless skipped automatically. */
@Composable
private fun SegmentButton(n: NowPlaying, position: Long, modifier: Modifier) {
    val seg = n.segments.firstOrNull { it.type in setOf("Intro", "Recap") && position in it.startMs until it.endMs - 1_000 } ?: return
    if (App.it.prefs.autoSkipIntro) return
    OutlinedButton({ App.it.playback.skip(seg) }, modifier, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Bg.copy(alpha = .7f))) {
        Text(if (seg.type == "Recap") "Skip recap" else "Skip intro")
    }
}

/** Next episode card at the outro or in the last 30 s, with a countdown when autoplay is on. */
@Composable
private fun UpNext(n: NowPlaying, position: Long, duration: Long, modifier: Modifier) {
    val next = n.next ?: return
    val outro = n.segments.firstOrNull { it.type == "Outro" }
    val show = duration > 0 && (outro != null && position >= outro.startMs || duration - position < 30_000)
    var dismissed by remember(n.item.id) { mutableStateOf(false) }
    if (!show || dismissed) return
    val autoplay = App.it.prefs.autoplayNext
    var left by remember(n.item.id) { mutableIntStateOf(10) }
    LaunchedEffect(n.item.id, autoplay) {
        if (!autoplay) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            if (App.it.playback.player.isPlaying) left--
        }
        App.it.playback.playNext()
    }
    Row(
        modifier.width(380.dp).clip(RoundedCornerShape(12.dp)).background(Bg.copy(alpha = .92f)).clickable { App.it.playback.playNext() }.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Picture(next.thumb, 140.dp, Modifier.width(140.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(if (autoplay) "Next episode in $left" else "Next episode", style = MaterialTheme.typography.labelMedium, color = Moon)
            Text("${next.episodeLabel} · ${next.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton({ dismissed = true }, contentPadding = PaddingValues(0.dp)) { Text("Hide") }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier) {
    var visible by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text) { delay(4_000); visible = false }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(text, Modifier.background(Bg.copy(alpha = .85f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksSheet(n: NowPlaying, onDismiss: () -> Unit) {
    val playback = App.it.playback
    ModalBottomSheet(onDismiss, containerColor = Raised) {
        Row(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f)) {
                item { Text("Audio", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = Violet) }
                items(n.streams.filter { it.isAudio }, key = { "a${it.index}" }) { s ->
                    Choice(s.displayTitle, s.index == n.audio) { playback.selectAudio(s.index); onDismiss() }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                item { Text("Subtitles", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = Violet) }
                item { Choice("Off", n.sub < 0) { playback.selectSubtitle(-1); onDismiss() } }
                items(n.streams.filter { it.isSub }, key = { "s${it.index}" }) { s ->
                    Choice(s.displayTitle + if (s.isExternal) " · external" else "", s.index == n.sub) { playback.selectSubtitle(s.index); onDismiss() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(n: NowPlaying, onDismiss: () -> Unit) {
    val playback = App.it.playback
    var speed by remember { mutableFloatStateOf(playback.player.playbackParameters.speed) }
    ModalBottomSheet(onDismiss, containerColor = Raised) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Text("Speed", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = Violet)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) FilterChip(speed == s, { speed = s; playback.player.setPlaybackSpeed(s) }, { Text("${s}×") })
                }
                Text("Quality", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = Violet)
                Text(
                    listOfNotNull(n.method.label, n.transcodeInfo, n.effective.label.takeIf { n.quality == Quality.Auto }?.let { "Auto: $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (n.method != Method.Offline) items(Quality.entries) { q ->
                Choice(q.label, q == n.quality) { playback.setQuality(q); onDismiss() }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (selected) Moon else Color.White, style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(Icons.Rounded.Check, null, tint = Moon)
    }
}
