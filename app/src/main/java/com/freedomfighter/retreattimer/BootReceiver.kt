package com.freedomfighter.retreattimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AlarmManager alarms do not survive a reboot. This receiver re-arms the entire
 * schedule after the device restarts (and after a time/timezone change or an app
 * update), so the retreat keeps running even if the phone was rebooted overnight.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BellScheduler.rescheduleAll(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Resume keeping the Bluetooth speaker awake if the teacher had it on,
                // so a reboot overnight doesn't quietly leave the speaker free to
                // disconnect. Android 15+ refuses a mediaPlayback foreground service
                // started straight from a boot broadcast, so there it is brought back
                // by an exact alarm a minute later, which is an allowed path.
                if (Build.VERSION.SDK_INT >= 35) KeepAliveService.resumeLater(context)
                else KeepAliveService.start(context)
            }
            // A time or timezone change only moves the bells; starting a service
            // from those broadcasts is not allowed in the background.
        }
    }
}
