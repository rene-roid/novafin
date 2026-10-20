package xyz.yuuki.novafin.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private class Star(val x: Float, val y: Float, val radius: Float, val depth: Float, val phase: Float, val twinkle: Float)

/** A curtain of aurora light: its lower edge waves, light rises from it and fades upward. */
private class Ribbon(val baseY: Float, val height: Float, val amp: Float, val waves: Float, val speed: Float, val phase: Float, val colors: List<Color>)

private val ribbons = listOf(
    Ribbon(.17f, .15f, .04f, 1.4f, .05f, 0f, listOf(Glow, Sky)),
    Ribbon(.24f, .17f, .05f, 1.0f, -.035f, 2f, listOf(Sky, Violet)),
    Ribbon(.12f, .10f, .03f, 2.1f, .07f, 4f, listOf(Violet, Dusk)),
)

/**
 * The app's sky: a deep blue-black gradient, slowly flowing aurora curtains at the top, three
 * layers of twinkling stars drifting at different speeds, and now and then a shooting star.
 * Everything is drawn in one Canvas from a single clock, so only this layer redraws each frame.
 * Holds still when the system's animations are turned off.
 */
@Composable
fun Cosmos(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val stars = remember {
        val r = java.util.Random(42)
        List(150) {
            val depth = r.nextInt(3).toFloat()
            Star(r.nextFloat(), r.nextFloat(), .4f + depth * .3f + r.nextFloat() * .3f, depth, r.nextFloat() * 6.28f, .6f + r.nextFloat() * 1.6f)
        }
    }
    val clock = rememberInfiniteTransition("cosmos")
    val seconds by clock.animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t")

    Canvas(modifier) {
        val t = if (still) 0f else seconds
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0C1030), Bg, Color(0xFF05050E))))
        for (r in ribbons) drawRibbon(r, t)
        drawStars(stars, t)
        if (!still) drawShootingStar(t)
    }
}

private fun DrawScope.drawRibbon(r: Ribbon, t: Float) {
    val w = size.width
    val h = size.height
    val strips = 120
    val sw = w / strips
    val pulse = .8f + .2f * sin(t * .25f + r.phase)
    for (i in 0 until strips) {
        val fx = (i + .5f) / strips
        // Two waves of different length make the curtain fold instead of just sliding.
        val edge = h * (r.baseY + r.amp * sin(fx * r.waves * 2 * PI.toFloat() + t * r.speed * 6 + r.phase) +
            r.amp * .5f * sin(fx * r.waves * 5.3f + t * r.speed * 9 + r.phase * 1.7f))
        // Rays: each strip has its own brightness and height, shimmering slowly, like real aurora.
        val ray = .55f + .3f * sin(i * .21f + t * r.speed * 10 + r.phase) + .15f * sin(i * .57f - t * .17f)
        val rise = r.height * h * (.7f + .45f * ray)
        val fall = 10.dp.toPx()
        val a = .36f * pulse * (.45f + .55f * ray)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                .55f to r.colors[1].copy(alpha = a * .35f),
                .9f to r.colors[0].copy(alpha = a),
                1f to Color.Transparent,
                startY = edge - rise,
                endY = edge + fall,
            ),
            // Whole pixels, so neighbouring strips neither overlap nor leave gaps.
            topLeft = Offset(kotlin.math.floor(i * sw), edge - rise),
            size = androidx.compose.ui.geometry.Size(kotlin.math.floor((i + 1) * sw) - kotlin.math.floor(i * sw), rise + fall),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawStars(stars: List<Star>, t: Float) {
    for (s in stars) {
        // Nearer layers drift faster, which gives the sky depth.
        val x = ((s.x + t * .0015f * (s.depth + 1)) % 1f) * size.width
        val y = s.y * size.height
        val a = (.35f + .65f * (.5f + .5f * sin(t * s.twinkle + s.phase))) * (.35f + s.depth * .25f)
        val c = Offset(x, y)
        drawCircle(Color.White.copy(alpha = a), s.radius.dp.toPx(), c)
    }
}

/** One shooting star every 11 seconds, crossing in under a second. */
private fun DrawScope.drawShootingStar(t: Float) {
    val period = 11f
    val n = (t / period).toInt()
    val p = (t % period) / .9f
    if (p > 1f) return
    val r = java.util.Random(n.toLong())
    val start = Offset(size.width * (.3f + r.nextFloat() * .7f), size.height * r.nextFloat() * .35f)
    val dir = Offset(-.8f, .45f)
    val travel = size.width * .55f
    val head = start + dir * (travel * p)
    val tail = head - dir * (travel * .35f)
    val fade = sin(p * PI.toFloat())
    drawLine(
        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .9f * fade)), tail, head),
        tail, head, 1.6.dp.toPx(), StrokeCap.Round,
    )
    drawCircle(Glow.copy(alpha = .5f * fade), 3.dp.toPx(), head)
}
