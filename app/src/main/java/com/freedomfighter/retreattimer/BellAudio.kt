package com.freedomfighter.retreattimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Plays the three-bell recording for the in-app "Test" buttons. It sets the alarm
 * stream to the same level the real ring will use, so the teacher hears in advance
 * exactly how loud the room will be when the bell fires for real.
 */
object BellAudio {
    private var player: MediaPlayer? = null

    /** Test the bells at the exact alarm volume that will be used for real.
     *  Defaults to the currently-selected sound, or a specific [rawRes] preview. */
    fun playTest(ctx: Context, rawRes: Int = BellSounds.selected(ctx).rawRes) {
        stop()
        val desired = BellStore.alarmVolume(ctx)
        if (desired >= 0) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, desired.coerceIn(0, max), 0)
        }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                // Match the room's Bluetooth speaker, not the phone (see [preferBluetoothOutput]).
                preferBluetoothOutput(ctx)
                val afd = ctx.resources.openRawResourceFd(rawRes)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
    }
}
