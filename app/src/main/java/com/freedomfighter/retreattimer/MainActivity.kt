package com.freedomfighter.retreattimer

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.concurrent.TimeUnit

private val Paper = Color(0xFFF7F3EA)
private val Ink = Color(0xFF2B2620)
private val Accent = Color(0xFF8C5A2B)
private val GoodGreen = Color(0xFF2E7D52)
private val WarnAmber = Color(0xFF9A6A12)
private val CardBg = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* status reread on resume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Paper)) {
                RetreatScreen()
            }
        }
    }

    override fun onDestroy() {
        BellAudio.stop()
        super.onDestroy()
    }
}

@Composable
private fun RetreatScreen() {
    val ctx = LocalContext.current
    var bells by remember { mutableStateOf(BellStore.load(ctx)) }
    // Tick once a minute so "next bell in …" and reliability checks stay fresh.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }

    fun persist(newList: List<BellTime>) {
        bells = newList.sortedBy { it.minuteOfDay }
        BellStore.save(ctx, bells)
        BellScheduler.rescheduleAll(ctx)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(20.dp)); Header() }
            item { ReliabilityCard(tick) }
            item { VolumeCard() }
            item { NextBellLine(bells, tick) }

            items(bells, key = { it.id }) { bell ->
                BellRow(
                    bell = bell,
                    onToggle = { persist(bells.map { if (it.id == bell.id) it.copy(enabled = !it.enabled) else it }) },
                    onEdit = {
                        pickTime(ctx, bell.hour, bell.minute) { h, m ->
                            persist(bells.map { if (it.id == bell.id) it.copy(hour = h, minute = m) else it })
                        }
                    },
                    onDelete = { persist(bells.filterNot { it.id == bell.id }) },
                    onTest = { BellAudio.playTest(ctx) },
                )
            }

            item { AddButton(bells) { newBell -> persist(bells + newBell) } }
            item { FooterNote() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Retreat Timer",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = Ink,
        )
        Text(
            "Three bells, on time, all day — even with no one in the room.",
            fontFamily = FontFamily.Serif,
            fontSize = 14.sp,
            color = Ink.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

/** Green when everything that guarantees ringing is in place; amber with one-tap
 *  fixes otherwise. This is the teacher's "I can leave now" confidence light. */
@Composable
private fun ReliabilityCard(tick: Long) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    val battOk = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifOk = remember(tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else true
    }
    val allOk = battOk && notifOk

    Card(
        colors = CardDefaults.cardColors(containerColor = if (allOk) GoodGreen.copy(alpha = 0.12f) else WarnAmber.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (allOk) "✓ Ready — the bells will ring on time" else "⚠ One step left for guaranteed ringing",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (allOk) GoodGreen else WarnAmber,
            )
            Text(
                if (allOk)
                    "The phone can be locked or asleep all day. Keep it charged and the bells will play without anyone present."
                else
                    "Tap below so Android never pauses the app. Without this, deep sleep could delay a bell.",
                fontSize = 13.sp,
                color = Ink.copy(alpha = 0.8f),
            )
            if (!battOk) {
                Button(
                    onClick = {
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                        runCatching { ctx.startActivity(i) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarnAmber),
                ) { Text("Allow background running") }
            }
            if (!notifOk) {
                Button(
                    onClick = {
                        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        runCatching { ctx.startActivity(i) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarnAmber),
                ) { Text("Allow notifications") }
            }
        }
    }
}

/** Alarm-volume slider. Both the live ring and the Test buttons play through this
 *  exact level, so the slider is a true preview of room loudness. */
@Composable
private fun VolumeCard() {
    val ctx = LocalContext.current
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    val initial = BellStore.alarmVolume(ctx).let {
        if (it < 0) am.getStreamVolume(AudioManager.STREAM_ALARM) else it
    }
    var vol by remember { mutableStateOf(initial.toFloat()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bell volume", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { BellAudio.playTest(ctx) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Test", color = Accent)
                }
            }
            Slider(
                value = vol,
                onValueChange = {
                    vol = it
                    val v = it.toInt().coerceIn(0, max)
                    BellStore.setAlarmVolume(ctx, v)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
                },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
            Text(
                "Press Test to hear the three bells at this exact level.",
                fontSize = 12.sp,
                color = Ink.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NextBellLine(bells: List<BellTime>, tick: Long) {
    val ctx = LocalContext.current
    val upcoming = remember(bells, tick) { BellScheduler.nextUpcoming(ctx) }
    val text = if (upcoming == null) {
        "No bells scheduled yet — add the first below."
    } else {
        val mins = TimeUnit.MILLISECONDS.toMinutes((upcoming.second - System.currentTimeMillis()).coerceAtLeast(0))
        val h = mins / 60
        val m = mins % 60
        val rel = when {
            h > 0 -> "in ${h}h ${m}min"
            else -> "in ${m}min"
        }
        "Next bell at ${upcoming.first.label()} — $rel"
    }
    Text(
        text,
        fontFamily = FontFamily.Serif,
        fontSize = 15.sp,
        color = Accent,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BellRow(
    bell: BellTime,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tap the time to edit it.
            TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Text(
                    bell.label(),
                    fontFamily = FontFamily.Serif,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bell.enabled) Ink else Ink.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onTest) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Test this bell", tint = Accent)
            }
            Switch(
                checked = bell.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedThumbColor = GoodGreen, checkedTrackColor = GoodGreen.copy(alpha = 0.4f)),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Ink.copy(alpha = 0.4f))
            }
        }
    }
}

/** The "+" that, per the teacher's workflow, drops the next ring exactly one hour
 *  after the last one (07:45 → 08:45). With no bells yet it seeds a sensible 07:00. */
@Composable
private fun AddButton(bells: List<BellTime>, onAdd: (BellTime) -> Unit) {
    val ctx = LocalContext.current
    Button(
        onClick = {
            val (h, m) = if (bells.isEmpty()) {
                7 to 0
            } else {
                val last = bells.maxByOrNull { it.minuteOfDay }!!
                ((last.hour + 1) % 24) to last.minute
            }
            onAdd(BellTime(id = BellStore.nextId(ctx), hour = h, minute = m, enabled = true))
        },
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            if (bells.isEmpty()) "Add first bell" else "Add bell (1 hour later)",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        "Tip: leave the phone plugged in. Each entry plays a recording of three " +
            "singing-bowl strikes — the signal to begin or end a sitting.",
        fontSize = 12.sp,
        color = Ink.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun pickTime(ctx: Context, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(
        ctx,
        { _, h, m -> onPicked(h, m) },
        hour, minute,
        DateFormat.is24HourFormat(ctx),
    ).show()
}
