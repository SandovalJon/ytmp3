package com.sandovaljon.ytmp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sandovaljon.ytmp3.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * YTMusic → MP3 para Android.
 *
 * Replica la app web original (Flask + yt-dlp):
 *  1. Pega la URL de la playlist, elige calidad MP3 (128/192/256/320K).
 *  2. Carga la playlist (solo metadata), lista canciones con miniatura,
 *     duración y marca las no disponibles (privadas/eliminadas).
 *  3. Descarga las seleccionadas como "NN - Título.mp3" en
 *     Music/YouTube Music MP3/<playlist>/, con progreso, log y cancelación.
 *  4. Campo opcional cookies.txt para playlists privadas o aviso de bot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter

    private val qualities = listOf("320K", "256K", "192K", "128K")    // No llamar a getString() aquí: las propiedades se evalúan en el
    // constructor, antes de onCreate(), y eso cerraba la app al abrirla
    // con "System services not available to Activities before onCreate()".
    // Con lazy se evalúa en el primer uso, ya dentro de onCreate().
    private val qualityLabels by lazy {
        listOf(
            getString(R.string.q320), getString(R.string.q256),
            getString(R.string.q192), getString(R.string.q128)
        )
    }
    private val filterValues = listOf(FILTER_NO_SHORTS, FILTER_ALL, FILTER_ONLY_SHORTS)
    private val filterLabels by lazy {
        listOf(
            getString(R.string.f_songs), getString(R.string.f_all),
            getString(R.string.f_shorts)
        )
    }

    private val pickCookies = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dest = File(filesDir, "cookies.txt")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                DownloadManager.setCookies(dest)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cookies_loaded, dest.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No se pudo leer cookies.txt: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* El progreso también se ve dentro de la app. */ }

    private var pendingUpdate: AppUpdater.UpdateInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DownloadManager.initEngine(this)

        // Calidad: por defecto 320K, como en la web original.
        val spinnerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, qualityLabels
        )
        binding.spQuality.adapter = spinnerAdapter
        binding.spQuality.setSelection(0)
        binding.spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                DownloadManager.setQuality(qualities[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Filtro Solo canciones / Todo / Solo Shorts (solo visible con canales).
        binding.spFilter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, filterLabels
        )
        binding.spFilter.setSelection(0)
        binding.spFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (binding.spFilter.tag != "internal") {
                    DownloadManager.setListFilter(filterValues[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        adapter = TrackAdapter(emptyList()) { track, checked ->
            DownloadManager.toggleTrack(track.id, checked)
        }
        binding.rvTracks.layoutManager = LinearLayoutManager(this)
        binding.rvTracks.adapter = adapter

        // Buscador por título o artista (respeta el filtro de Shorts).
        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            DownloadManager.setSearchQuery(text?.toString().orEmpty())
        }

        binding.btnLoad.setOnClickListener {
            binding.etSearch.setText("")
            DownloadManager.loadPlaylist(this, binding.etUrl.text.toString())
        }
        binding.btnCookies.setOnClickListener { pickCookies.launch("text/*") }
        binding.btnUpdateEngine.setOnClickListener {
            binding.btnUpdateEngine.isEnabled = false
            DownloadManager.updateEngine(this) { msg ->
                binding.btnUpdateEngine.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnCheckUpdate.setOnClickListener {
            val pending = pendingUpdate
            if (pending != null) {
                askToDownloadUpdate(pending)
            } else {
                checkForAppUpdate(manual = true)
            }
        }
        binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            // Evita el bucle al actualizar el checkbox desde render().
            if (binding.cbSelectAll.tag != "internal") {
                DownloadManager.toggleAll(checked)
            }
        }
        binding.btnDownload.setOnClickListener {
            if (DownloadManager.downloadableTracks().isEmpty()) {
                Toast.makeText(this, R.string.err_no_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!DownloadManager.state.value.engineReady) {
                Toast.makeText(this, R.string.err_engine, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            askNotificationPermissionIfNeeded()
            DownloadManager.startDownload(this)
        }
        binding.btnCancel.setOnClickListener { DownloadManager.cancelDownload() }
        binding.btnRetry.setOnClickListener {
            val n = DownloadManager.retryFailed(this)
            if (n == 0) {
                Toast.makeText(this, R.string.no_errors_to_retry, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnOpenFolder.setOnClickListener { openMusicFolder() }

        // Si la app se abrió desde un enlace de YouTube, prellenar la URL.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { binding.etUrl.setText(it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.state.collect { render(it) }
            }
        }

        // Auto-chequeo silencioso al abrir (no molesta si no hay nada nuevo).
        checkForAppUpdate(manual = false)
    }

    /** Comprueba GitHub Releases; si manual=true avisa también cuando ya estás al día. */
    private fun checkForAppUpdate(manual: Boolean) {
        binding.tvUpdateStatus.text = getString(R.string.checking_update)
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val update = AppUpdater.check(this@MainActivity)
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    if (update != null) {
                        pendingUpdate = update
                        val current = AppUpdater.currentVersion(this@MainActivity)
                        binding.tvUpdateStatus.text =
                            getString(R.string.update_available, update.versionName, current)
                        binding.btnCheckUpdate.text =
                            getString(R.string.update_download, update.versionName)
                        if (manual) askToDownloadUpdate(update)
                    } else {
                        pendingUpdate = null
                        binding.btnCheckUpdate.text = getString(R.string.check_update)
                        if (manual) {
                            val current = AppUpdater.currentVersion(this@MainActivity)
                            binding.tvUpdateStatus.text = getString(R.string.app_up_to_date, current)
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.app_up_to_date, current),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            binding.tvUpdateStatus.text = ""
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    if (manual) {
                        binding.tvUpdateStatus.text = getString(R.string.update_failed, e.message ?: "?")
                    } else {
                        binding.tvUpdateStatus.text = ""
                    }
                }
            }
        }
    }

    private fun askToDownloadUpdate(update: AppUpdater.UpdateInfo) {
        val sizeMb = if (update.sizeBytes > 0) " (%.0f MB)".format(update.sizeBytes / 1048576.0) else ""
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available, update.versionName, AppUpdater.currentVersion(this)))
            .setMessage((update.notes.ifBlank { update.assetName } + sizeMb).take(800))
            .setPositiveButton(getString(R.string.update_download, update.versionName)) { _, _ ->
                downloadAndInstallUpdate(update)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstallUpdate(update: AppUpdater.UpdateInfo) {
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apk = AppUpdater.download(this@MainActivity, update.apkUrl) { pct ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.tvUpdateStatus.text = getString(R.string.update_downloading, pct)
                    }
                }
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvUpdateStatus.text = getString(R.string.update_install_note)
                    val ok = AppUpdater.install(this@MainActivity, apk)
                    if (!ok) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_install_note),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvUpdateStatus.text = getString(R.string.update_failed, e.message ?: "?")
                }
            }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openMusicFolder() {
        val state = DownloadManager.state.value
        val folder = sanitizeFilename(state.playlistTitle.ifBlank { "Playlist" })
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Music/YouTube Music MP3/$folder"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Los MP3 están en: ${state.outDirDisplay}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun render(state: UiState) {
        binding.tvEngineStatus.text = state.engineMessage

        binding.progressLoad.visibility = if (state.loadingPlaylist) View.VISIBLE else View.GONE
        binding.btnLoad.isEnabled = !state.loadingPlaylist
        binding.btnLoad.text = if (state.loadingPlaylist) {
            getString(R.string.loading_playlist)
        } else {
            getString(R.string.load_playlist)
        }
        if (state.loadError != null) {
            binding.tvLoadError.visibility = View.VISIBLE
            binding.tvLoadError.text = state.loadError
        } else {
            binding.tvLoadError.visibility = View.GONE
        }

        binding.tvCookies.text = state.cookiesName?.let {
            getString(R.string.cookies_loaded, it)
        } ?: getString(R.string.cookies_none)

        // Paso 2: lista de canciones
        if (state.tracks.isNotEmpty()) {
            binding.cardTracks.visibility = View.VISIBLE
            binding.tvPlaylistTitle.text = state.playlistTitle
            val shown = state.visibleTracks
            binding.tvPlaylistMeta.text = listOf(
                state.playlistUploader,
                "${shown.size} canciones"
            ).filter { it.isNotBlank() }.joinToString(" · ")
            // Filtro de Shorts: solo aparece si el canal trae esa pestaña.
            binding.rowFilter.visibility = if (state.hasShorts) View.VISIBLE else View.GONE
            val filterPos = when (state.listFilter) {
                FILTER_ALL -> 1
                FILTER_ONLY_SHORTS -> 2
                else -> 0
            }
            if (binding.spFilter.selectedItemPosition != filterPos) {
                binding.spFilter.tag = "internal"
                binding.spFilter.setSelection(filterPos)
                binding.spFilter.tag = null
            }
            adapter.submit(shown)
            val selectable = shown.filter { !it.unavailable }
            val selected = shown.filter { it.selected && !it.unavailable }
            val selSecs = state.downloadableTracks.mapNotNull { it.durationSec }.sum()
            binding.tvSelCount.text = getString(
                R.string.selected_count, selected.size, shown.size
            ) + " · " + formatDuration(selSecs)
            binding.cbSelectAll.tag = "internal"
            binding.cbSelectAll.isChecked = selectable.isNotEmpty() && selectable.all { it.selected }
            binding.cbSelectAll.tag = null
            binding.btnDownload.isEnabled = selected.isNotEmpty() && !state.jobActive
        } else {
            binding.cardTracks.visibility = View.GONE
        }

        // Paso 3: descargas
        if (state.jobTotal > 0 || state.jobActive || state.log.isNotEmpty()) {
            binding.cardJobs.visibility = View.VISIBLE
            binding.tvJobTitle.text = state.playlistTitle
            binding.tvJobStatus.text = state.jobStatus
            binding.tvJobStatus.setTextColor(
                getColor(
                    when (state.jobStatus) {
                        "completado" -> R.color.ok
                        "descargando" -> R.color.warn
                        "cancelado" -> R.color.muted
                        "completado con errores" -> R.color.warn
                        else -> R.color.err
                    }
                )
            )
            binding.progressOverall.progress = state.overallPercent
            binding.tvJobMeta.text = getString(
                R.string.songs_done, state.jobDone, state.jobTotal, state.jobFailed
            ) + " · " + state.outDirDisplay
            binding.tvJobCurrent.text = if (state.currentTrack.isNotBlank()) {
                "${state.currentTrack} — ${state.currentPercent.toInt()}%"
            } else ""
            val failedTracks = state.tracks.filter { it.status == "error" || it.status == "cancelado" }
            val errorsOnly = state.tracks.filter { it.status == "error" }
            if (!state.jobActive && failedTracks.isNotEmpty()) {
                binding.tvJobFailed.visibility = View.VISIBLE
                binding.tvJobFailed.text = "Con error (${failedTracks.size}):\n" +
                    failedTracks.joinToString("\n") { "• ${it.title}" }
            } else {
                binding.tvJobFailed.visibility = View.GONE
            }
            binding.btnRetry.visibility =
                if (!state.jobActive && errorsOnly.isNotEmpty()) View.VISIBLE else View.GONE
            if (errorsOnly.isNotEmpty()) {
                binding.btnRetry.text = getString(R.string.retry_failed, errorsOnly.size)
            }
            binding.tvJobLog.text = state.log.takeLast(40).joinToString("\n")
            binding.btnCancel.visibility = if (state.jobActive) View.VISIBLE else View.GONE
        } else {
            binding.cardJobs.visibility = View.GONE
        }
    }
}
