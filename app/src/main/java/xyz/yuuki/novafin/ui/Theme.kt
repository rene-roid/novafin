package xyz.yuuki.novafin.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.yuuki.novafin.R

// Neutral and dark: near-black page, #2b2b2b surfaces, the moon's cream for the main actions.
val Bg = Color(0xFF0E0E0E)
val Raised = Color(0xFF1C1C1C)
val Raised2 = Color(0xFF2B2B2B)
/** Highlights that aren't the main action: section labels, selected states, watched marks. */
val Accent = Color(0xFFDADADA)
val Moon = Color(0xFFFFE9D2)
val Dusk = Color(0xFFD8A0AE)
val Sub = Color(0xFFB0B0B0)
val Faint = Color(0xFF777777)

// Static instances of the variable font: some skins (HyperOS) ignore font variation settings and draw everything thin.
val Figtree = FontFamily(
    Font(R.font.figtree_400, FontWeight.W400),
    Font(R.font.figtree_500, FontWeight.W500),
    Font(R.font.figtree_600, FontWeight.W600),
    Font(R.font.figtree_700, FontWeight.W700),
    Font(R.font.figtree_800, FontWeight.W800),
)

// No colour here: text follows LocalContentColor, so it reads on light buttons too.
private val base = TextStyle(fontFamily = Figtree)

private val typography = Typography(
    displaySmall = base.copy(fontSize = 34.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.5).sp),
    headlineMedium = base.copy(fontSize = 26.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.3).sp),
    headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.W700),
    titleLarge = base.copy(fontSize = 20.sp, fontWeight = FontWeight.W700),
    titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.W600),
    titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.W600),
    bodyLarge = base.copy(fontSize = 16.sp),
    bodyMedium = base.copy(fontSize = 14.sp),
    bodySmall = base.copy(fontSize = 13.sp, color = Sub),
    labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.W700),
    labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
    labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.W600),
)

@Composable
fun NovafinTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Moon,
        onPrimary = Bg,
        secondary = Accent,
        onSecondary = Bg,
        secondaryContainer = Raised2,
        onSecondaryContainer = Color.White,
        tertiary = Dusk,
        background = Bg,
        onBackground = Color.White,
        surface = Bg,
        onSurface = Color.White,
        onSurfaceVariant = Sub,
        surfaceContainerLowest = Bg,
        surfaceContainerLow = Raised,
        surfaceContainer = Raised,
        surfaceContainerHigh = Raised2,
        surfaceContainerHighest = Raised2,
        outline = Faint,
        outlineVariant = Color(0xFF333333),
    ),
    typography = typography,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatRuntime(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

/** A soft coloured halo around [shape]; kept for the one main action on a page. */
fun Modifier.glow(color: Color = Moon.copy(alpha = .55f), radius: Dp = 14.dp, shape: Shape = RoundedCornerShape(50)) =
    shadow(radius, shape, clip = false, ambientColor = color, spotColor = color)

/** The app's backdrop: plain near-black. */
@Composable
fun NightSky(modifier: Modifier = Modifier) = Canvas(modifier) { drawRect(Bg) }

/**
 * Light from the artwork: a tiny, heavily blurred copy of [img] behind the page, so each show tints
 * its own page in its colours, fading into the dark further down.
 */
@Composable
fun AmbientLight(img: xyz.yuuki.novafin.Img?, modifier: Modifier = Modifier) {
    img ?: return
    coil3.compose.AsyncImage(
        xyz.yuuki.novafin.App.it.api.imageUrl(img.id, img.type, 64, img.tag), null,
        modifier
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen, alpha = .55f)
            .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Black, .35f to Color.Black.copy(alpha = .7f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    )
}

/** Moves with the top of [list], so a backdrop effect scrolls away with the page. */
fun Modifier.scrollsWith(list: androidx.compose.foundation.lazy.LazyListState) = graphicsLayer {
    translationY = if (list.firstVisibleItemIndex == 0) -list.firstVisibleItemScrollOffset.toFloat() else -size.height
}

/** Cards and tiles ease down a little while pressed. */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }.clickable(source, ripple(color = Moon), onClick = onClick)
}
