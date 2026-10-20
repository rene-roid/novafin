package xyz.yuuki.novafin

import java.time.Instant

/** Fallbacks when nothing is remembered: app settings, then the server user's preferences. */
data class TrackDefaults(val audioLang: String?, val subLang: String?, val subMode: String)

/** Word overlap between two titles, 0..1. */
private fun similarity(a: String?, b: String?): Double {
    val x = a?.lowercase()?.split(Regex("\\W+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val y = b?.lowercase()?.split(Regex("\\W+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    if (x.isEmpty() || y.isEmpty()) return 0.0
    return (x intersect y).size.toDouble() / (x union y).size
}

private fun sameLang(a: String?, b: String?) = a != null && b != null && a.equals(b, true)

/** Audio stream index to play: the remembered language, then the default language, then the file's default. */
fun pickAudio(streams: List<Stream>, mem: TrackMemory?, d: TrackDefaults): Int? {
    val audio = streams.filter { it.isAudio }
    if (audio.isEmpty()) return null
    mem?.audioLang?.let { lang ->
        audio.filter { sameLang(it.language, lang) }
            .maxWithOrNull(compareBy<Stream> { similarity(it.title ?: it.displayTitle, mem.audioTitle) }.thenBy { -kotlin.math.abs(it.channels - mem.audioChannels) })
            ?.let { return it.index }
    }
    d.audioLang?.let { lang -> audio.firstOrNull { sameLang(it.language, lang) }?.let { return it.index } }
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
    val inLang = subs.filter { d.subLang == null || sameLang(it.language, d.subLang) }
    val full = inLang.firstOrNull { !it.isSigns }
    val signs = inLang.firstOrNull { it.isSigns }
    return when (d.subMode) {
        "None" -> -1
        "Always" -> (full ?: inLang.firstOrNull())?.index ?: -1
        "OnlyForced" -> signs?.index ?: -1
        // Subtitles when the audio isn't in the subtitle language, else only signs.
        "Smart" -> if (d.subLang != null && !sameLang(audioLang, d.subLang)) (full ?: signs)?.index ?: -1 else signs?.index ?: -1
        else -> (subs.firstOrNull { it.isDefault && (d.subLang == null || sameLang(it.language, d.subLang)) } ?: subs.firstOrNull { it.isForced })?.index ?: -1
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
