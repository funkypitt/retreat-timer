package com.freedomfighter.retreattimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms do not survive a reboot. This receiver re-arms the entire
 * schedule after the device restarts (and after a time/timezone change or an app
 * update), so the retreat keeps running even if the phone was rebooted overnight.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BellScheduler.rescheduleAll(context)
        // Resume keeping the Bluetooth speaker awake if the teacher had it on, so
        // a reboot overnight doesn't quietly leave the speaker free to disconnect.
        KeepAliveService.start(context)
    }
}
