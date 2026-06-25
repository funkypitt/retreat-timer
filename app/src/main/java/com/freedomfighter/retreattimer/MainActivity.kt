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
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
                RetreatApp()
            }
        }
    }

    override fun onDestroy() {
        BellAudio.stop()
        super.onDestroy()
    }
}

@Composable
private fun RetreatApp() {
    var tab by remember { mutableStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))
            Header()
            TabRow(
                selectedTabIndex = tab,
                containerColor = Paper,
                contentColor = Accent,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Schedule", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.Schedule, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Library", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.LibraryMusic, null) })
            }
            if (tab == 0) ScheduleTab() else LibraryTab(onGoToSchedule = { tab = 0 })
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
            fontSize = 28.sp,
            color = Ink,
        )
        Text(
            "Bells and talks, on time, all day — even with no one present.",
            fontFamily = FontFamily.Serif,
            fontSize = 13.sp,
            color = Ink.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------- Schedule tab

@Composable
private fun ScheduleTab() {
    val ctx = LocalContext.current
    var bells by remember { mutableStateOf(BellStore.load(ctx)) }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }

    fun persist(newList: List<BellTime>) {
        bells = newList.sortedBy { it.minuteOfDay }
        BellStore.save(ctx, bells)
        BellScheduler.rescheduleAll(ctx)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ReliabilityCard(tick) }
        item { VolumeCard() }
        item { NextBellLine(bells, tick) }

        items(bells, key = { it.id }) { bell ->
            ScheduleRow(
                bell = bell,
                onToggle = { persist(bells.map { if (it.id == bell.id) it.copy(enabled = !it.enabled) else it }) },
                onEdit = {
                    pickTime(ctx, bell.hour, bell.minute) { h, m ->
                        persist(bells.map { if (it.id == bell.id) it.copy(hour = h, minute = m) else it })
                    }
                },
                onDelete = { persist(bells.filterNot { it.id == bell.id }) },
                onPlay = {
                    if (bell.isTalk) BellService.playTalk(ctx, bell.talkUri!!, bell.talkTitle ?: "Dharma talk")
                    else BellAudio.playTest(ctx)
                },
            )
        }

        item { AddBellButton(bells) { newBell -> persist(bells + newBell) } }
        item { FooterNote() }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ScheduleRow(
    bell: BellTime,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        bell.time(),
                        fontFamily = FontFamily.Serif,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bell.enabled) Ink else Ink.copy(alpha = 0.35f),
                    )
                }
                Text(
                    if (bell.isTalk) "🎧 ${bell.talkTitle}" else "🔔 Three bells",
                    fontSize = 12.sp,
                    color = if (bell.enabled) Accent else Ink.copy(alpha = 0.3f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Accent)
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

/** The "+" that drops the next bell exactly one hour after the last (07:45 →
 *  08:45). With no bells yet it seeds a sensible 07:00. */
@Composable
private fun AddBellButton(bells: List<BellTime>, onAdd: (BellTime) -> Unit) {
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
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

// ----------------------------------------------------------------- Library tab

@Composable
private fun LibraryTab(onGoToSchedule: () -> Unit) {
    val ctx = LocalContext.current
    var talks by remember { mutableStateOf(BellStore.loadTalks(ctx)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Persist read access so scheduled playback works after a reboot.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val title = queryDisplayName(ctx, uri)
            val talk = DharmaTalk(BellStore.nextId(ctx), uri.toString(), title)
            talks = talks + talk
            BellStore.saveTalks(ctx, talks)
        }
    }

    fun removeTalk(talk: DharmaTalk) {
        talks = talks.filterNot { it.id == talk.id }
        BellStore.saveTalks(ctx, talks)
        // Drop any scheduled item that pointed at this talk, then re-arm alarms.
        val remaining = BellStore.load(ctx).filterNot { it.talkUri == talk.uri }
        BellStore.save(ctx, remaining)
        BellScheduler.rescheduleAll(ctx)
    }

    fun scheduleTalk(talk: DharmaTalk) {
        val now = java.util.Calendar.getInstance()
        pickTime(ctx, now.get(java.util.Calendar.HOUR_OF_DAY), 0) { h, m ->
            val item = BellTime(
                id = BellStore.nextId(ctx), hour = h, minute = m, enabled = true,
                talkUri = talk.uri, talkTitle = talk.title,
            )
            val updated = BellStore.load(ctx) + item
            BellStore.save(ctx, updated)
            BellScheduler.rescheduleAll(ctx)
            onGoToSchedule()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Add dharma talks from your phone, play them, or schedule one to play " +
                    "at a set time — exactly like the bells.",
                fontSize = 13.sp, color = Ink.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { picker.launch(arrayOf("audio/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add dharma talk (pick MP3)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (talks.isEmpty()) {
            item {
                Text(
                    "No talks yet.",
                    fontFamily = FontFamily.Serif, fontSize = 15.sp,
                    color = Ink.copy(alpha = 0.5f), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }

        items(talks, key = { it.id }) { talk ->
            TalkRow(
                talk = talk,
                onPlay = { BellService.playTalk(ctx, talk.uri, talk.title) },
                onSchedule = { scheduleTalk(talk) },
                onRemove = { removeTalk(talk) },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TalkRow(
    talk: DharmaTalk,
    onPlay: () -> Unit,
    onSchedule: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                talk.title,
                fontFamily = FontFamily.Serif, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Accent)
                    Spacer(Modifier.width(2.dp)); Text("Play", color = Accent)
                }
                TextButton(onClick = onSchedule) {
                    Icon(Icons.Filled.Schedule, null, tint = Accent)
                    Spacer(Modifier.width(2.dp)); Text("Schedule", color = Accent)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Ink.copy(alpha = 0.4f))
                }
            }
        }
    }
}

// ------------------------------------------------------------- shared sections

/** Green when everything that guarantees playback is in place; amber with one-tap
 *  fixes otherwise. The teacher's "I can leave now" confidence light. */
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
                if (allOk) "✓ Ready — bells and talks will play on time" else "⚠ One step left for guaranteed playback",
                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = if (allOk) GoodGreen else WarnAmber,
            )
            Text(
                if (allOk)
                    "The phone can be locked or asleep all day. Keep it charged and everything plays without anyone present."
                else
                    "Tap below so Android never pauses the app. Without this, deep sleep could delay playback.",
                fontSize = 13.sp, color = Ink.copy(alpha = 0.8f),
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

/** Alarm-volume slider. Both live playback and the Test/Play buttons use this
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
                Text("Volume", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { BellAudio.playTest(ctx) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(4.dp)); Text("Test bells", color = Accent)
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
                "Bells and talks play at this exact level. Press Test bells to hear it.",
                fontSize = 12.sp, color = Ink.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NextBellLine(bells: List<BellTime>, tick: Long) {
    val ctx = LocalContext.current
    val upcoming = remember(bells, tick) { BellScheduler.nextUpcoming(ctx) }
    val text = if (upcoming == null) {
        "Nothing scheduled yet — add a bell below or schedule a talk."
    } else {
        val mins = TimeUnit.MILLISECONDS.toMinutes((upcoming.second - System.currentTimeMillis()).coerceAtLeast(0))
        val rel = if (mins >= 60) "in ${mins / 60}h ${mins % 60}min" else "in ${mins}min"
        val what = if (upcoming.first.isTalk) "🎧 ${upcoming.first.talkTitle}" else "🔔 three bells"
        "Next at ${upcoming.first.time()} — $what — $rel"
    }
    Text(
        text,
        fontFamily = FontFamily.Serif, fontSize = 14.sp, color = Accent,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FooterNote() {
    Text(
        "Tip: leave the phone plugged in. Each bell entry plays three singing-bowl " +
            "strikes; scheduled talks play your chosen recording.",
        fontSize = 12.sp, color = Ink.copy(alpha = 0.55f), textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun pickTime(ctx: Context, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(
        ctx, { _, h, m -> onPicked(h, m) },
        hour, minute, DateFormat.is24HourFormat(ctx),
    ).show()
}

/** Human-readable name for a picked file, with the .mp3 extension stripped. */
private fun queryDisplayName(ctx: Context, uri: Uri): String {
    var name: String? = null
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    }
    val raw = name ?: uri.lastPathSegment ?: "Dharma talk"
    return raw.substringBeforeLast('.').ifBlank { raw }
}
