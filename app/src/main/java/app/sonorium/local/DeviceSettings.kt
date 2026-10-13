package app.sonorium.local

import android.content.Context

/** This device's Audio Settings (as a server's): crossfade between themes, default volume, master gain. */
object DeviceSettings {
    data class Audio(val crossfade: Double = 3.0, val volume: Int = 60, val gain: Int = 60, val stereo: Boolean = true)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("device_settings", Context.MODE_PRIVATE)

    fun get(context: Context): Audio = prefs(context).let { p ->
        Audio(p.getFloat("crossfade", 3f).toDouble(), p.getInt("volume", 60), p.getInt("gain", 60), p.getBoolean("stereo", true))
    }

    fun set(context: Context, audio: Audio) {
        prefs(context).edit().putFloat("crossfade", audio.crossfade.toFloat()).putInt("volume", audio.volume).putInt("gain", audio.gain).putBoolean("stereo", audio.stereo).apply()
    }
}
