package com.sandovaljon.ytmp3

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Estado y lógica de descarga, compartidos por MainActivity y
 * DownloadService. Equivale a los jobs de app.py (new_job /
 * download_job), adaptado a Android:
 *
 * - Los MP3 se descargan primero a una carpeta temporal privada
 *   (yt-dlp no puede escribir directamente en MediaStore) y al
 *   terminar cada canción se publican en
 *   Music/YouTube Music MP3/<nombre de la playlist>/NN - Título.mp3
 *   mediante MediaStore (Android 10+). En Android 9 o anterior se
 *   escriben directamente en esa misma ruta pública.
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PROCESS_ID = "ytmp3-download"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cancelled = false
    @Volatile private var cookiesFile: File? = null

    fun setCookies(file: File?) {
        cookiesFile = file
        _state.update {
            it.copy(cookiesName = file?.name)
        }
    }

    fun setQuality(quality: String) {
        _state.update { it.copy(quality = quality) }
    }

    fun initEngine(context: Context) {
        scope.launch {
            val ok = YtDlpEngine.init(context)
            _state.update {
                it.copy(
                    engineReady = ok,
                    engineMessage = if (ok) {
                        "Motor yt-dlp listo (yt-dlp ${YtDlpEngine.version(context)})."
                    } else {
                        "No se pudo iniciar el motor yt-dlp. Reintenta al abrir la app."
                    }
                )
            }
        }
    }

    fun updateEngine(context: Context, onDone: (String) -> Unit) {
        scope.launch {
            val msg = YtDlpEngine.update(context)
            _state.update { it.copy(engineMessage = msg) }
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    fun loadPlaylist(context: Context, url: String) {
        val cleanUrl = url.trim()
        if (!isYoutubeUrl(cleanUrl)) {
            _state.update { it.copy(loadError = "Introduce una URL válida de YouTube o YouTube Music.") }
            return
        }
        scope.launch {
            _state.update { it.copy(loadingPlaylist = true, loadError = null) }
            try {
                val info = YtDlpEngine.loadPlaylist(cleanUrl, cookiesFile)
                _state.update {
                    it.copy(
                        loadingPlaylist = false,
                        playlistTitle = info.title,
                        playlistUploader = info.uploader,
                        tracks = info.items,
                        outDirDisplay = "Music/YouTube Music MP3/${sanitizeFilename(info.title)}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar playlist", e)
                var msg = e.message ?: "No se pudo leer la playlist."
                if (msg.contains("Sign in", true) || msg.contains("cookies", true)) {
                    msg += "\nLa playlist puede requerir inicio de sesión: " +
                        "exporta cookies.txt de tu navegador y usa el botón de cookies."
                }
                _state.update { it.copy(loadingPlaylist = false, loadError = msg) }
            }
        }
    }

    fun toggleTrack(id: String, selected: Boolean) {
        _state.update {
            it.copy(tracks = it.tracks.map { t -> if (t.id == id) t.copy(selected = selected) else t })
        }
    }

    fun toggleAll(selected: Boolean) {
        _state.update {
            it.copy(tracks = it.tracks.map { t ->
                if (t.unavailable) t else t.copy(selected = selected)
            })
        }
    }

    fun selectedTracks(): List<Track> = _state.value.tracks.filter { it.selected && !it.unavailable }

    /** Arranca el servicio en primer plano; el servicio ejecuta runDownload(). */
    fun startDownload(context: Context) {
        val selected = selectedTracks()
        if (selected.isEmpty() || _state.value.jobActive) return
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelDownload() {
        cancelled = true
        YtDlpEngine.destroyProcess(PROCESS_ID)
        _state.update { it.copy(jobStatus = "cancelando…") }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, DownloadService::class.java))
    }

    /** Bucle de descarga, ejecutado por DownloadService. */
    suspend fun runDownload(context: Context) {
        val snapshot = _state.value
        val selected = snapshot.tracks.filter { it.selected && !it.unavailable }
        if (selected.isEmpty()) return
        cancelled = false
        val playlistTitle = snapshot.playlistTitle.ifBlank { "Playlist" }
        val quality = snapshot.quality
        val folderName = sanitizeFilename(playlistTitle)
        val tmpDir = File(context.cacheDir, "ytmp3-downloads").apply {
            deleteRecursively(); mkdirs()
        }
        val outDisplay = "Music/YouTube Music MP3/$folderName"
        val logLines = ArrayList<String>()
        fun log(line: String) {
            synchronized(logLines) {
                logLines.add(line)
                if (logLines.size > 400) logLines.removeAt(0)
            }
            _state.update { it.copy(log = logLines.toList()) }
        }
        fun setTrackStatus(id: String, status: String) {
            _state.update {
                it.copy(tracks = it.tracks.map { t -> if (t.id == id) t.copy(status = status) else t })
            }
        }

        _state.update {
            it.copy(
                jobActive = true, jobStatus = "descargando", jobDone = 0,
                jobFailed = 0, jobTotal = selected.size, currentTrack = "",
                currentPercent = 0f, log = emptyList(), outDirDisplay = outDisplay
            )
        }
        log("Carpeta de destino: $outDisplay")
        log("Motor yt-dlp: ${YtDlpEngine.version(context)}")
        var done = 0
        var failed = 0
        var updatedThisRun = false
        try {
            for ((pos, track) in selected.withIndex()) {
                if (cancelled) break
                val number = pos + 1
                _state.update { it.copy(currentTrack = track.title, currentPercent = 0f) }
                setTrackStatus(track.id, "descargando")
                log("[$number/${selected.size}] Descargando: ${track.title}")

                fun attempt(embed: Boolean): File? {
                    val file = YtDlpEngine.downloadTrack(
                        track = track,
                        position = number,
                        quality = quality,
                        tmpDir = tmpDir,
                        cookiesFile = cookiesFile,
                        processId = PROCESS_ID,
                        onProgress = { p ->
                            _state.update { it.copy(currentPercent = p) }
                        },
                        embed = embed
                    )
                    if (file == null || !file.exists()) {
                        throw RuntimeException("No se generó el archivo MP3.")
                    }
                    return file
                }

                try {
                    var file: File? = null
                    try {
                        file = attempt(embed = true)
                    } catch (e: Exception) {
                        if (cancelled) throw e
                        val msg = e.message ?: ""
                        if (YtDlpEngine.isStaleError(msg) && !updatedThisRun) {
                            // Motor desactualizado / bloqueo de YouTube:
                            // actualizar yt-dlp y reintentar una vez.
                            log("YouTube bloqueó la descarga, actualizando yt-dlp…")
                            val updateMsg = YtDlpEngine.update(context)
                            log(updateMsg)
                            _state.update { it.copy(engineMessage = updateMsg) }
                            updatedThisRun = true
                            _state.update { it.copy(currentPercent = 0f) }
                            file = attempt(embed = true)
                        } else if (YtDlpEngine.isEmbedError(msg)) {
                            // Falló al incrustar portada/metadatos:
                            // reintentar sin incrustar, el MP3 sí se genera.
                            log("Reintentando sin incrustar portada/metadatos…")
                            file = attempt(embed = false)
                        } else {
                            throw e
                        }
                    }
                    val downloaded = file
                    if (cancelled) {
                        setTrackStatus(track.id, "cancelado")
                        log("Cancelado por el usuario en: ${track.title}")
                        break
                    }
                    if (downloaded == null || !downloaded.exists()) {
                        throw RuntimeException("No se generó el archivo MP3.")
                    }
                    publishToMusic(context, downloaded, folderName, track)
                    downloaded.delete()
                    done++
                    setTrackStatus(track.id, "ok")
                    log("OK: ${track.title}")
                } catch (e: Exception) {
                    if (cancelled) {
                        setTrackStatus(track.id, "cancelado")
                        log("Cancelado por el usuario en: ${track.title}")
                        break
                    }
                    failed++
                    setTrackStatus(track.id, "error")
                    Log.e(TAG, "Error con ${track.title}", e)
                    // Mostrar el error completo, no solo la última línea.
                    val fullMsg = (e.message ?: "No se pudo descargar.").trim()
                    log("ERROR: ${track.title}")
                    fullMsg.lines().forEach { line -> if (line.isNotBlank()) log(line) }
                    if (YtDlpEngine.isStaleError(fullMsg)) {
                        log("Si el error persiste, carga un cookies.txt o pulsa Actualizar motor yt-dlp.")
                    }
                }
                _state.update { it.copy(jobDone = done, jobFailed = failed, currentPercent = 100f) }
            }
        } finally {
            tmpDir.deleteRecursively()
            val status = when {
                cancelled -> "cancelado"
                failed == 0 -> "completado"
                else -> "completado con errores"
            }
            _state.update {
                it.copy(jobActive = false, jobStatus = status, currentTrack = "", currentPercent = 0f)
            }
        }
    }

    /**
     * Publica un MP3 terminado en la colección de audio del sistema:
     * Music/YouTube Music MP3/<playlist>/NN - Título.mp3
     */
    private fun publishToMusic(context: Context, file: File, folderName: String, track: Track) {
        val relativePath = "Music/YouTube Music MP3/$folderName/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("No se pudo crear el archivo en la biblioteca de música.")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw RuntimeException("No se pudo escribir el archivo MP3.")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "YouTube Music MP3/$folderName"
            ).apply { mkdirs() }
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("audio/mpeg"), null)
        }
    }
}
