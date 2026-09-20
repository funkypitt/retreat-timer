# Retreat Timer — notes

Reference material moved out of the README.

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

## Keep Bluetooth speaker awake

Off by default. Portable speakers drop the
connection after a few minutes of silence, so the next bell would come faintly
from the tablet instead. Turn this on when ringing through an external speaker
and it holds the link open with a **silent keep-alive signal** between bells —
near-digital-silence (~−84 dBFS), the same trick as the "silence + bells" loops
centres play, which keep the link alive by streaming continuously rather than
by being loud. It does not touch the bells themselves — they still fire via the
alarm system, unchanged; this is just a separate silent stream on the same output.

## Bell sounds

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
