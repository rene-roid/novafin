package xyz.yuuki.novafin.ui

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import xyz.yuuki.novafin.App
import xyz.yuuki.novafin.Img
import xyz.yuuki.novafin.Item
import xyz.yuuki.novafin.PlayerActivity

// ---- navigation -----------------------------------------------------------------------------

sealed interface Route {
    data object Home : Route
    data object Library : Route
    data object Search : Route
    data object Downloads : Route
    data object Settings : Route
    data object Addresses : Route
    data class Details(val id: String) : Route
}

class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    var retap by mutableIntStateOf(0)

    fun go(r: Route) = stack.add(r)

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun tab(r: Route) {
        if (stack.toList() == listOf(r)) retap++
        stack.clear()
        stack.add(r)
    }

    fun open(item: Item) {
        // Episodes open their own page; seasons open the show scrolled to them.
        go(Route.Details(if (item.isSeason) item.json.optString("SeriesId").ifEmpty { item.id } else item.id))
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

// ---- layout ---------------------------------------------------------------------------------

enum class Width { Compact, Medium, Expanded }

@Composable
fun widthClass(): Width {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> Width.Compact
        w < 840 -> Width.Medium
        else -> Width.Expanded
    }
}

@Composable
fun isTablet() = widthClass() != Width.Compact

// ---- data loading ---------------------------------------------------------------------------

private val uiCache = HashMap<String, Any?>()

class Load<T>(val data: T?, val error: String?, val loading: Boolean, val refresh: () -> Unit)

/** Shows the last result for [key] at once, then refreshes it. Back navigation is instant. */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(key: String, loader: suspend () -> T): Load<T> {
    var data by remember(key) { mutableStateOf(uiCache[key] as T?) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var tick by remember(key) { mutableIntStateOf(0) }
    val online by App.it.api.online.collectAsStateWithLifecycle()
    LaunchedEffect(key, tick, online) {
        loading = true
        try {
            val v = loader()
            uiCache[key] = v
            data = v
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server"
        }
        loading = false
    }
    return Load(data, error, loading) { tick++ }
}

fun forgetUi(prefix: String) = uiCache.keys.filter { it.startsWith(prefix) }.forEach { uiCache.remove(it) }

// ---- images ---------------------------------------------------------------------------------

/** A downloaded copy when there is one, else the server image at the drawn size. */
@Composable
fun imageModel(img: Img?, widthDp: Dp): Any? {
    img ?: return null
    val app = App.it
    app.downloads.file(img.id, "${img.type}.webp")?.let { return it }
    val px = with(LocalDensity.current) { widthDp.toPx() }.toInt()
    val small = app.prefs.smallImagesOnMobile && app.metered
    // Rounded up to a step so nearby sizes share one cached image.
    val w = ((if (small) px * 3 / 4 else px) / 100 + 1) * 100
    return app.api.imageUrl(img.id, img.type, w.coerceAtMost(1920), img.tag)
}

@Composable
fun Picture(img: Img?, width: Dp, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(Raised)) {
        AsyncImage(imageModel(img, width), null, Modifier.fillMaxSize(), contentScale = scale)
    }
}

// ---- cards ----------------------------------------------------------------------------------

private val cardShape = RoundedCornerShape(8.dp)

@Composable
fun Badges(item: Item, modifier: Modifier = Modifier) {
    val dl by App.it.downloads.states.collectAsStateWithLifecycle()
    Row(modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dl[item.id]?.done == true) Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(20.dp).background(Bg.copy(alpha = .7f), CircleShape).padding(2.dp), tint = Moon)
        if (item.userData.played) Icon(Icons.Rounded.CheckCircle, "Watched", Modifier.size(20.dp).background(Bg.copy(alpha = .7f), CircleShape), tint = Violet)
        else if (item.userData.unplayed > 0) Text(
            item.userData.unplayed.toString(), Modifier.background(Violet, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun ProgressLine(item: Item, modifier: Modifier = Modifier) {
    val u = item.userData
    val pct = when {
        u.positionMs > 0 && item.runTimeMs > 0 -> u.positionMs.toFloat() / item.runTimeMs
        u.playedPercentage > 0 && !u.played -> (u.playedPercentage / 100).toFloat()
        else -> return
    }
    LinearProgressIndicator({ pct.coerceIn(0f, 1f) }, modifier.fillMaxWidth().height(3.dp), color = Moon, trackColor = Color.White.copy(alpha = .25f), drawStopIndicator = {}, gapSize = 0.dp)
}

/** 2:3 poster with title below. */
@Composable
fun PosterCard(item: Item, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(width).clip(cardShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3).clip(cardShape)) {
            Picture(if (item.isEpisode) item.poster else item.poster ?: item.thumb, width, Modifier.fillMaxSize())
            Badges(item, Modifier.align(Alignment.TopEnd))
            ProgressLine(item, Modifier.align(Alignment.BottomCenter))
        }
        Text(item.name, Modifier.padding(top = 6.dp, start = 2.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(item.year?.toString(), item.seriesName?.takeIf { item.isEpisode }).joinToString(" · "),
            Modifier.padding(start = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
        )
    }
}

/** 16:9 still with progress, for continue watching and episodes. */
@Composable
fun ThumbCard(item: Item, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(width).clip(cardShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(cardShape)) {
            Picture(item.thumb ?: item.poster, width, Modifier.fillMaxSize())
            Badges(item, Modifier.align(Alignment.TopEnd))
            ProgressLine(item, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            if (item.isEpisode) item.seriesName ?: item.name else item.name, Modifier.padding(top = 6.dp, start = 2.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (item.isEpisode) "${item.episodeLabel} · ${item.name}" else listOfNotNull(item.year?.toString(), item.runTimeMs.takeIf { it > 0 }?.let(::formatRuntime)).joinToString(" · "),
            Modifier.padding(start = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
fun ItemRow(title: String, items: List<Item>, thumbs: Boolean = false) {
    if (items.isEmpty()) return
    val nav = LocalNav.current
    val tablet = isTablet()
    Section(title) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                if (thumbs) ThumbCard(item, if (tablet) 300.dp else 240.dp) { nav.open(item) }
                else PosterCard(item, if (tablet) 150.dp else 120.dp) { nav.open(item) }
            }
        }
    }
}

// ---- states ---------------------------------------------------------------------------------

@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition("shimmer")
    val a by t.animateFloat(.35f, .7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(cardShape).background(Raised.copy(alpha = a)))
}

@Composable
fun LoadingRows() = Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    repeat(3) {
        Shimmer(Modifier.width(160.dp).height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { Shimmer(Modifier.width(120.dp).aspectRatio(2f / 3)) } }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(48.dp), tint = Faint)
        Text(message, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Sub)
        if (onRetry != null) TextButton(onRetry) { Text("Retry") }
    }
}

@Composable
fun Empty(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Sub)
        if (action != null) Button(onAction, Modifier.padding(top = 12.dp)) { Text(action) }
    }
}

/** Fades the bottom of a backdrop into the page. */
fun Modifier.scrim() = background(Brush.verticalGradient(0f to Color.Transparent, .55f to Bg.copy(alpha = .6f), 1f to Bg))

@Composable
fun BoxScope.TopScrim() = Box(Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Bg.copy(alpha = .7f), Color.Transparent))))

// ---- playback entry -------------------------------------------------------------------------

/** Starts [item] and opens the fullscreen player. */
@Composable
fun rememberPlay(): (Item, Long?, Int?, Int?) -> Unit {
    val ctx = LocalContext.current
    return remember {
        { item, start, audio, sub ->
            App.it.playback.play(item, start, audio, sub)
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
