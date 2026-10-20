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
import androidx.compose.ui.draw.shadow
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

// From the icon: a near-black night sky, violet haze, the lit edge of the moon.
val Bg = Color(0xFF0A0915)
val Raised = Color(0xFF15122A)
val Raised2 = Color(0xFF211C3E)
val Violet = Color(0xFF9B87FF)
val Moon = Color(0xFFFFE9D2)
val Dusk = Color(0xFFD8A0AE)
val Sub = Color(0xFFB9B3D6)
val Faint = Color(0xFF7D769E)

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
        secondary = Violet,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF3A2F7A),
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
        outlineVariant = Color(0xFF2E2852),
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

/** Moonlight on text: white into the moon's warm rim and the dusk pink of the icon. */
val MoonText = Brush.horizontalGradient(listOf(Color.White, Moon, Color(0xFFF2B8A8), Dusk))

/** A soft coloured halo around [shape], like the light around the crescent. */
fun Modifier.glow(color: Color = Moon, radius: Dp = 18.dp, shape: Shape = RoundedCornerShape(50)) =
    shadow(radius, shape, clip = false, ambientColor = color, spotColor = color)

/**
 * The app's backdrop: the night sky from the icon. Near-black, a violet haze from the top corner,
 * a little dusk from below, and a scatter of faint stars, a few of them twinkling with a soft cross.
 */
@Composable
fun NightSky(modifier: Modifier = Modifier) {
    val stars = remember {
        val r = java.util.Random(7)
        List(55) { Triple(Offset(r.nextFloat(), r.nextFloat()), .4f + r.nextFloat() * 1.1f, .08f + r.nextFloat() * .4f) }
    }
    Canvas(modifier) {
        drawRect(Bg)
        drawRect(Brush.radialGradient(listOf(Violet.copy(alpha = .20f), Color.Transparent), Offset(size.width * .82f, -size.height * .05f), size.maxDimension * .75f))
        drawRect(Brush.radialGradient(listOf(Dusk.copy(alpha = .07f), Color.Transparent), Offset(size.width * .05f, size.height * 1.05f), size.maxDimension * .6f))
        for ((p, radius, a) in stars) {
            val c = Offset(p.x * size.width, p.y * size.height)
            drawCircle(Color.White.copy(alpha = a), radius.dp.toPx(), c)
            if (radius > 1.35f) {
                // The brightest few get a glow and a faint cross.
                drawCircle(Brush.radialGradient(listOf(Moon.copy(alpha = a * .5f), Color.Transparent), c, 7.dp.toPx()), 7.dp.toPx(), c)
                val l = 6.dp.toPx()
                drawLine(Moon.copy(alpha = a * .6f), c.copy(x = c.x - l), c.copy(x = c.x + l), .6.dp.toPx())
                drawLine(Moon.copy(alpha = a * .6f), c.copy(y = c.y - l), c.copy(y = c.y + l), .6.dp.toPx())
            }
        }
    }
}
