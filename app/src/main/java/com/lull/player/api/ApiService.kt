package com.lull.player.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lull.player.MainActivity
import com.lull.player.PlaybackService
import com.lull.player.R

/**
 * Hosts the local API for as long as it is switched on.
 *
 * **Why this is not part of [PlaybackService]:** that service exists while there is something to
 * play and is torn down when there isn't. The API has to answer precisely when nothing is playing,
 * because "start playing something" is the request an agent most obviously wants to make. So it
 * gets its own foreground service, with its own notification saying, plainly, that Lull is
 * listening on a port — a socket that is open should never be a thing you have to go looking for.
 *
 * The service holds the one `MediaController` the API drives playback through, connected on the
 * main thread and handed to [LullBackend] as a supplier so that a request arriving before the
 * connection completes gets a 503 rather than a crash.
 */
class ApiService : Service() {

    companion object {
        private const val TAG = "LullApi"
        private const val CHANNEL_ID = "lull_api"
        private const val NOTIFICATION_ID = 42

        const val ACTION_STOP = "com.lull.player.api.STOP"

        /** Starts the API if it is enabled; a no-op otherwise, so callers needn't check first. */
        fun startIfEnabled(context: Context) {
            if (!ApiPrefs.enabled(context)) return
            val intent = Intent(context, ApiService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start the API service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ApiService::class.java)) }
        }

        /** Stop and start, for a port or LAN change that needs a fresh socket. */
        fun restart(context: Context) {
            stop(context)
            startIfEnabled(context)
        }
    }

    private var server: ApiServer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connectController()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's own Stop: turn the setting off too, or the next launch reopens it.
            ApiPrefs.setEnabled(this, false)
            ApiStatus.update(running = false, address = "", error = null)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ApiPrefs.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        if (server?.isRunning == true) return START_STICKY

        val address = ApiPrefs.bindAddress(this)
        val port = ApiPrefs.port(this)
        val router = ApiRouter(LullBackend(applicationContext) { controller }) { ApiPrefs.key(this) }
        val api = ApiServer(router, address, port)

        // Foreground first: a service that binds a socket before it has posted its notification is
        // a service that can be killed with the socket open.
        startForegroundWith("$address:$port")

        return try {
            api.start()
            server = api
            ApiStatus.update(running = true, address = api.address, error = null)
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on $address:$port", e)
            ApiStatus.update(running = false, address = "", error = e.message ?: e.javaClass.simpleName)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        controller?.release()
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        ApiStatus.update(running = false, address = "", error = ApiStatus.error)
        super.onDestroy()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            controller = runCatching { future.get() }.getOrNull()
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Notification ----------------

    private fun startForegroundWith(address: String) {
        createChannel()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_api)
            .setContentTitle(getString(R.string.api_notification_title))
            .setContentText(getString(R.string.api_notification_text, address))
            .setContentIntent(open)
            .addAction(0, getString(android.R.string.cancel), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.api_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }
}

/**
 * What the API is doing, for the settings dialog to show.
 *
 * The dialog cannot ask the socket directly — it may be in another process state entirely by the
 * time it is opened — so the service publishes here, and a listener redraws the dialog if it is up.
 */
object ApiStatus {

    var running: Boolean = false
        private set
    var address: String = ""
        private set

    /** Why it is not running, when that is something the user can fix (a port already in use). */
    var error: String? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun update(running: Boolean, address: String, error: String?) {
        this.running = running
        this.address = address
        this.error = error
        listeners.toList().forEach { it() }
    }
}
