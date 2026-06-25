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

/**
 * Foreground service that plays the scheduled audio — either the built-in three
 * bells or an imported dharma talk. Running as a foreground service (type
 * mediaPlayback) means Android will not kill it mid-playback, and the
 * MediaPlayer's own wake mode keeps the CPU awake for the whole recording even
 * if the device was in deep sleep. A short startup wake lock bridges the gap
 * between the alarm firing and playback actually beginning.
 */
class BellService : Service() {

    private var player: MediaPlayer? = null
    private var startupLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }

        val talkUri = intent?.getStringExtra(BellScheduler.EXTRA_TALK_URI)
        val talkTitle = intent?.getStringExtra(BellScheduler.EXTRA_TALK_TITLE)

        startBellForeground(NOTIF_ID, buildNotification(talkTitle))
        acquireStartupLock()
        applyAlarmVolume()
        play(talkUri)
        return START_NOT_STICKY
    }

    private fun buildNotification(talkTitle: String?): android.app.Notification {
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
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BellService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(talkTitle ?: getString(R.string.ringing_title))
            .setContentText(
                if (talkTitle != null) getString(R.string.playing_text)
                else getString(R.string.ringing_text),
            )
            .setSmallIcon(R.drawable.ic_bell)
            .setContentIntent(tap)
            .addAction(0, getString(R.string.stop), stop)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
    }

    private fun startBellForeground(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }

    /** Holds the CPU for a few seconds while MediaPlayer prepares; once playback
     *  starts, [MediaPlayer.setWakeMode] keeps the lock for the whole recording. */
    private fun acquireStartupLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        startupLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatTimer:start").apply {
            setReferenceCounted(false)
            acquire(30_000L)
        }
    }

    /** Force STREAM_ALARM to the level the teacher tested with, so what they heard
     *  when pressing "Test" is exactly what the room hears now. */
    private fun applyAlarmVolume() {
        val desired = BellStore.alarmVolume(this)
        if (desired < 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, desired.coerceIn(0, max), 0)
    }

    private fun play(talkUri: String?) {
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setWakeMode(this@BellService, PowerManager.PARTIAL_WAKE_LOCK)
                if (talkUri != null) {
                    setDataSource(this@BellService, Uri.parse(talkUri))
                } else {
                    val afd = resources.openRawResourceFd(R.raw.three_bells)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ -> finish(); true }
                prepare()
                start()
            }
        }.onFailure { finish() }
    }

    private fun finish() {
        runCatching { player?.release() }
        player = null
        runCatching { if (startupLock?.isHeld == true) startupLock?.release() }
        startupLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.retreattimer.STOP"
        private const val CHANNEL_ID = "retreat_ringing"
        private const val NOTIF_ID = 7

        /** Play a dharma talk now, through the foreground service so it keeps
         *  going if the screen locks and can be stopped from the notification. */
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

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BellService::class.java).setAction(ACTION_STOP))
        }
    }
}
