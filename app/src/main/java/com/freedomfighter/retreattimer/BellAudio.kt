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
    fun playTest(ctx: Context, rawRes: Int) {
        stop()
        val gain = gainScalar(BellStore.bellGain(ctx))
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                val afd = ctx.resources.openRawResourceFd(rawRes)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { stop() }
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
