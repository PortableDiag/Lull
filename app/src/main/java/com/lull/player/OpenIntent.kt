package com.lull.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Playback started by another app — a file manager's "Open with", or the share sheet.
 *
 * The goal is that opening one track from Sift gives you its whole folder to page through, and
 * that this works *without* Lull holding media permission, because the read grant on the intent
 * covers every uri attached to it. Three routes, tried in order:
 *
 *  1. **`ClipData`** — the launching app already knows the folder and has attached the siblings.
 *     No permission, no lookup, and it is the only route that works for a `.nomedia` folder.
 *     (Sift does this for audio as of 1.10; other file managers would have to adopt it.)
 *  2. **The library** — resolve the opened file to a real path and take every track the library
 *     already holds from the same directory. Needs media permission, which we may not have.
 *  3. **The single file**, which always works.
 *
 * Route 2's path resolution is the awkward part: a file manager typically hands over its own
 * `content://<their.app>.fileprovider/...` uri, which answers neither a MediaStore id nor a DATA
 * column. The descriptor it opens still points at the real file, though, and `/proc/self/fd/N` is
 * a symlink to it — so the folder is recoverable even when the uri tells us nothing.
 */
object OpenIntent {

    private const val TAG = "LullOpen"

    /** What an external launch resolved to. [folderResolved] is false when route 3 was taken. */
    data class Opened(
        val items: List<MediaItem>,
        val startIndex: Int,
        val folderResolved: Boolean
    )

    fun isExternal(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data != null
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
        else -> false
    }

    fun resolve(context: Context, intent: Intent, library: List<AudioItem>): Opened? {
        val uris = incomingUris(intent)
        if (uris.isEmpty()) return null

        // A multi-share is already a list — the sender chose it, so it is the queue.
        if (uris.size > 1) {
            return Opened(uris.map { mediaItem(context, it, library) }, 0, true)
        }

        val uri = uris.first()
        takePersistableGrant(context, intent, uri)

        clipDataQueue(context, intent, uri, library)?.let { return it }
        folderQueue(context, uri, library)?.let { return it }

        return Opened(listOf(mediaItem(context, uri, library)), 0, false)
    }

    // ---------------- Route 1: the folder the launching app handed over ----------------

    private fun clipDataQueue(
        context: Context, intent: Intent, uri: Uri, library: List<AudioItem>
    ): Opened? {
        val clip = intent.clipData ?: return null
        if (clip.itemCount < 2) return null

        val items = ArrayList<MediaItem>(clip.itemCount)
        var startIndex = -1
        for (i in 0 until clip.itemCount) {
            val u = clip.getItemAt(i).uri ?: continue
            if (u == uri) startIndex = items.size
            val label = clip.getItemAt(i).text?.toString()?.takeIf { it.isNotBlank() }
            items.add(mediaItem(context, u, library, fallbackTitle = label))
        }
        // Without the opened file in the list we would silently start on something else.
        if (items.size < 2 || startIndex < 0) return null
        Log.d(TAG, "clipdata queue size=${items.size} start=$startIndex")
        return Opened(items, startIndex, true)
    }

    // ---------------- Route 2: the folder, out of the library we already loaded ----------------

    private fun folderQueue(context: Context, uri: Uri, library: List<AudioItem>): Opened? {
        if (library.isEmpty()) return null

        // The opened row, if the library already holds it — by MediaStore id, else by path.
        val path = resolvePath(context, uri)
        val byId = mediaStoreId(context, uri)?.let { id -> library.firstOrNull { it.id == id } }
        val opened = byId ?: path?.let { p -> library.firstOrNull { it.path == p } }

        val dir = opened?.folderPath ?: path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        if (dir.isNullOrEmpty()) { Log.d(TAG, "no folder for $uri (path=$path)"); return null }

        val siblings = library.filter { it.folderPath == dir }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        if (siblings.size < 2) return null

        val startIndex = when {
            opened != null -> siblings.indexOfFirst { it.id == opened.id }
            else -> siblings.indexOfFirst { it.path == path }
        }
        if (startIndex < 0) return null

        Log.d(TAG, "folder queue $dir size=${siblings.size} start=$startIndex")
        return Opened(siblings.map { it.toMediaItem() }, startIndex, true)
    }

