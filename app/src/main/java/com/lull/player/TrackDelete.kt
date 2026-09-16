package com.lull.player

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deleting the file itself, which is the only thing Lull does that it cannot take back.
 *
 * Android has moved the goalposts three times and Lull supports all three positions:
 *
 *  - **API 30 and up** — the app cannot delete a file it did not create. It asks the system for a
 *    delete, the system shows its own confirmation over the app, and the system does the deleting.
 *    One dialog covers the whole batch.
 *  - **API 29** — a delete throws [RecoverableSecurityException], which carries a consent dialog for
 *    *that one file*. So a selection is worked through a file at a time, which is what Android 10
 *    is; there is no batch form of it.
 *  - **API 26–28** — a plain `WRITE_EXTERNAL_STORAGE` delete, no system dialog at all. Lull's own
 *    confirmation is the only one there will be, which is the main reason it exists on every
 *    version rather than only where the platform does not already ask.
 *
 * Nothing here trusts a result code. `RESULT_OK` says the user pressed a button, not that every
 * file went, so what actually happened is read back out of MediaStore afterwards — see
 * [MediaLibrary.existingIds] and [Deletion.outcome].
 */
object TrackDelete {

    private const val TAG = "LullDelete"

    /**
     * One pass at deleting. [consent] is a dialog that has to be shown before anything more can
     * happen; [pending] is what that dialog covers, to be re-attempted once it comes back.
     */
    class Attempt(val consent: IntentSender?, val pending: List<Long>)

    fun attempt(context: Context, ids: List<Long>): Attempt {
        if (ids.isEmpty()) return Attempt(null, emptyList())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestAll(context, ids)
        else deleteEach(context, ids)
    }

    /** API 30+: the system deletes them, once it has asked. Nothing is gone until it comes back. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAll(context: Context, ids: List<Long>): Attempt {
        val uris = ids.map { MediaLibrary.contentUriFor(it) }
        val sender = runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.onFailure { Log.w(TAG, "delete request refused", it) }.getOrNull()
        return Attempt(sender, ids)
    }

    /**
     * API 29 and below: try each one. Below 29 that is the whole story. On 29 a file another app
     * owns comes back as a [RecoverableSecurityException] carrying its own consent dialog, so the
     * first one that does becomes the dialog to show and everything still unresolved is re-tried
     * behind it.
     */
    private fun deleteEach(context: Context, ids: List<Long>): Attempt {
        var consent: IntentSender? = null
        val pending = ArrayList<Long>()
        for (id in ids) {
            try {
                context.contentResolver.delete(MediaLibrary.contentUriFor(id), null, null)
            } catch (e: SecurityException) {
                val sender = recoverable(e)
                if (sender == null) Log.w(TAG, "cannot delete $id", e)
                else {
                    if (consent == null) consent = sender
                    pending += id
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot delete $id", e)
            }
        }
        return Attempt(consent, pending)
    }

    private fun recoverable(e: SecurityException): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) senderOf(e) else null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun senderOf(e: SecurityException): IntentSender? =
        (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

    /**
     * Everything Lull remembered about tracks that are now gone: the rating, the tempo, the
     * loudness profile, and membership of every playlist.
     *
     * Ratings and tempos are keyed by MediaStore id precisely so they survive a file *moving*, but
     * a file the user deleted is not moving anywhere, and MediaStore hands a re-added file a new
     * id — so keeping any of it would be keeping rubbish that can never be matched to anything
     * again. Called on the main thread, because the stores tell the open screens when they change.
     */
    fun forget(context: Context, gone: List<Long>) {
        if (gone.isEmpty()) return
        RatingStore.setAll(context, gone, RatingStore.UNRATED)
        TempoStore.forgetAll(context, gone)
        ProfileStore.forgetAll(context, gone)
        PlaylistStore.removeFromAll(context, gone)
        Changes.notifyChanged()
    }
}

/**
 * The delete, as a user goes through it: confirm, then however many system dialogs this version of
 * Android insists on, then one honest sentence about what actually went.
 *
 * It lives in a class rather than in [MainActivity] because Now Playing deletes the track you can
 * hear and the library deletes a selection, and the awkward half — registering the activity-result
 * launchers, holding the batch across the system's dialog, working out afterwards what really
 * happened — is identical either way. Construct it as a field: [ActivityResultContracts] launchers
 * must be registered before the activity is started.
 */
class DeleteFlow(
    private val activity: AppCompatActivity,
    private val controller: () -> MediaController?,
    private val onFinished: () -> Unit = {}
) {

    /** The batch the user confirmed, held across the system's dialogs. Empty when nothing is running. */
    private var requested: List<Long> = emptyList()

    /** What the dialog currently on screen covers. */
    private var pending: List<Long> = emptyList()

    /** Titles, kept only so the report can name a single deleted track instead of counting it. */
    private var titles: Map<Long, String> = emptyMap()

    private val consent = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Granted: on API 30+ the system has already done it and the next pass finds nothing left
        // to do; on 29 the grant covers one file and the next pass takes the one after it.
        if (result.resultCode == Activity.RESULT_OK) step(pending) else finish()
    }

