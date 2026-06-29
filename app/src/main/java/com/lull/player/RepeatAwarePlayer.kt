package com.lull.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * When repeat-one is active, "next"/"previous" — from the UI, the media notification,
 * the lock screen, or hardware/Bluetooth media buttons — restart the current track
 * instead of changing tracks.
 *
 * This is deliberate: if you fall asleep to a looping white-noise track, an accidental
 * skip on a headphone/headband button just restarts it rather than jumping to music.
 *
 * The four seek commands are always reported as available so the buttons stay enabled
 * (and therefore tappable to "restart") even on a single-item queue.
 */
class RepeatAwarePlayer(player: Player) : ForwardingPlayer(player) {

    private fun restartCurrent() {
        seekTo(currentMediaItemIndex, 0L)
    }

    override fun seekToNext() {
        if (repeatMode == Player.REPEAT_MODE_ONE) restartCurrent() else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (repeatMode == Player.REPEAT_MODE_ONE) restartCurrent() else super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (repeatMode == Player.REPEAT_MODE_ONE) restartCurrent() else super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (repeatMode == Player.REPEAT_MODE_ONE) restartCurrent() else super.seekToPreviousMediaItem()
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ).build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
        else -> super.isCommandAvailable(command)
    }
}
