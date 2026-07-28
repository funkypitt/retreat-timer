package com.freedomfighter.retreattimer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting

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

/**
 * Route this player to the room's Bluetooth speaker when one is present. Returns
 * true only when the hint was actually accepted.
 *
 * **Call this late.** A [android.media.MediaPlayer] has no native player object
 * until `setDataSource()` has run, and `setPreferredDevice` on a player in that
 * state is refused outright — it returns false and the hint is lost, silently.
 * That is exactly what 1.8.2 did (the call sat next to `setAudioAttributes`,
 * before the data source), which is why playback kept leaking to the phone. Set
 * it after `prepare()`, and — since a started track can still be re-routed —
 * again after `start()` if the first attempt was refused. An [AudioTrack] is
 * usable as soon as it is built, so there the ordering is already fine.
 *
 * Deliberately fail-safe: the whole call is wrapped so that *nothing* here — a
 * device-query fault, a driver rejecting [setPreferredDevice] — can ever throw
 * out of the critical playback path. The bell must ring even if the routing hint
 * fails, in which case playback falls back to the system's default output.
 */
fun AudioRouting.preferBluetoothOutput(ctx: Context): Boolean =
    runCatching { bluetoothOutput(ctx)?.let { setPreferredDevice(it) } == true }.getOrDefault(false)

/**
 * Turn a 0–100% loudness trim into a [android.media.MediaPlayer.setVolume] amplitude
 * scalar. The curve is squared so equal slider steps feel like roughly equal steps
 * to the ear (loudness perception is far from linear in amplitude); 100% is unity
 * (no attenuation) and 0% is silence. Applying the trim inside the player, rather
 * than via the alarm stream, is what makes it work over a Bluetooth speaker — where
 * A2DP absolute volume otherwise ignores `setStreamVolume`.
 */
fun gainScalar(pct: Int): Float {
    val f = pct.coerceIn(0, 100) / 100f
    return f * f
}
