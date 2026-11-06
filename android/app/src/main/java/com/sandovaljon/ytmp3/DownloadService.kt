package com.sandovaljon.ytmp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene viva la descarga aunque la
 * app pase a segundo plano, con notificación de progreso.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_START = "com.sandovaljon.ytmp3.START"
        const val ACTION_CANCEL = "com.sandovaljon.ytmp3.CANCEL"
        const val CHANNEL_ID = "ytmp3-downloads"
        const val NOTIF_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                DownloadManager.cancelDownload()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("Iniciando descarga…", 0))
                serviceScope.launch {
                    // Refresca la notificación con el progreso general.
                    val notifJob = launch {
                        DownloadManager.state.collect { state ->
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            val text = if (state.currentTrack.isNotBlank()) {
                                "${state.jobDone}/${state.jobTotal} · ${state.currentTrack}"
                            } else {
                                "Descargando música"
                            }
                            nm.notify(NOTIF_ID, buildNotification(text, state.overallPercent))
                        }
                    }
                    try {
                        DownloadManager.runDownload(applicationContext)
                    } finally {
                        notifJob.cancel()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_music)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
