package com.freedomfighter.retreattimer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays a bell recording for the in-app "Test" buttons at the bell trim
 * (see [BellStore.bellGain]) — a software gain applied to the player, so the
 * preview is a true preview of room loudness on the phone speaker and, crucially,
 * over a Bluetooth speaker too.
 */
object BellAudio {
    private var player: MediaPlayer? = null

    /** Preview [rawRes] at the current bell trim. All bell recordings are
     *  loudness-matched, so any of them judges the level equally well. */
    fun playTest(ctx: Context, rawRes: Int) = playTest(ctx, rawRes, null, 1)

    /** Preview whatever is chosen, at the length a slot would ring it — the custom
     *  recording repeats three times for a three-bell slot, like the real ring. */
    fun playTestSelected(ctx: Context, single: Boolean) {
        val custom = BellSounds.customFile(ctx)?.takeIf { BellSounds.isCustom(ctx) }
        if (custom != null) playTest(ctx, 0, custom.absolutePath, BellSounds.customRepeats(single))
        else playTest(ctx, BellSounds.rawRes(ctx, single))
    }

    /** Preview the imported recording once, whether or not it is chosen. */
    fun playTestCustom(ctx: Context) {
        BellSounds.customFile(ctx)?.let { playTest(ctx, 0, it.absolutePath, 1) }
    }

    private var repeatsLeft = 1

    private fun playTest(ctx: Context, rawRes: Int, path: String?, repeats: Int) {
        stop()
        repeatsLeft = repeats
        val gain = gainScalar(BellStore.bellGain(ctx))
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                if (path != null) setDataSource(path) else {
                    val afd = ctx.resources.openRawResourceFd(rawRes)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                setOnCompletionListener { if (--repeatsLeft > 0) { runCatching { seekTo(0); start() } } else stop() }
                prepare()
                setVolume(gain, gain)
                // Match the room's Bluetooth speaker, not the phone — after
                // prepare(), or the hint is dropped (see [preferBluetoothOutput]).
                val pinned = preferBluetoothOutput(ctx)
                start()
                if (!pinned) preferBluetoothOutput(ctx)
            }
        }
    }

    /** Re-apply the trim to a preview that is already playing, so dragging the
     *  slider is heard live. No-op when nothing is playing. */
    fun setGain(pct: Int) {
        val g = gainScalar(pct)
        runCatching { player?.setVolume(g, g) }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
    }
}
