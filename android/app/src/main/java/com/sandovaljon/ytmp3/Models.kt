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
    val status: String = "pendiente", // pendiente | descargando | ok | error | cancelado
    val tab: String = "" // pestaña de origen en canales (Videos / Shorts); vacío en playlists
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
    val cookiesName: String? = null,
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
    /** Progreso general 0..100, igual que en la web original. */
    val overallPercent: Int
        get() {
            if (jobTotal <= 0) return 0
            val inTrack = currentPercent.coerceIn(0f, 100f) / 100f
            return (((jobDone + inTrack) / jobTotal) * 100).toInt().coerceIn(0, 100)
        }

    /** ¿La lista trae pestaña de Shorts (canal/perfil)? */
    val hasShorts: Boolean
        get() = tracks.any { isShortTab(it.tab) }

    /** Pistas visibles según el filtro (por defecto, sin Shorts). */
    val visibleTracks: List<Track>
        get() = when (listFilter) {
            FILTER_ONLY_SHORTS -> tracks.filter { isShortTab(it.tab) }
            FILTER_NO_SHORTS -> tracks.filter { !isShortTab(it.tab) }
            else -> tracks
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
