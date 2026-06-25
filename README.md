# Retreat Timer

An Android app that rings **three singing-bowl bells** at scheduled times of day —
the signal to begin or end a meditation sitting — so a retreat keeps running on
schedule even when the teacher is not present.

## Why it's reliable

The whole point of this app is that the bells ring **no matter what**: locked
screen, deep sleep, Doze, hours unattended.

- Each bell is scheduled with `AlarmManager.setAlarmClock()` — the only Android
  alarm type guaranteed to fire at the exact time even in Doze mode (it's the same
  mechanism the stock Clock app uses).
- When an alarm fires it starts a **foreground service** (type `mediaPlayback`)
  that holds a **partial wake lock** and plays the recording through the **alarm
  audio stream** at the volume you set — so nothing the OS does can mute or kill it.
- Alarms are re-armed automatically after a **reboot**, time change, or app update.
- The home screen shows a green "Ready" light once battery optimisation is disabled
  and notifications are allowed — the teacher's "I can leave now" confirmation.

## Using it

1. Tap **Add first bell**, then tap a time to edit it.
2. Tap **+ Add bell (1 hour later)** to drop the next ring exactly one hour after
   the last (e.g. 07:45 → 08:45). Edit any time by tapping it.
3. Use the **▶ Test** button (per bell, or on the volume card) to hear the three
   bells at the exact volume the room will hear.
4. Toggle any bell on/off; the schedule repeats every day.

Keep the phone plugged in and the bells will play all day on their own.

## Build

```
./gradlew assembleDebug
```

The bell recording (`app/src/main/res/raw/three_bells.mp3`) is three strikes of a
singing bowl, each allowed to fade to barely audible before the next — human-paced.

No ads, no tracking, no accounts. Permissions are limited to exact alarms, boot
receipt, wake lock, foreground-service playback, and notifications.
