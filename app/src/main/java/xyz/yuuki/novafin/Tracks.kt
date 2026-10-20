package xyz.yuuki.novafin

import java.time.Instant
import java.util.Locale

/**
 * Fallbacks when nothing is remembered: the user's languages in order of preference (app settings,
 * else the server user's), and Jellyfin's subtitle mode.
 */
data class TrackDefaults(val audioLangs: List<String>, val subLangs: List<String>, val subMode: String)

// Files tag languages with bibliographic codes ("fre", "ger"), Java and ExoPlayer use terminology ones ("fra", "deu").
private val BIBLIOGRAPHIC = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld", "fre" to "fra",
    "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas",
    "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
)

/** One code per language, whatever form the file, ExoPlayer or the settings use ("en", "eng", "fre", "fra"). */
fun langKey(code: String?): String? {
    val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.ifEmpty { null } ?: return null
    val three = if (c.length == 2) runCatching { Locale.forLanguageTag(c).isO3Language }.getOrNull()?.ifEmpty { null } ?: c else c
    return BIBLIOGRAPHIC[three] ?: three
}

/** Word overlap between two titles, 0..1. */
private fun similarity(a: String?, b: String?): Double {
    val x = a?.lowercase()?.split(Regex("\\W+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val y = b?.lowercase()?.split(Regex("\\W+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    if (x.isEmpty() || y.isEmpty()) return 0.0
    return (x intersect y).size.toDouble() / (x union y).size
}

fun sameLang(a: String?, b: String?) = a != null && b != null && langKey(a) == langKey(b)

/** Audio stream index to play: the remembered language, then the default language, then the file's default. */
fun pickAudio(streams: List<Stream>, mem: TrackMemory?, d: TrackDefaults): Int? {
    val audio = streams.filter { it.isAudio }
    if (audio.isEmpty()) return null
    mem?.audioLang?.let { lang ->
        audio.filter { sameLang(it.language, lang) }
            .maxWithOrNull(compareBy<Stream> { similarity(it.title ?: it.displayTitle, mem.audioTitle) }.thenBy { -kotlin.math.abs(it.channels - mem.audioChannels) })
            ?.let { return it.index }
    }
    for (lang in d.audioLangs) audio.firstOrNull { sameLang(it.language, lang) }?.let { return it.index }
    return (audio.firstOrNull { it.isDefault } ?: audio.first()).index
}

/** Subtitle stream index to show, or -1 for none. [audioLang] is the language of the audio being played. */
fun pickSubtitle(streams: List<Stream>, mem: TrackMemory?, d: TrackDefaults, audioLang: String?): Int {
    val subs = streams.filter { it.isSub }
    if (mem != null && (mem.subOff || mem.subLang != null)) {
        if (mem.subOff) return -1
        val sameLanguage = subs.filter { sameLang(it.language, mem.subLang) }
        val best = sameLanguage.maxWithOrNull(
            compareBy<Stream> { it.isSigns == mem.subForced }.thenBy { similarity(it.title ?: it.displayTitle, mem.subTitle) },
        )
        if (best != null) return best.index
    }
    if (subs.isEmpty()) return -1
    // The first preferred language that has subtitles, else any.
    val subLang = d.subLangs.firstOrNull { lang -> subs.any { sameLang(it.language, lang) } }
    val inLang = subs.filter { subLang == null || sameLang(it.language, subLang) }
    val full = inLang.firstOrNull { !it.isSigns }
    val signs = inLang.firstOrNull { it.isSigns }
    return when (d.subMode) {
        "None" -> -1
        "Always" -> (full ?: inLang.firstOrNull())?.index ?: -1
        "OnlyForced" -> signs?.index ?: -1
        // Subtitles when the audio isn't in the subtitle language, else only signs.
        "Smart" -> if (subLang != null && d.subLangs.none { sameLang(audioLang, it) }) (full ?: signs)?.index ?: -1 else signs?.index ?: -1
        else -> (subs.firstOrNull { it.isDefault && (subLang == null || sameLang(it.language, subLang)) } ?: subs.firstOrNull { it.isForced })?.index ?: -1
    }
}

/** What to remember after the user picked [audio] and [sub] (-1 = off). */
fun memoryFor(streams: List<Stream>, audio: Int?, sub: Int): TrackMemory {
    val a = streams.firstOrNull { it.isAudio && it.index == audio }
    val s = streams.firstOrNull { it.isSub && it.index == sub }
    return TrackMemory(a?.language, a?.title ?: a?.displayTitle, a?.channels ?: 0, s?.language, s?.title ?: s?.displayTitle, s?.isSigns == true, sub < 0)
}

/**
 * Offline progress against the server: upload ours unless the server saw a newer play (another
 * device watched since), in which case the other device wins.
 */
fun shouldUpload(serverLastPlayed: Instant?, ours: Instant) = serverLastPlayed == null || !serverLastPlayed.isAfter(ours)
