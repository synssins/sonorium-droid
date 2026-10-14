package app.sonorium.cast

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/**
 * The Cast speaker's volume in steps of the system volume panel: [STEPS] steps of 5 %. Pure, so the step and
 * clamp rules are unit-tested; [CastVolumeSession] puts them behind the device's volume keys.
 */
object CastVolumeSteps {
    /** Steps from silent to full: one volume key press moves 5 %. */
    const val STEPS = 20

    /** The speaker's level (0..1) as a step, rounded to the nearest one; muted reads as 0. */
    fun toStep(level: Double, muted: Boolean): Int =
        if (muted || level.isNaN()) 0 else (level * STEPS).roundToInt().coerceIn(0, STEPS)

    /** A step as the speaker's level (0..1). */
    fun toLevel(step: Int): Double = step.coerceIn(0, STEPS).toDouble() / STEPS

    /**
     * What a volume key (or the panel) asks for, from [level]/[muted] now. [direction] is one of
     * AudioManager's ADJUST_* values. Null: nothing to change.
     */
    fun adjust(level: Double, muted: Boolean, direction: Int): Change? = when (direction) {
        AudioManager.ADJUST_RAISE ->
            // Muted: the first press brings the sound back where it was (the way the device's own volume does)
            // (at least one step, so the press is heard)
            if (muted) Change(maxOf(level.coerceIn(0.0, 1.0), toLevel(1)), false)
            else toStep(level, false).let { s -> if (s >= STEPS) null else Change(toLevel(s + 1), false) }
        AudioManager.ADJUST_LOWER ->
            if (muted) null
            else toStep(level, false).let { s -> if (s <= 0) null else Change(toLevel(s - 1), false) }
        AudioManager.ADJUST_MUTE -> if (muted) null else Change(level, true)
        AudioManager.ADJUST_UNMUTE -> if (!muted) null else Change(level, false)
        AudioManager.ADJUST_TOGGLE_MUTE -> Change(level, !muted)
        else -> null
    }

    /**
     * The panel's slider (or its mute icon) set [step]: 0 mutes and keeps the speaker's level for unmuting,
     * any other step unmutes at that level.
     */
    fun setTo(level: Double, step: Int): Change =
        if (step <= 0) Change(level, true) else Change(toLevel(step), false)

    data class Change(val level: Double, val muted: Boolean)
}

/**
 * While casting: a media session with remote playback, so the device's volume keys and the system volume panel
 * show and change the Cast speaker's volume instead of the device's media volume. Releasing it gives the keys
 * back to the device's media volume.
 */
class CastVolumeSession(context: Context, name: String, private val target: Target) {
    /** Where the changes go: the Cast session (or a stand-in in the device tests). */
    interface Target {
        fun setLevel(level: Double)
        fun setMuted(muted: Boolean)
    }

    private var level = 0.0
    private var muted = false

    private val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, CastVolumeSteps.STEPS, 0) {
        override fun onAdjustVolume(direction: Int) {
            CastVolumeSteps.adjust(level, muted, direction)?.let { apply(it) }
        }

        override fun onSetVolumeTo(volume: Int) {
            apply(CastVolumeSteps.setTo(level, volume))
        }
    }

    private val session = MediaSession(context, "sonorium-cast").apply {
        // Without a callback the session drops the volume changes it gets (the provider is called from it)
        setCallback(object : MediaSession.Callback() {}, Handler(Looper.getMainLooper()))
        setPlaybackToRemote(provider)
        setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, name).build())
        // The system sends the volume keys to an active session that plays
        setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build(),
        )
        isActive = true
    }

    val token: MediaSession.Token get() = session.sessionToken

    /** The speaker's volume changed (here, in the Google Home app, or on the speaker): the panel follows. */
    fun update(level: Double, muted: Boolean) {
        this.level = level.coerceIn(0.0, 1.0)
        this.muted = muted
        provider.currentVolume = CastVolumeSteps.toStep(this.level, muted)
    }

    private fun apply(change: CastVolumeSteps.Change) {
        val wasMuted = muted
        val wasLevel = level
        update(change.level, change.muted)
        if (change.level != wasLevel) target.setLevel(change.level)
        if (change.muted != wasMuted) target.setMuted(change.muted)
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