    private val writePermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) step(requested) else {
            requested = emptyList()
            toast(activity.getString(R.string.delete_needs_permission))
        }
    }

    /**
     * Lull's own confirmation, shown on every Android version — including the ones that will show
     * their own straight after.
     *
     * The system's dialog asks whether you meant to delete *a file*; this one says which tracks,
     * how many, and that the rating and playlist entries go with them. Below API 29 there is no
     * system dialog at all, so this is the only thing standing between a long-press and a file
     * being gone for good.
     */
    fun confirm(tracks: List<AudioItem>) =
        ask(tracks.map { it.id }, tracks.associate { it.id to label(it) })

    /** The track you can hear, from Now Playing, where there is a `MediaItem` and no [AudioItem]. */
    fun confirmOne(id: Long, title: String) = ask(listOf(id), mapOf(id to title))

    private fun ask(ids: List<Long>, named: Map<Long, String>) {
        val unique = ids.distinct()
        if (unique.isEmpty()) { toast(activity.getString(R.string.nothing_selected)); return }
        if (requested.isNotEmpty()) return

        val message = if (unique.size == 1)
            activity.getString(R.string.delete_confirm_one, named[unique[0]].orEmpty())
        else activity.resources.getQuantityString(
            R.plurals.delete_confirm_many, unique.size, unique.size
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_track)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ -> start(unique, named) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(ids: List<Long>, named: Map<Long, String>) {
        requested = ids
        titles = named

        // Only below API 29 is a delete a matter of holding a permission. From 29 up the file is
        // not ours to delete however many permissions we hold, and the consent dialog is the route.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        step(requested)
    }

    /**
     * One round: drop anything already gone, try what is left, and show a consent dialog if the
     * system handed one back. No dialog means there is nothing further to wait for, whether that
     * is because it worked or because it cannot.
     */
    private fun step(ids: List<Long>) {
        if (ids.isEmpty()) { finish(); return }
        activity.lifecycleScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                val live = MediaLibrary.existingIds(activity, ids)
                TrackDelete.attempt(activity, ids.filter { it in live })
            }
            val sender = attempt.consent
            if (sender == null) { finish(); return@launch }
            pending = attempt.pending
            val launched = runCatching {
                consent.launch(IntentSenderRequest.Builder(sender).build())
            }.isSuccess
            if (!launched) finish()
        }
    }

    /** Works out what really went, forgets it, takes it out of the queue, and says so. */
    private fun finish() {
        val asked = requested
        requested = emptyList()
        pending = emptyList()
        if (asked.isEmpty()) return

        activity.lifecycleScope.launch {
            val present = withContext(Dispatchers.IO) { MediaLibrary.existingIds(activity, asked) }
            val outcome = Deletion.outcome(asked, present)
            if (outcome.deleted > 0) {
                dropFromQueue(outcome.goneIds.toSet())
                TrackDelete.forget(activity, outcome.goneIds)
            }
            report(outcome)
            titles = emptyMap()
            onFinished()
        }
    }

    /**
     * Takes the deleted tracks out of what is playing. The engine moves on by itself when the item
     * removed is the current one — leaving it in would leave the player pointed at a file that is
     * no longer there, which is a playback error a second or two later rather than now.
     */
    private fun dropFromQueue(gone: Set<Long>) {
        val c = controller() ?: return
        val queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        for (index in Deletion.queueRemovals(queue, gone)) c.removeMediaItem(index)
    }

    private fun report(outcome: Deletion.Outcome) {
        val named = outcome.goneIds.singleOrNull()?.let { titles[it] }?.takeIf { it.isNotBlank() }
        toast(
            when {
                outcome.deleted == 0 -> activity.getString(R.string.delete_none)
                !outcome.complete ->
                    activity.getString(R.string.delete_partial, outcome.deleted, outcome.requested)
                named != null -> activity.getString(R.string.deleted_one, named)
                else -> activity.resources.getQuantityString(
                    R.plurals.deleted_count, outcome.deleted, outcome.deleted
                )
            }
        )
    }

    private fun label(item: AudioItem): String =
        item.title.ifBlank { item.path.substringAfterLast('/') }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
