package xyz.yuuki.novafin.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private class Star(val x: Float, val y: Float, val radius: Float, val depth: Float, val phase: Float, val twinkle: Float)

/** A curtain of aurora light: its lower edge waves, light rises from it and fades upward. */
private class Ribbon(val baseY: Float, val height: Float, val amp: Float, val waves: Float, val speed: Float, val phase: Float, val colors: List<Color>)

/**
 * Aurora as a GPU shader (Android 13+): three curtains whose lower edges wave and fold, light rising
 * from a bright rim and fading upward, fine vertical rays that shimmer, colour shifting with height.
 * Dithered so the soft gradients don't band.
 */
private const val AURORA_AGSL = """
uniform float2 res;
uniform float region;
uniform float time;
uniform float3 teal;
uniform float3 sky;
uniform float3 violet;
uniform float3 pink;

float hash(float n) { return fract(sin(n) * 43758.5453); }

// Smooth everywhere: sums of sines at unrelated frequencies instead of cell noise.
float waves(float x, float t) {
    return 0.5 * sin(x * 2.7 + t * 0.11) + 0.3 * sin(x * 5.9 - t * 0.17 + 1.3) + 0.2 * sin(x * 13.1 + t * 0.23 + 2.1);
}
float rays(float x, float t) {
    return 0.45 * sin(x * 61.0 + t * 0.7) + 0.30 * sin(x * 97.0 - t * 1.1 + 0.7)
        + 0.15 * sin(x * 151.0 + t * 1.7 + 1.9) * sin(x * 7.0 - t * 0.3) + 0.10 * sin(x * 233.0 - t * 2.3 + 4.1);
}

float3 curtain(float2 uv, float base, float h, float amp, float w, float sp, float ph, float3 a, float3 b, float t) {
    float x = uv.x;
    float edge = base + amp * sin(x * w + t * sp + ph) + amp * 0.5 * sin(x * w * 2.3 - t * sp * 1.4 + ph * 1.7)
        + amp * 0.35 * waves(x * 3.0 + ph, t);
    float d = edge - uv.y;
    // Each ray has its own brightness and reaches its own height, shimmering as it moves.
    float r = rays(x + ph, t * (0.6 + abs(sp)));
    float ray = 0.55 + 0.45 * r;
    float below = smoothstep(-0.022, 0.0, d);
    float rise = exp(-max(d, 0.0) / (h * (0.55 + 0.75 * ray)));
    float rim = exp(-abs(d) / 0.009) * 0.55;
    float k = below * (rise * (0.35 + 0.65 * ray) + rim * ray);
    float3 col = mix(a, b, clamp(d / (h * 1.8), 0.0, 1.0));
    return col * k;
}

half4 main(float2 p) {
    // The buffer covers the top [region] of the screen; curtain positions are in whole-screen units.
    float2 uv = float2(p.x / res.x, p.y / res.y * region);
    float t = time;
    float3 acc = curtain(uv, 0.24, 0.10, 0.045, 5.5, 0.22, 2.0, sky, violet, t) * 0.36;
    acc += curtain(uv, 0.17, 0.09, 0.040, 8.0, 0.30, 0.0, teal, sky, t) * 0.50;
    acc += curtain(uv, 0.11, 0.06, 0.030, 12.0, -0.40, 4.0, violet, pink, t) * 0.28;
    // Breathing brightness.
    acc *= 0.85 + 0.15 * sin(t * 0.3);
    acc = max(acc + (hash(p.x * 12.9898 + p.y * 78.233) - 0.5) / 255.0, 0.0);
    // Premultiplied, with alpha as bright as the light, so it glows over the sky like an additive blend.
    float a = clamp(max(acc.r, max(acc.g, acc.b)), 0.0, 1.0);
    return half4(half3(acc), half(a));
}
"""

@RequiresApi(33)
private class AuroraShader {
    private val shader = RuntimeShader(AURORA_AGSL).apply {
        for ((name, c) in listOf("teal" to Glow, "sky" to Sky, "violet" to Violet, "pink" to Dusk)) setFloatUniform(name, c.red, c.green, c.blue)
    }

    fun brush(w: Float, h: Float, region: Float, t: Float): ShaderBrush {
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("region", region)
        shader.setFloatUniform("time", t)
        return ShaderBrush(shader)
    }
}

private val ribbons = listOf(
    Ribbon(.17f, .15f, .04f, 1.4f, .05f, 0f, listOf(Glow, Sky)),
    Ribbon(.24f, .17f, .05f, 1.0f, -.035f, 2f, listOf(Sky, Violet)),
    Ribbon(.12f, .10f, .03f, 2.1f, .07f, 4f, listOf(Violet, Dusk)),
)

/**
 * The app's sky: a deep blue-black gradient, slowly flowing aurora curtains at the top (a shader on
 * Android 13+, soft strips before that), three
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
    val aurora = remember { if (Build.VERSION.SDK_INT >= 33) AuroraShader() else null }
    val clock = rememberInfiniteTransition("cosmos")
    val seconds by clock.animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t")

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0C1030), Bg, Color(0xFF05050E))))
            if (aurora == null) for (r in ribbons) drawRibbon(r, if (still) 0f else seconds)
        }
        if (aurora != null) BoxWithConstraints(Modifier.fillMaxSize()) {
            // The aurora's rays are vertical and soft from top to bottom, so it is rendered at full width
            // but a sixth of the height of the area it covers, then stretched smoothly. That keeps the
            // per-pixel shader cheap enough to run every frame without blurring the rays.
            val region = .5f
            Canvas(
                Modifier.size(maxWidth, maxHeight * region / 6).graphicsLayer {
                    scaleY = 6f
                    transformOrigin = TransformOrigin(0f, 0f)
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ) {
                drawRect(aurora.brush(size.width, size.height, region, if (still) 0f else seconds))
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = if (still) 0f else seconds
            drawStars(stars, t)
            if (!still) drawShootingStar(t)
        }
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
