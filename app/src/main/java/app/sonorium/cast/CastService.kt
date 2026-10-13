package app.sonorium.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.sonorium.R
import app.sonorium.engine.OpenApp
import app.sonorium.engine.PhoneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress

/**
 * Keeps the cast stream going with the screen off: a foreground service holding the [StreamServer]
 * (bound to the phone's Wi-Fi address) and a wake lock while the phone casts.
 */
class CastService : Service() {
    private var server: StreamServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var address = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // "Stop casting" in the notification
                CastController.stopFromNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                val speaker = intent?.getStringExtra(EXTRA_SPEAKER).orEmpty()
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification(title, speaker),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
                if (server == null) {
                    address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
                    val bind = address.takeIf { it.isNotEmpty() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                    // The cast stream is mono: the engine's stereo frames folded ((L+R)/2)
                    server = runCatching { StreamServer(render = { buf -> renderMono(buf) }, bind = bind) }
                        .onFailure { android.util.Log.w("CastService", "Couldn't open the stream", it) }.getOrNull()
                    if (server == null) {
                        CastController.onStreamFailed()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonorium:cast").apply { acquire(12 * 60 * 60 * 1000L) }
                }
                server?.let { CastController.onServing(it.port, address) }
            }
        }
        return START_NOT_STICKY
    }

    private var stereoBuf = ByteArray(0)

    /** Fills [mono] with the engine's next frames folded to mono ((L+R)/2), 16-bit; returns the bytes written. */
    private fun renderMono(mono: ByteArray): Int {
        val frames = mono.size / 2
        if (stereoBuf.size < frames * PhoneEngine.FRAME_BYTES) stereoBuf = ByteArray(frames * PhoneEngine.FRAME_BYTES)
        val n = PhoneEngine.render(stereoBuf, 0, frames * PhoneEngine.FRAME_BYTES) / PhoneEngine.FRAME_BYTES
        for (f in 0 until n) {
            val i = f * 4
            val l = (stereoBuf[i].toInt() and 0xff) or (stereoBuf[i + 1].toInt() shl 8)
            val r = (stereoBuf[i + 2].toInt() and 0xff) or (stereoBuf[i + 3].toInt() shl 8)
            val m = (l + r) / 2
            mono[f * 2] = m.toByte()
            mono[f * 2 + 1] = (m shr 8).toByte()
        }
        return n * 2
    }

    private fun notification(title: String, speaker: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
        // Tapping it: back to the main screen already open (not a second one)
        val open = OpenApp.pendingIntent(this, OPEN_REQUEST)
        OpenApp.addLastServer(this, OPEN_REQUEST, scope)
        val stop = PendingIntent.getService(this, 3, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { "Sonorium" })
            .setContentText("Casting to ${speaker.ifEmpty { "a speaker" }}")
            .setContentIntent(open)
            .addAction(0, "Stop casting", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        server?.close()
        server = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        scope.cancel()
        CastController.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.sonorium.cast.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 1101
        private const val OPEN_REQUEST = 2
    }
}
