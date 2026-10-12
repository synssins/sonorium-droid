package app.sonorium.enginelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.time.Instant

/**
 * Plays a source through AudioTrack in the background (screen off) and logs, every minute, the
 * render thread's CPU time and the battery level to `<external files>/lab-log.csv`.
 */
class LabService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: return START_NOT_STICKY
        val theme = File(intent.getStringExtra(EXTRA_THEME) ?: return START_NOT_STICKY)
        val cache = intent.getStringExtra(EXTRA_CACHE)?.let(::File)
        val fast = intent.getBooleanExtra(EXTRA_FAST, false)
        stop()
        startForegroundNow("Starting…")
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "enginelab:play").apply { acquire(4 * 60 * 60 * 1000L) }
        running = true
        thread = Thread({ play(mode, theme, cache, fast) }, "lab-render").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    private fun play(mode: String, theme: File, cache: File?, fast: Boolean) {
        val source: PcmSource = try {
            if (mode == MODE_KOTLIN) KotlinMixerSource(cache ?: theme) else EngineSource(this, theme, cache, fast)
        } catch (e: Throwable) {
            status = "Failed: ${e.message}"
            update(status)
            running = false
            return
        }
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, 4096 * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val block = ShortArray(4096)
        val log = File(getExternalFilesDir(null), "lab-log.csv")
        if (!log.exists()) log.writeText("time,mode,label,minutes,cpu_seconds,cpu_percent_of_one_core,battery_percent\n")
        val startBattery = battery()
        val startWall = SystemClock.elapsedRealtime()
        val startCpu = Debug.threadCpuTimeNanos()
        var renderNs = 0L
        var nextReport = startWall + 60_000
        track.play()
        try {
            while (running) {
                val t0 = Debug.threadCpuTimeNanos()
                source.read(block)
                renderNs += Debug.threadCpuTimeNanos() - t0
                track.write(block, 0, block.size)
                val now = SystemClock.elapsedRealtime()
                if (now >= nextReport) {
                    nextReport += 60_000
                    val minutes = (now - startWall) / 60_000.0
                    val cpu = (Debug.threadCpuTimeNanos() - startCpu) / 1e9
                    val pct = 100.0 * cpu / ((now - startWall) / 1000.0)
                    log.appendText("${Instant.now()},$mode,\"${source.label}\",${"%.1f".format(minutes)},${"%.2f".format(cpu)},${"%.2f".format(pct)},${battery()}\n")
                    status = "${source.label} · ${"%.0f".format(minutes)} min · render ${"%.2f".format(renderNs / 1e9)} s · " +
                        "thread ${"%.1f".format(pct)} % of a core · battery $startBattery → ${battery()} %"
                    update(status)
                }
            }
        } catch (e: Throwable) {
            status = "Stopped: ${e.message}"
            update(status)
        } finally {
            track.stop()
            track.release()
            source.close()
        }
    }

    private fun battery(): Int = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Engine lab", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sonorium Engine Lab")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNow(text: String) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, notification(text))
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"
        const val EXTRA_THEME = "theme"
        const val EXTRA_CACHE = "cache"
        const val EXTRA_FAST = "fast"
        const val MODE_PYTHON = "python"
        const val MODE_KOTLIN = "kotlin"
        private const val CHANNEL = "lab"

        @Volatile var status: String = "Idle"

        fun start(context: Context, mode: String, theme: File, cache: File?, fast: Boolean) {
            val i = Intent(context, LabService::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_THEME, theme.path).putExtra(EXTRA_CACHE, cache?.path).putExtra(EXTRA_FAST, fast)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LabService::class.java).setAction(ACTION_STOP))
        }
    }
}
