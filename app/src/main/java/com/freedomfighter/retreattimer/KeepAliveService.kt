package com.freedomfighter.retreattimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Random

/**
 * Keeps a Bluetooth speaker from going to sleep between bells.
 *
 * Many portable speakers drop the A2DP link after a few minutes of silence to
 * save battery. On a retreat there can be an hour of silence between rings, so
 * the speaker disconnects and the bell then comes out — faintly — of the
 * tablet's own speaker instead. This service emits a continuous, very quiet hiss
 * so the audio link never goes idle. It is exactly the trick behind the
 * "silence + bells" recordings many centres already play on a loop.
 *
 * It is entirely separate from the bell path: the bells still fire via
 * AlarmManager through [BellService] on the alarm stream, unchanged. This service
 * only adds a parallel faint stream on the same alarm routing, so both mix at the
 * speaker and the (loud) bell simply plays over the (inaudible) keep-alive. It is
 * opt-in and off by default.
 */
class KeepAliveService : Service() {

    @Volatile private var running = false
    private var audioThread: Thread? = null
    private var track: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Defensive: a sticky restart (null intent) or a stale start after the
        // toggle was switched off should not resurrect the hiss.
        if (!BellStore.keepSpeakerAwake(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startKeepAlive()
        // Sticky: if the OS ever kills it while the toggle is on, bring it back.
        return START_STICKY
    }

    private fun startKeepAlive() {
        if (running) return
        running = true

        startForegroundNotification()
        acquireWakeLock()

        audioThread = Thread({ streamNoise() }, "RetreatTimer-KeepAlive").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Generate and write low-amplitude white noise forever. A blocking write on
     *  a STREAM-mode AudioTrack paces itself to real time, so this is a steady,
     *  cheap trickle rather than a busy loop. */
    private fun streamNoise() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)

        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Same routing as the bells, so it follows the speaker the
                        // bells will use and rides the alarm volume already set.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: run { stopSelf(); return }

        track = t
        val buf = ShortArray(1024)
        val rng = Random()
        runCatching {
            t.play()
            while (running) {
                for (i in buf.indices) {
                    // Uniform noise in [-AMPLITUDE, +AMPLITUDE]. AMPLITUDE is the
                    // one field-tunable knob: raise it if a stubborn speaker still
                    // sleeps, lower it if the hiss is noticeable in a quiet hall.
                    buf[i] = (rng.nextInt(AMPLITUDE * 2 + 1) - AMPLITUDE).toShort()
                }
                if (t.write(buf, 0, buf.size) < 0) break // device error → give up
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatTimer:keepalive").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.keepalive_channel_desc) },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(R.drawable.ic_bell)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { audioThread?.join(500) }
        audioThread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.retreattimer.KEEPALIVE_STOP"
        private const val CHANNEL_ID = "retreat_keepalive"
        private const val NOTIF_ID = 8
        private const val SAMPLE_RATE = 44_100

        /** Peak sample value of the keep-alive noise (out of 32767). ~80 is a
         *  faint hiss — enough energy to keep a speaker's link alive, low enough
         *  to be unobtrusive. The single knob to tune per speaker. */
        private const val AMPLITUDE = 80

        /** Turn the keep-alive on or off, persisting the choice so it survives a
         *  reboot (see [BootReceiver]). */
        fun setEnabled(ctx: Context, enabled: Boolean) {
            BellStore.setKeepSpeakerAwake(ctx, enabled)
            if (enabled) start(ctx) else ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        /** Start the service if the toggle is on. Safe to call repeatedly (e.g.
         *  from boot); a second start is a no-op while already running. */
        fun start(ctx: Context) {
            if (!BellStore.keepSpeakerAwake(ctx)) return
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
