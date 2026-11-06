package com.sandovaljon.ytmp3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Carga perezosa de miniaturas de YouTube (i.ytimg.com) con caché en memoria. */
object ThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(url: String, imageView: ImageView) {
        imageView.tag = url
        cache.get(url)?.let { imageView.setImageBitmap(it); return }
        imageView.setImageResource(R.drawable.ic_music)
        scope.launch {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    withContext(Dispatchers.Main) {
                        if (imageView.tag == url) imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                // La miniatura es opcional; se queda el icono de música.
            }
        }
    }
}

class TrackAdapter(
    private var tracks: List<Track>,
    private val onToggle: (Track, Boolean) -> Unit
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.cbTrack)
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val uploader: TextView = view.findViewById(R.id.tvUploader)
        val duration: TextView = view.findViewById(R.id.tvDuration)
        val status: TextView = view.findViewById(R.id.tvStatus)
    }

    fun submit(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.title.text = track.title
        holder.uploader.text = track.uploader.ifBlank { "" }
        holder.duration.text = formatDuration(track.durationSec)
        holder.itemView.alpha = if (track.unavailable) 0.45f else 1f

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = track.selected
        holder.check.isEnabled = !track.unavailable
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (!track.unavailable) onToggle(track, checked)
        }

        if (track.unavailable) {
            holder.status.visibility = View.VISIBLE
            holder.status.text = "· no disponible"
            holder.status.setTextColor(holder.itemView.context.getColor(R.color.err))
        } else if (track.status != "pendiente") {
            holder.status.visibility = View.VISIBLE
            holder.status.text = when (track.status) {
                "ok" -> "✓ descargada"
                "error" -> "✕ error"
                "descargando" -> "descargando…"
                "cancelado" -> "cancelada"
                else -> track.status
            }
            holder.status.setTextColor(
                holder.itemView.context.getColor(
                    if (track.status == "ok") R.color.ok
                    else if (track.status == "error") R.color.err else R.color.warn
                )
            )
        } else {
            holder.status.visibility = View.GONE
        }
        ThumbnailLoader.load(track.thumbnailUrl, holder.thumb)
    }
}
