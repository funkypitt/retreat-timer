package com.freedomfighter.retreattimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that plays the scheduled audio — either the built-in three
 * bells or an imported dharma talk — with full transport control. Running as a
 * foreground service (type mediaPlayback) means Android will not kill it
 * mid-playback, and the MediaPlayer's wake mode keeps the CPU awake for the whole
 * recording even in deep sleep. The notification carries Play/Pause and Stop
 * actions plus a progress bar; the in-app bar mirrors it via [PlaybackState].
 */
class BellService : Service() {

    private var player: MediaPlayer? = null
    private var startupLock: PowerManager.WakeLock? = null
    private var currentTitle: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // A transport action needs a live player. If the recording ended in the
        // same instant the button was tapped, stop rather than leaving a started
        // service sitting there with no notification behind it.
        if (action in TRANSPORT && player == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> finish()
            ACTION_TOGGLE -> togglePause()
            ACTION_BACK10 -> seekBy(-10_000)
            ACTION_FWD10 -> seekBy(+10_000)
            ACTION_RESTART -> seekTo(0)
            ACTION_SEEK -> seekTo(intent.getIntExtra(EXTRA_POSITION_MS, 0))
            else -> startPlayback(intent)
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent?) {
        val talkUri = intent?.getStringExtra(BellScheduler.EXTRA_TALK_URI)
        val talkTitle = intent?.getStringExtra(BellScheduler.EXTRA_TALK_TITLE)
        currentTitle = talkTitle ?: getString(R.string.ringing_title)

        // Replace any in-progress playback.
        runCatching { player?.release() }
        player = null

        startBellForeground(NOTIF_ID, buildNotification(playing = true, position = 0, duration = 0))
        acquireStartupLock()
        applyVolume(isTalk = talkUri != null)

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                // Keep the audio on the room's Bluetooth speaker instead of also
                // leaking out of the phone, which the alarm stream does on many
                // devices (see [bluetoothOutput]).
                bluetoothOutput(this@BellService)?.let { setPreferredDevice(it) }
                setWakeMode(this@BellService, PowerManager.PARTIAL_WAKE_LOCK)
                if (talkUri != null) {
                    setDataSource(this@BellService, Uri.parse(talkUri))
                } else {
                    val afd = resources.openRawResourceFd(BellSounds.selected(this@BellService).rawRes)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ -> finish(); true }
                prepare()
                start()
            }
            PlaybackState.title = currentTitle
            PlaybackState.durationMs = player?.duration ?: 0
            PlaybackState.positionMs = 0
            PlaybackState.isPlaying = true
            startTicker()
        }.onFailure { finish() }
    }

    private fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            PlaybackState.isPlaying = false
            ticker?.cancel()
            pushNotification()
        } else {
            p.start()
            PlaybackState.isPlaying = true
            startTicker()
            pushNotification()
        }
    }

    /** Jump to an absolute position. This is what makes a recording's volume
     *  testable: talks often open with a long silence, and without seeking the
     *  only way to reach the speech is to wait it out. */
    private fun seekTo(positionMs: Int) {
        val p = player ?: return
        val target = positionMs.coerceIn(0, runCatching { p.duration }.getOrDefault(0))
        runCatching { p.seekTo(target) }
        PlaybackState.positionMs = target
        pushNotification()
    }

    private fun seekBy(deltaMs: Int) {
        val p = player ?: return
        seekTo(runCatching { p.currentPosition }.getOrDefault(0) + deltaMs)
    }

    /** Drive the progress bar / remaining-time readout while playing. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                PlaybackState.positionMs = runCatching { p.currentPosition }.getOrDefault(0)
                PlaybackState.durationMs = runCatching { p.duration }.getOrDefault(0)
                pushNotification()
                delay(500)
            }
        }
    }

    // ---- notification ----

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(PlaybackState.isPlaying, PlaybackState.positionMs, PlaybackState.durationMs),
        )
    }

    private fun buildNotification(playing: Boolean, position: Int, duration: Int): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_ringing),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.channel_ringing_desc) }
            nm.createNotificationChannel(channel)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = servicePendingIntent(2, ACTION_TOGGLE)
        val stop = servicePendingIntent(1, ACTION_STOP)
        val back10 = servicePendingIntent(3, ACTION_BACK10)
        val fwd10 = servicePendingIntent(4, ACTION_FWD10)

        val remaining = (duration - position).coerceAtLeast(0)
        val text = if (duration > 0) {
            "${formatClock(position)} / ${formatClock(duration)}  ·  ${formatClock(remaining)} left"
        } else {
            getString(if (currentTitle == getString(R.string.ringing_title)) R.string.ringing_text else R.string.playing_text)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_rew, getString(R.string.back10), back10)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.pause else R.string.play),
                toggle,
            )
            .addAction(android.R.drawable.ic_media_ff, getString(R.string.fwd10), fwd10)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stop)

        if (duration > 0) builder.setProgress(duration, position, false)
        return builder.build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, BellService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun startBellForeground(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }

    private fun acquireStartupLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        startupLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatTimer:start").apply {
            setReferenceCounted(false)
            acquire(30_000L)
        }
    }

    /** Force STREAM_ALARM to the level the teacher tested with, so what they heard
     *  when pressing "Test" is exactly what the room hears now. Talks and bells
     *  carry their own levels — a spoken recording normally needs to be much
     *  louder than a bowl strike to fill the same room. */
    private fun applyVolume(isTalk: Boolean) {
        val desired = if (isTalk) BellStore.talkVolume(this) else BellStore.alarmVolume(this)
        if (desired < 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, desired.coerceIn(0, max), 0)
    }

    private fun finish() {
        ticker?.cancel()
        runCatching { player?.release() }
        player = null
        runCatching { if (startupLock?.isHeld == true) startupLock?.release() }
        startupLock = null
        PlaybackState.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        finish()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.retreattimer.STOP"
        const val ACTION_TOGGLE = "com.freedomfighter.retreattimer.TOGGLE"
        const val ACTION_BACK10 = "com.freedomfighter.retreattimer.BACK10"
        const val ACTION_FWD10 = "com.freedomfighter.retreattimer.FWD10"
        const val ACTION_RESTART = "com.freedomfighter.retreattimer.RESTART"
        const val ACTION_SEEK = "com.freedomfighter.retreattimer.SEEK"
        private const val EXTRA_POSITION_MS = "position_ms"

        /** Actions that act on an already-playing recording. */
        private val TRANSPORT = setOf(ACTION_TOGGLE, ACTION_BACK10, ACTION_FWD10, ACTION_RESTART, ACTION_SEEK)
        private const val CHANNEL_ID = "retreat_ringing"
        private const val NOTIF_ID = 7

        /** Play a dharma talk now, through the foreground service so it keeps
         *  going if the screen locks and can be controlled from the notification. */
        fun playTalk(ctx: Context, uri: String, title: String) {
            val i = Intent(ctx, BellService::class.java).apply {
                putExtra(BellScheduler.EXTRA_TALK_URI, uri)
                putExtra(BellScheduler.EXTRA_TALK_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun toggle(ctx: Context) = send(ctx, ACTION_TOGGLE)

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        fun back10(ctx: Context) = send(ctx, ACTION_BACK10)

        fun fwd10(ctx: Context) = send(ctx, ACTION_FWD10)

        fun restart(ctx: Context) = send(ctx, ACTION_RESTART)

        /** Jump straight to [positionMs] — used by the draggable progress bar. */
        fun seekTo(ctx: Context, positionMs: Int) {
            ctx.startService(
                Intent(ctx, BellService::class.java)
                    .setAction(ACTION_SEEK)
                    .putExtra(EXTRA_POSITION_MS, positionMs),
            )
        }

        private fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, BellService::class.java).setAction(action))
        }
    }
}
