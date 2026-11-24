package com.sandovaljon.ytmp3

/** Una canción de la playlist, tal como la devuelve yt-dlp --flat-playlist -J. */
data class Track(
    val index: Int,
    val id: String,
    val title: String,
    val uploader: String,
    val durationSec: Long?,
    val thumbnailUrl: String,
    val unavailable: Boolean,
    val selected: Boolean = !unavailable,
    val status: String = "pendiente", // pendiente | descargando | ok | error | cancelado | saltada
    val tab: String = "", // pestaña de origen en canales (Videos / Shorts); vacío en playlists
    // Criterios automáticos de la app de PC v1.3:
    val exists: Boolean = false,   // «ya la tienes»: su MP3 ya está en la carpeta destino
    val inRange: Boolean = true,   // duración dentro del rango elegido (1–7 min por defecto)
    val forced: Boolean = false    // marcada a mano pese a un criterio: se descarga igual
)

data class PlaylistInfo(
    val title: String,
    val uploader: String,
    val items: List<Track>,
    val kind: String = "playlist" // playlist | canal
)

/** Filtros de la lista (solo relevantes cuando hay pestaña de Shorts). */
const val FILTER_NO_SHORTS = "no-shorts"
const val FILTER_ALL = "all"
const val FILTER_ONLY_SHORTS = "only-shorts"

fun isShortTab(tab: String): Boolean = tab.contains("short", ignoreCase = true)

/** Estado observable de la app (lo expone DownloadManager como StateFlow). */
data class UiState(
    val engineReady: Boolean = false,
    val engineMessage: String = "Iniciando motor yt-dlp…",
    val loadingPlaylist: Boolean = false,
    val loadError: String? = null,
    val playlistTitle: String = "",
    val playlistUploader: String = "",
    val tracks: List<Track> = emptyList(),
    val quality: String = "320K",
    val listFilter: String = FILTER_NO_SHORTS,
    val searchQuery: String = "",
    val cookiesName: String? = null,
    val durMinMin: Double = 1.0, // rango de duración elegido, en minutos (PC v1.3: 1–7)
    val durMaxMin: Double = 7.0,
    val existingCount: Int = 0,
    val outCount: Int = 0,
    val jobActive: Boolean = false,
    val jobStatus: String = "",
    val jobDone: Int = 0,
    val jobFailed: Int = 0,
    val jobTotal: Int = 0,
    val currentTrack: String = "",
    val currentPercent: Float = 0f,
    val log: List<String> = emptyList(),
    val outDirDisplay: String = ""
) {
    /** Progreso general 0..100, igual que en la web original (suma de pistas activas en paralelo). */
    val overallPercent: Int
        get() {
            if (jobTotal <= 0) return 0
            val inTrack = currentPercent.coerceIn(0f, 300f) / 100f
            return (((jobDone + inTrack) / jobTotal) * 100).toInt().coerceIn(0, 100)
        }

    /** ¿La lista trae pestaña de Shorts (canal/perfil)? */
    val hasShorts: Boolean
        get() = tracks.any { isShortTab(it.tab) }

    /** Pistas visibles según el filtro de Shorts y el buscador de texto. */
    val visibleTracks: List<Track>
        get() {
            val byTab = when (listFilter) {
                FILTER_ONLY_SHORTS -> tracks.filter { isShortTab(it.tab) }
                FILTER_NO_SHORTS -> tracks.filter { !isShortTab(it.tab) }
                else -> tracks
            }
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) return byTab
            return byTab.filter { "${it.title} ${it.uploader}".lowercase().contains(q) }
        }

    /** Pistas que realmente se descargarían: visibles + seleccionadas + disponibles. */
    val downloadableTracks: List<Track>
        get() = visibleTracks.filter { it.selected && !it.unavailable }
}

fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Igual que title_key() en app.py: clave comparable de un título, sin
 * acentos, en minúsculas y solo letras/números. Sirve para comparar el
 * título de la playlist con el nombre del MP3 ya descargado.
 */
fun titleKey(title: String): String {
    val decomposed = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
    val noMarks = decomposed.replace(Regex("\\p{Mn}+"), "")
    val spaced = noMarks.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    return spaced.replace(Regex("\\s+"), " ")
}

/** Igual que duration_in_range() en app.py: sin duración conocida, fuera. */
fun durationInRange(durationSec: Long?, dminMin: Double, dmaxMin: Double): Boolean {
    if (durationSec == null) return false
    val d = durationSec.toDouble()
    return d >= dminMin * 60.0 && d <= dmaxMin * 60.0
}

/** ¿Entra sola en «Seleccionar todo»? Las que cumplen un criterio automático, no. */
fun isAutoSelectable(t: Track): Boolean = !t.unavailable && !t.exists && t.inRange

/** Minutos como en la web: 1, 7, 2.5 (sin ceros de más). */
fun formatMinutes(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else v.toString().trimEnd('0').trimEnd('.', ',')

/** Igual que sanitize_filename() en app.py. */
fun sanitizeFilename(name: String, fallback: String = "Playlist", maxLen: Int = 80): String {
    var n = name.trim().replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_")
    n = n.replace(Regex("\\s+"), " ").trim(' ', '.')
    if (n.isEmpty()) n = fallback
    return n.take(maxLen).trimEnd(' ', '.').ifEmpty { fallback }
}

fun isYoutubeUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return u.contains("youtube.com") || u.contains("youtu.be") ||
        u.contains("music.youtube.com")
}
