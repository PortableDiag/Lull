package com.lull.player

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback host. Media3's MediaSessionService runs as a foreground service with
 * a media notification while playing, and routes lock-screen / Bluetooth / headset media
 * buttons to the session player (our [RepeatAwarePlayer]).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    // When "Mix with other audio" is toggled in settings, re-apply audio focus handling on the
    // live player so the change takes effect without restarting playback.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_MIX_AUDIO) applyAudioFocus()
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(MUSIC_ATTRIBUTES, /* handleAudioFocus = */ !Prefs.mixAudio(this))
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = exo

        val player = RepeatAwarePlayer(exo)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openUiIntent())
            .build()

        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Mixing means *not* requesting audio focus, so other players keep going; disabling it makes
     * us grab focus (pausing others) and respond to interruptions like a normal media app.
     */
    private fun applyAudioFocus() {
        exoPlayer?.setAudioAttributes(MUSIC_ATTRIBUTES, /* handleAudioFocus = */ !Prefs.mixAudio(this))
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // If the user swipes the app away while nothing is playing, tear down the service.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun openUiIntent(): PendingIntent {
        val intent = Intent(this, NowPlayingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private companion object {
        val MUSIC_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }
}
