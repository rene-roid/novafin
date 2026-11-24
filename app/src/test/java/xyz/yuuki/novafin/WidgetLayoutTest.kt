package xyz.yuuki.novafin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WidgetLayoutTest {
    // RemoteViews refuses anything else at inflation time, and the launcher only says "Can't load widget".
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "TextView", "ImageView", "ImageButton", "Button",
        "ProgressBar", "Chronometer", "ViewFlipper", "ListView", "GridView", "StackView", "AdapterViewFlipper", "merge", "include",
    )

    @Test fun widgetLayoutsUseOnlyRemoteViewsClasses() {
        val layouts = File("src/main/res/layout").listFiles { f -> f.name.startsWith("widget_") }.orEmpty()
        val bad = layouts.flatMap { f -> Regex("<([A-Za-z.]+)").findAll(f.readText()).map { it.groupValues[1] }.filter { it !in allowed }.map { "${f.name}: <$it>" }.toList() }
        assertEquals(emptyList<String>(), bad)
        assertEquals(true, layouts.isNotEmpty())
    }
}
