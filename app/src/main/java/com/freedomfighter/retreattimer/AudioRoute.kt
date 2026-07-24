package com.freedomfighter.retreattimer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * The connected Bluetooth (or BLE) speaker/headset to pin playback to, or null if
 * none is present.
 *
 * Every audio path here uses [android.media.AudioAttributes.USAGE_ALARM] so bells
 * and talks fire through silent mode and Do-Not-Disturb. The catch: on many
 * devices Android deliberately duplicates the alarm stream to the *built-in
 * speaker* on top of a connected Bluetooth device, so an alarm is never missed if
 * the headphones are off your ears. On a retreat that means a talk pouring out of
 * the teacher's phone as well as the room speaker. Pinning the player's preferred
 * device to the Bluetooth output confines playback to the speaker the room hears;
 * when no Bluetooth speaker is connected this is null and playback falls back to
 * the phone as before.
 */
fun bluetoothOutput(ctx: Context): AudioDeviceInfo? {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }.getOrNull()
}
