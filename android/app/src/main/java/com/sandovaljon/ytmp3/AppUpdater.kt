package com.sandovaljon.ytmp3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-actualización sin pasar por Play Store ni descargar a mano.
 *
 * - Comprueba `https://api.github.com/repos/SandovalJon/ytmp3/releases/latest`.
 * - Compara `tag_name` (p. ej. v1.0.6) con la versionName instalada.
 * - Descarga el primer asset `.apk` a getExternalFilesDir(DOWNLOADS)
 *   y lo instala con FileProvider + ACTION_VIEW.
 *
 * El motor yt-dlp ya se actualiza solo sin APK (YtDlpEngine.update);
 * esto solo hace falta para cambios de código nativo (UI, fixes).
 */
object AppUpdater {
    const val REPO = "SandovalJon/ytmp3"
    private const val API_LATEST = "https://api.github.com/repos/SandovalJon/ytmp3/releases/latest"

    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val apkUrl: String,
        val assetName: String,
        val sizeBytes: Long,
        val notes: String,
    )

    fun currentVersion(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    /** Devuelve UpdateInfo si hay versión más nueva, null si estás al día o no se pudo comprobar. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = httpGetJson(API_LATEST) ?: return@withContext null
        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        var assetName = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", true) && url.isNotBlank()) {
                apkUrl = url
                assetName = name
                size = a.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isNullOrBlank()) return@withContext null
        val latest = tag.trimStart('v', 'V')
        return@withContext if (isNewer(latest, currentVersion(context))) {
            UpdateInfo(tag, latest, apkUrl, assetName, size, json.optString("body").orEmpty())
        } else {
            null
        }
    }

    /** Compara versiones tipo 1.0.6 > 1.0.5. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().trimStart('v', 'V')
            .split(".", "-", "_").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = parts(latest)
        val c = parts(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun httpGetJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "YTMusic-MP3-Android")
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Descarga el APK con progreso 0..100. Lanza excepción si falla. */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            dir.mkdirs()
            val out = File(dir, "ytmp3-update.apk")
            if (out.exists()) out.delete()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "YTMusic-MP3-Android")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw RuntimeException("Descarga falló: HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                onProgress(100)
                out
            } catch (e: Exception) {
                out.delete()
                throw e
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Instala el APK descargado.
     * @return false si falta el permiso de orígenes desconocidos (se abrió Ajustes).
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // noop
            }
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
