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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /** Descargas simultáneas, igual que YTMP3_PARALLEL=3 en la web. */
    private const val MAX_PARALLEL = 3

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cancelled = false
    @Volatile private var cookiesFile: File? = null
    /** IDs de proceso yt-dlp en curso (uno por pista, para cancelar el árbol completo). */
    private val activeProcessIds = ConcurrentHashMap.newKeySet<String>()

    fun setCookies(file: File?) {
        cookiesFile = file
        _state.update {
            it.copy(cookiesName = file?.name)
        }
    }

    fun setQuality(quality: String) {
        _state.update { it.copy(quality = quality) }
    }

    fun setListFilter(filter: String) {
        _state.update { it.copy(listFilter = filter) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
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
                        listFilter = FILTER_NO_SHORTS,
                        searchQuery = "",
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
        // Solo afecta a lo visible (respeta el filtro y el buscador).
        val visibleIds = _state.value.visibleTracks.map { it.id }.toSet()
        _state.update {
            it.copy(tracks = it.tracks.map { t ->
                if (t.unavailable || t.id !in visibleIds) t else t.copy(selected = selected)
            })
        }
    }

    fun selectedTracks(): List<Track> = _state.value.tracks.filter { it.selected && !it.unavailable }

    /** Lo que realmente se descarga: visibles + seleccionadas + disponibles. */
    fun downloadableTracks(): List<Track> = _state.value.downloadableTracks

    /** Relanza solo las pistas con error (paridad con /retry de la web). Devuelve cuántas. */
    fun retryFailed(context: Context): Int {
        val cur = _state.value
        if (cur.jobActive) return 0
        val ids = cur.tracks.filter { it.status == "error" }.map { it.id }.toSet()
        if (ids.isEmpty()) return 0
        pendingRetryIds = ids
        _state.update { st ->
            st.copy(tracks = st.tracks.map { if (it.id in ids) it.copy(status = "pendiente") else it })
        }
        launchService(context)
        return ids.size
    }

    private var pendingRetryIds: Set<String>? = null

    /** Arranca el servicio en primer plano; el servicio ejecuta runDownload(). */
    fun startDownload(context: Context) {
        val selected = downloadableTracks()
        if (selected.isEmpty() || _state.value.jobActive) return
        launchService(context)
    }

    private fun launchService(context: Context) {
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
        activeProcessIds.forEach { YtDlpEngine.destroyProcess(it) }
        _state.update { it.copy(jobStatus = "cancelando…") }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, DownloadService::class.java))
    }

    /** Bucle de descarga en paralelo (hasta MAX_PARALLEL pistas a la vez),
     *  ejecutado por DownloadService. El progreso general suma las pistas
     *  activas, igual que en la web. */
    suspend fun runDownload(context: Context) {
        val snapshot = _state.value
        val retryIds = pendingRetryIds
        pendingRetryIds = null
        // En reintento: solo las fallidas (con su número original para no duplicar archivos).
        val selected = if (retryIds != null) {
            snapshot.tracks.filter { it.id in retryIds && it.selected && !it.unavailable }
        } else {
            snapshot.downloadableTracks
        }
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
        log("Carpeta de destino: $outDisplay ($MAX_PARALLEL en paralelo)")
        log("Motor yt-dlp: ${YtDlpEngine.version(context)}")

        val titles = selected.associate { it.id to it.title }
        val activePercents = ConcurrentHashMap<String, Float>()
        val doneCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val updatedEngine = AtomicBoolean(false)

        fun publishAggregate() {
            val sum = activePercents.values.sumOf { it.toDouble() }.toFloat()
            val names = activePercents.keys.mapNotNull { titles[it] }.joinToString(" · ")
            _state.update {
                it.copy(
                    currentPercent = sum,
                    currentTrack = if (names.length > 140) names.take(140) + "…" else names
                )
            }
        }

        suspend fun downloadOne(track: Track, number: Int, total: Int) {
            if (cancelled) return
            val pid = "$PROCESS_ID-${track.id}"
            activeProcessIds.add(pid)
            setTrackStatus(track.id, "descargando")
            log("[$number/$total] Descargando: ${track.title}")
            activePercents[track.id] = 0f
            publishAggregate()
            try {
                fun attempt(embed: Boolean) {
                    val file = YtDlpEngine.downloadTrack(
                        track = track,
                        position = number,
                        quality = quality,
                        tmpDir = tmpDir,
                        cookiesFile = cookiesFile,
                        processId = pid,
                        onProgress = { p ->
                            activePercents[track.id] = p
                            publishAggregate()
                        },
                        embed = embed
                    )
                    if (file == null || !file.exists()) {
                        throw RuntimeException("No se generó el archivo MP3.")
                    }
                    publishToMusic(context, file, folderName, track)
                    file.delete()
                }

                try {
                    attempt(embed = true)
                } catch (e: Exception) {
                    if (cancelled) throw e
                    val msg = e.message ?: ""
                    if (YtDlpEngine.isStaleError(msg) && updatedEngine.compareAndSet(false, true)) {
                        // Motor desactualizado / bloqueo de YouTube:
                        // actualizar yt-dlp y reintentar una vez (una sola vez por trabajo).
                        log("YouTube bloqueó la descarga, actualizando yt-dlp…")
                        val updateMsg = YtDlpEngine.update(context)
                        log(updateMsg)
                        _state.update { it.copy(engineMessage = updateMsg) }
                        activePercents[track.id] = 0f
                        publishAggregate()
                        attempt(embed = true)
                    } else if (YtDlpEngine.isEmbedError(msg)) {
                        // Falló al incrustar portada/metadatos:
                        // reintentar sin incrustar, el MP3 sí se genera.
                        log("Reintentando sin incrustar portada/metadatos…")
                        attempt(embed = false)
                    } else {
                        throw e
                    }
                }
                if (cancelled) {
                    setTrackStatus(track.id, "cancelado")
                    log("Cancelado por el usuario en: ${track.title}")
                    return
                }
                doneCount.incrementAndGet()
                setTrackStatus(track.id, "ok")
                log("OK: ${track.title}")
            } catch (e: Exception) {
                if (cancelled) {
                    setTrackStatus(track.id, "cancelado")
                    log("Cancelado por el usuario en: ${track.title}")
                    return
                }
                failedCount.incrementAndGet()
                setTrackStatus(track.id, "error")
                Log.e(TAG, "Error con ${track.title}", e)
                // Mostrar el error completo, no solo la última línea.
                val fullMsg = (e.message ?: "No se pudo descargar.").trim()
                log("ERROR: ${track.title}")
                fullMsg.lines().forEach { line -> if (line.isNotBlank()) log(line) }
                if (YtDlpEngine.isStaleError(fullMsg)) {
                    log("Si el error persiste, carga un cookies.txt o pulsa Actualizar motor yt-dlp.")
                }
            } finally {
                activePercents.remove(track.id)
                activeProcessIds.remove(pid)
                publishAggregate()
                _state.update { it.copy(jobDone = doneCount.get(), jobFailed = failedCount.get()) }
            }
        }

        try {
            coroutineScope {
                val semaphore = Semaphore(MAX_PARALLEL)
                selected.map { track ->
                    async { semaphore.withPermit { downloadOne(track, track.index, selected.size) } }
                }.awaitAll()
            }
        } finally {
            tmpDir.deleteRecursively()
            val done = doneCount.get()
            val failed = failedCount.get()
            val status = when {
                cancelled -> "cancelado"
                failed == 0 -> "completado"
                else -> "completado con errores"
            }
            _state.update {
                it.copy(
                    jobActive = false, jobStatus = status, jobDone = done,
                    jobFailed = failed, currentTrack = "", currentPercent = 0f
                )
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
