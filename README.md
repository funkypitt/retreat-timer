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

1. Tap **Add bell** and pick the time. The picker opens ready for typing, so any
   minute (06:47, not just 06:45) is set directly; a dial is one tap away.
2. Edit any time by tapping it. Toggle any bell on/off; the schedule repeats
   every day.
3. **Bell volume** and **Talk volume** are set separately — a spoken recording
   normally has to be much louder than a bowl strike to fill the same room. Each
   card has its own **▶ Test** button, and **Test talk** plays the talk that is
   actually coming up next, so the level can be judged on real speech.
4. The player bar at the bottom has a **draggable progress bar** — talks often
   open with minutes of silence, so drag straight to the speech instead of
   waiting it out. Alongside it: restart, −10s, play/pause, +10s, stop, and both
   elapsed and remaining time. The notification carries the same controls.

Keep the phone plugged in and the bells will play all day on their own.

## Build

```
./gradlew assembleDebug
```

Four selectable bell sounds (`app/src/main/res/raw/bell_*.mp3`) — singing bell,
Tibetan E♭ bowl, gong bowl, and Satipanya. Each is one bowl struck three times,
the strike allowed to ring out **fully** before the next. All four are
loudness-matched to −20 LUFS so switching changes only the timbre, not the volume.

The first three are generated from the source samples in the repo root (linear
gain to match loudness, then `ffmpeg concat` ×3). Satipanya comes from
`Satipanya-3-bells.mp3`, which is already a natural three-strike recording, so it
is used whole rather than concatenated — only gained to match, trimmed of its
15 s of trailing silence, and converted to stereo:

```
ffmpeg -i Satipanya-3-bells.mp3 \
  -af "volume=19.0dB,atrim=0:71.5,afade=t=out:st=70.5:d=1.0,aformat=channel_layouts=stereo" \
  -ar 44100 -b:a 192k app/src/main/res/raw/bell_satipanya.mp3
```

Its strikes are spaced further apart than the other three — that is how the
recording was made, and it is kept.

No ads, no tracking, no accounts. Permissions are limited to exact alarms, boot
receipt, wake lock, foreground-service playback, and notifications.
