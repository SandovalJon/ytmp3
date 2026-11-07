package com.sandovaljon.ytmp3

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/**
 * Envoltorio del motor yt-dlp en el dispositivo.
 *
 * Librería: io.github.junkfood02.youtubedl-android:library:ffmpeg:0.18.1
 * (fork mantenido de yausername/youtubedl-android). Incluye yt-dlp
 * 2025.11.12, Python 3.8, ffmpeg y un runtime QuickJS (libqjs.so) que
 * yt-dlp necesita desde que abandonó su intérprete JS interno para
 * YouTube. La librería añade --ffmpeg-location automáticamente en cada
 * execute() y pone su directorio de binarios en el PATH del proceso,
 * así que no hay que pasar esas rutas a mano.
 */
object YtDlpEngine {
    private const val TAG = "YtDlpEngine"
    private var ready = false

    fun isReady(): Boolean = ready

    /** Inicializa yt-dlp + ffmpeg. Debe llamarse una vez, en segundo plano. */
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
            Log.i(TAG, "yt-dlp listo: ${YoutubeDL.getInstance().version(context)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar yt-dlp", e)
            false
        }
    }

    /** Actualiza yt-dlp a la última versión estable (descarga de GitHub). */
    fun update(context: Context): String {
        return try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            "yt-dlp actualizado: $status"
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo actualizar yt-dlp", e)
            "No se pudo actualizar yt-dlp: ${e.message}"
        }
    }

    fun version(context: Context): String =
        try { YoutubeDL.getInstance().version(context) ?: "?" } catch (e: Exception) { "?" }

    /** Error típico de motor desactualizado / bloqueo de YouTube. */
    fun isStaleError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return m.contains("sign in to confirm") || m.contains("not a bot") ||
            m.contains("http error 403") || m.contains("403") ||
            m.contains("requested format is not available") ||
            m.contains("no formats") || m.contains("no video formats") ||
            m.contains("unable to extract") || m.contains("failed to extract")
    }

    fun isEmbedError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return m.contains("thumbnail") || m.contains("embed") || m.contains("ffmpeg") ||
            m.contains("post-process") || m.contains("postprocessing")
    }

    /**
     * Carga la playlist con `yt-dlp --flat-playlist -J` (solo metadata,
     * sin descargar nada), igual que playlist_info() en app.py.
     * Acepta también la URL de un video suelto (se envuelve como lista de 1)
     * y URLs de canal/perfil (@handle, /channel/, /c/): las pestañas
     * (Videos / Shorts) se aplanan en una sola lista con el origen en
     * Track.tab, igual que en la web.
     */
    @Throws(Exception::class)
    fun loadPlaylist(url: String, cookiesFile: File?): PlaylistInfo {
        val request = YoutubeDLRequest(url)
            .addOption("--flat-playlist")
            .addOption("-J")
            .addOption("--no-warnings")
        if (cookiesFile != null && cookiesFile.isFile) {
            request.addOption("--cookies", cookiesFile.absolutePath)
        }
        val response = YoutubeDL.getInstance().execute(request)
        if (response.exitCode != 0) {
            throw RuntimeException(response.err.ifBlank { "yt-dlp devolvió un error." })
        }
        val data = JSONObject(response.out)
        val entries = data.optJSONArray("entries")
        val items = ArrayList<Track>()
        if (entries == null) {
            // Video suelto
            val single = trackFromJson(data, 1, "")
            if (single != null) items.add(single)
        } else {
            collectEntries(entries, "", items)
        }
        if (items.isEmpty()) {
            throw RuntimeException("La playlist está vacía o no es accesible (¿privada?).")
        }
        // Reindexar tras aplanar (los canales traen tabs Videos + Shorts).
        val reindexed = items.mapIndexed { i, t -> t.copy(index = i + 1) }
        // Los Shorts quedan desmarcados por defecto: solo canciones.
        val withDefaults = reindexed.map { t ->
            if (isShortTab(t.tab) && !t.unavailable) t.copy(selected = false) else t
        }
        val lowered = url.lowercase()
        val kind = if ("list=" in lowered) "playlist"
            else if (listOf("/@", "/channel/", "/c/", "/user/", "music.youtube.com/@").any { lowered.contains(it) }) "canal"
            else "playlist"
        return PlaylistInfo(
            title = data.optString("title")
                .ifBlank { data.optString("channel").ifBlank { "Playlist" } },
            uploader = data.optString("uploader").ifBlank { data.optString("channel") },
            items = withDefaults,
            kind = kind
        )
    }

    /** Aplana entries: si un elemento trae sus propios entries es una
     *  pestaña (Videos / Shorts) o sub-playlist; se recorre en profundidad. */
    private fun collectEntries(entries: org.json.JSONArray, tabName: String, out: ArrayList<Track>) {
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val nested = e.optJSONArray("entries")
            if (nested != null) {
                collectEntries(nested, e.optString("title").ifBlank { tabName }, out)
                continue
            }
            val track = trackFromJson(e, out.size + 1, tabName)
            if (track != null) out.add(track)
        }
    }

    private fun trackFromJson(e: JSONObject, index: Int, tab: String = ""): Track? {
        val vid = e.optString("id").ifBlank {
            e.optString("url").substringAfter("v=").substringBefore("&")
        }
        if (vid.isBlank()) return null
        val rawTitle = e.optString("title")
        val unavailable = rawTitle.isBlank() ||
            rawTitle.startsWith("[Private") || rawTitle.startsWith("[Deleted")
        return Track(
            index = index,
            id = vid,
            title = rawTitle.ifBlank { "(sin título)" },
            uploader = e.optString("uploader")
                .ifBlank { e.optString("channel") }
                .ifBlank { e.optString("channel_id") },
            durationSec = if (e.has("duration") && !e.isNull("duration"))
                e.optLong("duration") else null,
            thumbnailUrl = "https://i.ytimg.com/vi/$vid/mqdefault.jpg",
            unavailable = unavailable,
            tab = tab
        )
    }

    /**
     * Descarga una canción como MP3 con la calidad elegida.
     * Equivale al comando de download_job() en app.py:
     * yt-dlp -x --audio-format mp3 --audio-quality <calidad>
     *   --embed-metadata --embed-thumbnail --newline --no-colors
     *   --no-playlist -o "<tmpDir>/<NN> - %(title)s.%(ext)s" <url>
     * onProgress recibe el % de la canción actual (0..100).
     */
    @Throws(Exception::class)
    fun downloadTrack(
        track: Track,
        position: Int,
        quality: String,
        tmpDir: File,
        cookiesFile: File?,
        processId: String,
        onProgress: (Float) -> Unit,
        embed: Boolean = true
    ): File? {
        val url = "https://www.youtube.com/watch?v=${track.id}"
        val request = YoutubeDLRequest(url)
            .addOption("-f", "bestaudio/best")
            .addOption("-x")
            .addOption("--audio-format", "mp3")
            .addOption("--audio-quality", quality)
        if (embed) {
            request.addOption("--embed-metadata")
            request.addOption("--embed-thumbnail")
        }
        request.addOption("--newline")
            .addOption("--no-colors")
            .addOption("--no-playlist")
            .addOption("--windows-filenames")
        if (cookiesFile != null && cookiesFile.isFile) {
            request.addOption("--cookies", cookiesFile.absolutePath)
        }
        val outTemplate = File(tmpDir, "%02d - %%(title)s.%%(ext)s".format(position)).absolutePath
        request.addOption("-o", outTemplate)

        val response = YoutubeDL.getInstance()
            .execute(request, processId) { progress, _, _ -> onProgress(progress) }
        if (response.exitCode != 0) {
            // Antes solo se guardaban las últimas 3 líneas y luego solo
            // la última, así era imposible saber por qué fallaba.
            // Ahora se guarda el error completo (últimas 12 líneas).
            val errText = response.err.lines()
                .map { it.trim() }.filter { it.isNotEmpty() }
                .takeLast(12).joinToString("\n")
            throw RuntimeException(
                errText.ifBlank { "yt-dlp devolvió el código ${response.exitCode}." }
            )
        }
        // El archivo resultante es "<NN> - <título>.mp3" dentro de tmpDir.
        return tmpDir.listFiles()
            ?.filter { it.name.startsWith("%02d - ".format(position)) && it.extension.equals("mp3", true) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun destroyProcess(processId: String) {
        try { YoutubeDL.getInstance().destroyProcessById(processId) } catch (e: Exception) { /* noop */ }
    }
}
