package xyz.yuuki.novafin.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.yuuki.novafin.R

// From the icon: night sky, violet glow, the moon.
val Bg = Color(0xFF16132B)
val Raised = Color(0xFF221D3D)
val Raised2 = Color(0xFF2D2750)
val Violet = Color(0xFF8F7BFF)
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
        secondaryContainer = Color(0xFF4B3F94),
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
        outlineVariant = Color(0xFF3A3360),
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
