# Retreat Timer

An Android app that rings **singing-bowl bells** at scheduled times of day — the
signal to begin or end a meditation sitting — so a retreat keeps running on
schedule even when the teacher is not present. Each slot rings **three bells**
(the default) or **one**, chosen per bell.

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
2. In that same picker, choose **Three bells** (the default) or **One bell** for
   this slot — three to open and close a sitting, one for the smaller markers in
   a day. Either can be previewed there before it is set, and the choice applies
   whichever bowl is selected. The schedule shows a **×3** or **×1** badge beside
   each bell's time, so which is which is visible without opening anything.
3. Edit any time — and its strike count — by tapping it. Toggle any bell on/off;
   the schedule repeats every day.
4. **Bell volume** and **Talk volume** are set separately — a spoken recording
   normally has to be much louder than a bowl strike to fill the same room. Each
   card has its own **▶ Test** button. **Test bell** rings a *single* strike, since
   every recording is loudness-matched and one is enough to judge the level;
   **Test talk** plays the talk that is actually coming up next, so the level can
   be judged on real speech.
5. The player bar at the bottom has a **draggable progress bar** — talks often
   open with minutes of silence, so drag straight to the speech instead of
   waiting it out. Alongside it: restart, −10s, play/pause, +10s, stop, and both
   elapsed and remaining time. The notification carries the same controls.
6. **Keep Bluetooth speaker awake** (off by default): portable speakers drop the
   connection after a few minutes of silence, so the next bell would come faintly
   from the tablet instead. Turn this on when ringing through an external speaker
   and it holds the link open with a **silent keep-alive signal** between bells —
   near-digital-silence (~−84 dBFS), the same trick as the "silence + bells" loops
   centres play, which keep the link alive by streaming continuously rather than
   by being loud. It does not touch the bells themselves — they still fire via the
   alarm system, unchanged; this is just a separate silent stream on the same output.

Keep the phone plugged in and the bells will play all day on their own.

## Build

```
./gradlew assembleDebug
```

**Your own sound.** The bell-sound card also takes an mp3 or wav file from the phone
("Choose file"). It stands in for the single strike; a three-bell slot plays it three
times over. The file is copied into the app's private files, so it stays readable from an
alarm after a reboot (a picked document's grant would not).

Five selectable bell sounds (`app/src/main/res/raw/bell_*.mp3`) — singing bell,
Tibetan E♭ bowl, gong bowl, Satipanya, and enpleineconscience.ch. Each is one
bowl struck three times, the strike allowed to ring out before the next. Each
also ships a single-strike cut (`bell_*_one.mp3`) for slots set to one bell. All
ten are loudness-matched to −20 LUFS, so neither switching bowl nor switching
between one and three strikes changes the volume in the room.

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

The single-strike cuts are the same audio, one strike long. For the first three
that is exactly the gained bowl in `normalized-bowls/`, before it was concatenated
— so it is copied straight across. Satipanya has no separate single: its first
strike runs 0–16 s of the natural recording, and because that recording swells
(its three strikes peak at −12.7, −9.6 and −6.8 dBFS), the first alone sits ~4 dB
under the other bowls and is gained back to the shared −20 LUFS:

```
ffmpeg -i app/src/main/res/raw/bell_satipanya.mp3 \
  -af "atrim=0:16,volume=4.2dB,afade=t=out:st=15:d=1.0,aformat=channel_layouts=stereo" \
  -ar 44100 -b:a 192k app/src/main/res/raw/bell_satipanya_one.mp3
```

enpleineconscience.ch comes from `enpleineconscience.wav`, a mono 16-bit
recording of the centre's own bowl struck three times (at 0 s, 4.2 s and 7.6 s —
a smaller bowl with a shorter ring, so its strikes come closer together than
Satipanya's). Like Satipanya it is a natural three-strike recording and is used
whole: the raw file sits at −32 LUFS, so it is gained +12 dB, trimmed of its
trailing room noise (the bowl is below −60 dBFS by 12 s), and converted to
stereo:

```
ffmpeg -i enpleineconscience.wav \
  -af "volume=12.0dB,atrim=0:15.0,afade=t=out:st=14.0:d=1.0,aformat=channel_layouts=stereo" \
  -ar 44100 -b:a 192k app/src/main/res/raw/bell_enpleineconscience.mp3
```

Its single is the first strike, cut just before the second lands at 4.205 s. By
then the first strike is 32 dB under its own peak, so the 1.2 s fade that ends
the cut is all but inaudible. The first strike is the loudest of the three, so
on its own it needs less gain than the whole recording to reach −20 LUFS:

```
ffmpeg -i enpleineconscience.wav \
  -af "atrim=0:4.18,volume=9.2dB,afade=t=out:st=3.0:d=1.18,aformat=channel_layouts=stereo" \
  -ar 44100 -b:a 192k app/src/main/res/raw/bell_enpleineconscience_one.mp3
```

No ads, no tracking, no accounts. Permissions are limited to exact alarms, boot
receipt, wake lock, foreground-service playback, and notifications.