    // ---------------- Uri plumbing ----------------

    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(intent.data)
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(name, T::class.java) else getParcelableExtra(name) as? T

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableArrayListExtra(name, T::class.java) else getParcelableArrayListExtra(name)

    /**
     * Keeps the read grant past this launch where the sender allowed it. Best-effort: most
     * `Open with` intents are one-shot grants and this throws, which is not a failure.
     */
    private fun takePersistableGrant(context: Context, intent: Intent, uri: Uri) {
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun mediaItem(
        context: Context, uri: Uri, library: List<AudioItem>, fallbackTitle: String? = null
    ): MediaItem {
        // Prefer the library's own row: it carries artist, album and artwork, which a bare uri
        // does not, so a file opened from outside looks the same as one opened from inside.
        mediaStoreId(context, uri)?.let { id ->
            library.firstOrNull { it.id == id }?.let { return it.toMediaItem() }
        }
        val title = fallbackTitle ?: displayName(context, uri) ?: uri.lastPathSegment ?: "Audio"
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** The MediaStore audio id behind [uri], when it is a MediaStore uri at all. */
    private fun mediaStoreId(context: Context, uri: Uri): Long? {
        if (uri.authority == MediaStore.AUTHORITY) {
            runCatching { ContentUris.parseId(uri) }.getOrNull()?.let { return it }
        }
        // A documents provider can still name a MediaStore row: "audio:1234".
        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            val parts = docId?.split(":", limit = 2)
            if (parts != null && parts.size == 2 && parts[0] == "audio") {
                return parts[1].toLongOrNull()
            }
        }
        return null
    }

    /**
     * Best-effort absolute path for [uri]: file:// directly, then SAF document ids, then the
     * provider's DATA column, then the opened descriptor. Ported from the same problem in Loopr,
     * where a file manager's private FileProvider uri was the case that broke folder queueing.
     */
    private fun resolvePath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { normalize(it) }

        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            when (uri.authority) {
                "com.android.externalstorage.documents" -> if (docId != null) {
                    val parts = docId.split(":", limit = 2)
                    val vol = parts[0]
                    val rel = parts.getOrNull(1).orEmpty()
                    val base = if (vol.equals("primary", true)) "/storage/emulated/0" else "/storage/$vol"
                    if (vol.isNotEmpty()) return "$base/$rel".trimEnd('/')
                }
                "com.android.providers.downloads.documents" ->
                    if (docId != null && docId.startsWith("raw:")) return normalize(docId.removePrefix("raw:"))
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return normalize(it) }

        return pathFromDescriptor(context, uri)
    }

    /**
     * The real path behind [uri], read off the open descriptor. This is what rescues a file
     * manager's own FileProvider uri, which answers no id and no DATA column.
     */
    private fun pathFromDescriptor(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val link = Os.readlink("/proc/self/fd/${pfd.fd}")
            // A provider streaming through a pipe or socket has no path to give.
            if (link.startsWith("/") && !link.startsWith("/proc/")) normalize(link) else null
        }
    }.getOrNull()

    /** Folds the many mount aliases the same file can be reached through onto one spelling. */
    private fun normalize(path: String): String {
        val aliases = listOf(
            "/sdcard/", "/mnt/sdcard/", "/storage/self/primary/", "/storage/emulated/legacy/",
            "/mnt/user/0/primary/", "/mnt/user/0/emulated/0/", "/mnt/runtime/default/emulated/0/",
            "/mnt/runtime/read/emulated/0/", "/mnt/runtime/write/emulated/0/",
            "/mnt/androidwritable/0/emulated/0/"
        )
        for (a in aliases) if (path.startsWith(a)) return "/storage/emulated/0/" + path.removePrefix(a)
        if (path.startsWith("/mnt/media_rw/")) return "/storage/" + path.removePrefix("/mnt/media_rw/")
        return path
    }
}
