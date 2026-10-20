package xyz.yuuki.novafin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.yuuki.novafin.R

// Aurora: deep space blue-black, aurora teal for what's active, cyan, violet and pink in the sky.
val Bg = Color(0xFF070818)
val Raised = Color(0xFF14162F)
val Raised2 = Color(0xFF1F2247)
val Glow = Color(0xFF6FF2D2)
val Sky = Color(0xFF5CC8FF)
val Violet = Color(0xFF9D86FF)
val Dusk = Color(0xFFFF7EC8)
val Sub = Color(0xFFB4B8D9)
val Faint = Color(0xFF6E7299)

/** The aurora's colours, for the main buttons and progress. */
val AuroraBrush = Brush.linearGradient(listOf(Glow, Sky, Violet))

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
        primary = Glow,
        onPrimary = Bg,
        secondary = Violet,
        onSecondary = Color.White,
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
        outlineVariant = Color(0xFF2A2E5A),
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

/** The main action on a screen: an aurora-gradient pill with dark text. */
@Composable
fun AuroraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = Button(
    onClick,
    modifier.background(if (enabled) AuroraBrush else SolidColor(Raised2), CircleShape),
    enabled,
    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Bg, disabledContainerColor = Color.Transparent, disabledContentColor = Faint),
    content = content,
)
