package com.lull.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads and caches album / embedded artwork off the main thread. */
object ArtLoader {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxMem / 8) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun key(item: AudioItem) = if (item.albumId > 0) "a${item.albumId}" else "t${item.id}"

    fun cached(item: AudioItem): Bitmap? = cache.get(key(item))

    suspend fun load(context: Context, item: AudioItem, px: Int): Bitmap? {
        val k = key(item)
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            // Prefer the track's embedded/album thumbnail; fall back to the album-art URI.
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.uri != android.net.Uri.EMPTY)
                    context.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
                else null
            }.getOrNull()
            primary ?: runCatching {
                item.artworkUri?.let { art ->
                    context.contentResolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }
        if (bmp != null) cache.put(k, bmp)
        return bmp
    }
}
