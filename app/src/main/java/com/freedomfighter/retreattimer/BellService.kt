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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that actually plays the three bells. Running as a foreground
 * service (type mediaPlayback) means Android will not kill it mid-ring, and a
 * partial wake lock guarantees the CPU stays awake until the full ~20s recording
 * has finished even if the device was in deep sleep.
 */
class BellService : Service() {

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBellForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        applyAlarmVolume()
        ring()
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ringing_title))
            .setContentText(getString(R.string.ringing_text))
            .setSmallIcon(R.drawable.ic_bell)
            .setContentIntent(tap)
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

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatTimer:ring").apply {
            setReferenceCounted(false)
            acquire(60_000L) // generous ceiling; released as soon as playback ends
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

    private fun ring() {
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                val afd = resources.openRawResourceFd(R.raw.three_bells)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
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
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "retreat_ringing"
        private const val NOTIF_ID = 7
    }
}
