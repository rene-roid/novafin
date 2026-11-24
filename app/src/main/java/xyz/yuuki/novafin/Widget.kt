package xyz.yuuki.novafin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a widget shows for one item; [img] is a thumbnail reference, not pixels. */
private class Entry(val id: String, val title: String, val sub: String, val progress: Float, val img: Img?)

enum class WidgetKind(val key: String) { Resume("resume"), NextUp("nextup") }

class ResumeWidget : HomeWidgetProvider(WidgetKind.Resume)
class NextUpWidget : HomeWidgetProvider(WidgetKind.NextUp)

abstract class HomeWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = HomeWidget.update(ctx, mgr, ids, kind, goAsync())

    // Pre-31 the layout follows the size; 31+ picks it from the size map, so this only matters for 28-30.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        if (Build.VERSION.SDK_INT < 31) HomeWidget.update(ctx, mgr, intArrayOf(id), kind, goAsync())
    }
}

/** Home screen widgets, drawn from a local snapshot so they never need the network to show something. */
object HomeWidget {
    const val ACTION_OPEN = "xyz.yuuki.novafin.widget.OPEN"
    const val ACTION_PLAY = "xyz.yuuki.novafin.widget.PLAY"
    const val EXTRA_ID = "id"

    private const val SLOTS = 4
    private val providers = mapOf(WidgetKind.Resume to ResumeWidget::class.java, WidgetKind.NextUp to NextUpWidget::class.java)

    /** Redraws every placed widget. */
    fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in providers.values) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isNotEmpty()) ctx.sendBroadcast(Intent(ctx, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }

    internal fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray, kind: WidgetKind, pending: BroadcastReceiver.PendingResult) {
        val app = App.it
        app.scope.launch {
            val bitmaps = ConcurrentHashMap<String, Bitmap>()
            try {
                // A receiver has 10 s; leave room to render the snapshot afterwards.
                withTimeoutOrNull(7_000) {
                    if (app.prefs.loggedIn) runCatching { fetch(kind) }.onSuccess { save(ctx, kind, it) }
                    coroutineScope {
                        load(ctx, kind).take(SLOTS).map { e -> async { e.img?.let { thumb(ctx, it) }?.let { bitmaps[e.id] = it } } }.awaitAll()
                    }
                }
                val entries = if (app.prefs.loggedIn) load(ctx, kind) else null
                for (id in ids) mgr.updateAppWidget(id, render(ctx, kind, entries, bitmaps, mgr, id))
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetch(kind: WidgetKind): List<Entry> {
        val api = App.it.api
        return (if (kind == WidgetKind.Resume) api.resume() else api.nextUp()).take(SLOTS).map { i ->
            val u = i.userData
            Entry(
                i.id,
                if (i.isEpisode) i.seriesName ?: i.name else i.name,
                if (i.isEpisode) listOfNotNull(i.episodeLabel, i.name).joinToString(" · ") else i.year?.toString().orEmpty(),
                (if (u.playedPercentage > 0) u.playedPercentage / 100 else if (i.runTimeMs > 0) u.positionMs.toDouble() / i.runTimeMs else 0.0).toFloat().coerceIn(0f, 1f),
                i.thumb,
            )
        }
    }

    private fun file(ctx: Context, kind: WidgetKind) = File(ctx.filesDir, "widget_${kind.key}.json")

    private fun save(ctx: Context, kind: WidgetKind, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("id", e.id).put("title", e.title).put("sub", e.sub).put("progress", e.progress.toDouble()).apply {
            e.img?.let { put("imgId", it.id).put("imgType", it.type).put("imgTag", it.tag) }
        })
        file(ctx, kind).writeText(a.toString())
    }

    private fun load(ctx: Context, kind: WidgetKind): List<Entry> = runCatching {
        val a = JSONArray(file(ctx, kind).readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            Entry(o.getString("id"), o.getString("title"), o.getString("sub"), o.getDouble("progress").toFloat(),
                o.optString("imgId").takeIf { s -> s.isNotEmpty() }?.let { id -> Img(id, o.getString("imgType"), o.optString("imgTag").ifEmpty { null }) })
        }
    }.getOrDefault(emptyList())

    // RGB_565 at 320x180 is ~115 KB; five of them stay well under the ~1 MB binder limit.
    private suspend fun thumb(ctx: Context, img: Img): Bitmap? {
        val req = ImageRequest.Builder(ctx).data(App.it.api.imageUrl(img.id, img.type, 320, img.tag)).size(Size(320, 180))
            .allowHardware(false).bitmapConfig(Bitmap.Config.RGB_565).build()
        return (SingletonImageLoader.get(ctx).execute(req) as? SuccessResult)?.image?.toBitmap()
    }

    private fun open(ctx: Context, action: String?, id: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN)
            .setData(Uri.parse("novafin://widget/${action ?: "app"}/${id.orEmpty()}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        id?.let { i.putExtra(EXTRA_ID, it) }
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun message(ctx: Context, title: Int, sub: Int) = RemoteViews(ctx.packageName, R.layout.widget_message).apply {
        setTextViewText(R.id.title, ctx.getString(title))
        setTextViewText(R.id.sub, ctx.getString(sub))
        setOnClickPendingIntent(android.R.id.background, open(ctx, null, null))
    }

    private fun fill(v: RemoteViews, ctx: Context, e: Entry, bmp: Bitmap?) {
        v.setTextViewText(R.id.title, e.title)
        v.setTextViewText(R.id.sub, e.sub)
        if (bmp != null) v.setImageViewBitmap(R.id.img, bmp)
        v.setViewVisibility(R.id.progress, if (e.progress > 0) View.VISIBLE else View.GONE)
        v.setProgressBar(R.id.progress, 1000, (e.progress * 1000).toInt(), false)
    }

    private fun compact(ctx: Context, e: Entry, bmp: Bitmap?) = RemoteViews(ctx.packageName, R.layout.widget_compact).apply {
        fill(this, ctx, e, bmp)
        setOnClickPendingIntent(android.R.id.background, open(ctx, ACTION_OPEN, e.id))
        setOnClickPendingIntent(R.id.play, open(ctx, ACTION_PLAY, e.id))
    }

    private fun wide(ctx: Context, list: List<Entry>, bitmaps: Map<String, Bitmap>) = RemoteViews(ctx.packageName, R.layout.widget_wide).apply {
        for (i in 0 until SLOTS) {
            val card = RemoteViews(ctx.packageName, R.layout.widget_card)
            val e = list.getOrNull(i)
            if (e == null) card.setViewVisibility(R.id.card, View.INVISIBLE) else {
                fill(card, ctx, e, bitmaps[e.id])
                card.setOnClickPendingIntent(R.id.card, open(ctx, ACTION_OPEN, e.id))
            }
            addView(R.id.row, card)
        }
    }

    private fun render(ctx: Context, kind: WidgetKind, entries: List<Entry>?, bitmaps: Map<String, Bitmap>, mgr: AppWidgetManager, id: Int): RemoteViews {
        if (entries == null) return message(ctx, R.string.widget_signin, R.string.widget_signin_sub)
        if (entries.isEmpty()) return message(ctx, R.string.widget_empty, R.string.widget_empty_sub)
        val one = compact(ctx, entries[0], bitmaps[entries[0].id])
        val many = wide(ctx, entries, bitmaps)
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(SizeF(110f, 110f) to one, SizeF(250f, 110f) to many))
        val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        return if (w >= 220) many else one
    }
}
