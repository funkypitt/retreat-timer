package com.freedomfighter.retreattimer

import android.Manifest
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.io.File
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
            Box(Modifier.weight(1f)) {
                if (tab == 0) ScheduleTab() else LibraryTab(onGoToSchedule = { tab = 0 })
            }
            NowPlayingBar()
        }
    }
}

/**
 * Persistent mini-player shown on both tabs whenever [BellService] is playing.
 * The progress bar is draggable, which is the point: dharma talks often open
 * with several minutes of silence, and checking that the volume carries means
 * reaching the speech without sitting through the gap. ±10s and restart handle
 * the fine adjustments once you are there.
 */
@Composable
private fun NowPlayingBar() {
    val ctx = LocalContext.current
    val title = PlaybackState.title ?: return
    val duration = PlaybackState.durationMs
    val position = PlaybackState.positionMs
    val playing = PlaybackState.isPlaying

    // While a drag is in progress the thumb follows the finger, not the ticker,
    // which would otherwise yank it back twice a second.
    var scrubMs by remember { mutableStateOf<Int?>(null) }
    val shown = scrubMs ?: position
    val remaining = (duration - shown).coerceAtLeast(0)

    Surface(color = Ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (duration > 0) {
                Slider(
                    value = shown.toFloat(),
                    onValueChange = { scrubMs = it.toInt() },
                    onValueChangeFinished = {
                        scrubMs?.let { BellService.seekTo(ctx, it) }
                        scrubMs = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFE7C9A0),
                        activeTrackColor = Color(0xFFE7C9A0),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${formatClock(shown)} elapsed",
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "−${formatClock(remaining)} left",
                        color = Color(0xFFE7C9A0),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
            } else {
                Text("Playing…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = { BellService.restart(ctx) }) {
                    Icon(Icons.Filled.SkipPrevious, "Back to beginning", tint = Color.White)
                }
                IconButton(onClick = { BellService.back10(ctx) }) {
                    Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White)
                }
                FilledIconButton(
                    onClick = { BellService.toggle(ctx) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White, modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { BellService.fwd10(ctx) }) {
                    Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White)
                }
                IconButton(onClick = { BellService.stop(ctx) }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color.White)
                }
            }
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
    val talks = remember { BellStore.loadTalks(ctx) }
    var tick by remember { mutableStateOf(0L) }
    var timeRequest by remember { mutableStateOf<TimeRequest?>(null) }
    LaunchedEffect(Unit) {
        while (true) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }

    fun persist(newList: List<BellTime>) {
        bells = newList.sortedBy { it.minuteOfDay }
        BellStore.save(ctx, bells)
        BellScheduler.rescheduleAll(ctx)
    }

    timeRequest?.let { req ->
        TimeDialog(
            initialHour = req.hour,
            initialMinute = req.minute,
            initialSingle = req.single,
            onDismiss = { timeRequest = null },
            onPicked = { h, m, s -> timeRequest = null; req.onPicked(h, m, s) },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ReliabilityCard(tick) }
        item { BellSoundCard() }
        item { LevelsHeader() }
        item { BellVolumeCard() }
        item { TalkVolumeCard(bells, talks, tick) }
        item { KeepSpeakerAwakeCard(tick) }
        item { NextBellLine(bells, tick) }

        items(bells, key = { it.id }) { bell ->
            ScheduleRow(
                bell = bell,
                onToggle = { persist(bells.map { if (it.id == bell.id) it.copy(enabled = !it.enabled) else it }) },
                onEdit = {
                    // A talk keeps no strike count, so it opens the plain picker.
                    timeRequest = TimeRequest(
                        bell.hour, bell.minute,
                        single = if (bell.isTalk) null else bell.singleStrike,
                    ) { h, m, s ->
                        persist(
                            bells.map {
                                if (it.id == bell.id) it.copy(hour = h, minute = m, singleStrike = s) else it
                            },
                        )
                    }
                },
                onDelete = { persist(bells.filterNot { it.id == bell.id }) },
                onPlay = {
                    if (bell.isTalk) BellService.playTalk(ctx, bell.talkUri!!, bell.talkTitle ?: "Dharma talk")
                    else BellAudio.playTest(ctx, BellSounds.rawRes(ctx, bell.singleStrike))
                },
            )
        }

        item {
            AddBellButton(bells.isEmpty()) {
                // Seeded with the current time, never an offset from the last bell:
                // every bell is a time the teacher chose on purpose.
                val (h, m) = nowHourMinute()
                timeRequest = TimeRequest(h, m, single = false) { ph, pm, ps ->
                    persist(
                        bells + BellTime(
                            id = BellStore.nextId(ctx), hour = ph, minute = pm,
                            enabled = true, singleStrike = ps,
                        ),
                    )
                }
            }
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            bell.time(),
                            fontFamily = FontFamily.Serif,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (bell.enabled) Ink else Ink.copy(alpha = 0.35f),
                        )
                    }
                    if (!bell.isTalk) StrikeBadge(bell.singleStrike, bell.enabled)
                }
                Text(
                    when {
                        bell.isTalk -> "🎧 ${bell.talkTitle}"
                        bell.singleStrike -> "🔔 One bell"
                        else -> "🔔 Three bells"
                    },
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

/** The "×1" / "×3" mark beside a bell's time, so one glance down the schedule
 *  shows which slots ring once and which ring three times — without opening any
 *  of them. Talks carry no strike count and get no badge. */
@Composable
private fun StrikeBadge(single: Boolean, enabled: Boolean) {
    val tint = if (enabled) Accent else Ink.copy(alpha = 0.3f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Text(
            if (single) "×1" else "×3",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Opens the time picker so the teacher chooses the time for every bell. It
 *  deliberately offers no default offset — a retreat schedule is rarely evenly
 *  spaced, so a guessed time would only have to be corrected. */
@Composable
private fun AddBellButton(isFirst: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            if (isFirst) "Add first bell" else "Add bell",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

// ----------------------------------------------------------------- Library tab

@Composable
private fun LibraryTab(onGoToSchedule: () -> Unit) {
    val ctx = LocalContext.current
    var talks by remember { mutableStateOf(BellStore.loadTalks(ctx)) }
    var showKDrive by remember { mutableStateOf(false) }
    var showPodcast by remember { mutableStateOf(false) }
    var timeRequest by remember { mutableStateOf<TimeRequest?>(null) }

    // A background download saves straight to storage and bumps this counter;
    // reload so the freshly-downloaded talk shows up in the list.
    LaunchedEffect(DownloadState.libraryVersion) {
        talks = BellStore.loadTalks(ctx)
    }

    timeRequest?.let { req ->
        TimeDialog(
            initialHour = req.hour,
            initialMinute = req.minute,
            initialSingle = req.single,
            onDismiss = { timeRequest = null },
            onPicked = { h, m, s -> timeRequest = null; req.onPicked(h, m, s) },
        )
    }

    fun addTalk(talk: DharmaTalk) {
        talks = talks + talk
        BellStore.saveTalks(ctx, talks)
    }

    if (showKDrive) {
        KDriveDialog(
            existingTitles = talks.map { it.title }.toSet(),
            onDismiss = { showKDrive = false },
        )
    }

    if (showPodcast) {
        PodcastDialog(
            existingTitles = talks.map { it.title }.toSet(),
            onDismiss = { showPodcast = false },
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Persist read access so scheduled playback works after a reboot.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val title = queryDisplayName(ctx, uri)
            addTalk(DharmaTalk(BellStore.nextId(ctx), uri.toString(), title))
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
        val (nowH, nowM) = nowHourMinute()
        // A talk has no strike count, so the picker asks for a time only.
        timeRequest = TimeRequest(nowH, nowM) { h, m, _ ->
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
                "Add dharma talks from your phone, a kDrive share link or a podcast feed, " +
                    "play them, or schedule one to play at a set time — exactly like the bells.",
                fontSize = 13.sp, color = Ink.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { picker.launch(PICKABLE_TYPES) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add from this phone (MP3)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            OutlinedButton(
                onClick = { showKDrive = true },
                shape = RoundedCornerShape(16.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download from kDrive link", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            OutlinedButton(
                onClick = { showPodcast = true },
                shape = RoundedCornerShape(16.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.RssFeed, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download from podcast feed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

/** Choose which bowl rings for every bell entry. All four are loudness-matched,
 *  so switching is purely about timbre, not volume. How many times it strikes is
 *  set per bell entry, not here — see [StrikeChoice]. */
@Composable
private fun BellSoundCard() {
    val ctx = LocalContext.current
    var selectedKey by remember { mutableStateOf(BellSounds.selected(ctx).key) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Bell sound", fontWeight = FontWeight.SemiBold, color = Ink)
            BellSounds.ALL.forEach { sound ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = selectedKey == sound.key,
                        onClick = {
                            selectedKey = sound.key
                            BellStore.setBellSoundKey(ctx, sound.key)
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Accent),
                    )
                    Text(
                        sound.label,
                        fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { BellAudio.playTest(ctx, sound.rawRes) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Preview", tint = Accent)
                        Spacer(Modifier.width(2.dp)); Text("Preview", color = Accent)
                    }
                }
            }
            Text(
                "The chosen bowl rings out fully between strikes. Whether a slot rings " +
                    "three times or once is set on the bell itself — tap its time.",
                fontSize = 12.sp, color = Ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Bell trim. A software gain the bells play through, so it previews true room
 *  loudness on the phone speaker and over a Bluetooth speaker alike. */
@Composable
private fun BellVolumeCard() {
    val ctx = LocalContext.current
    VolumeCard(
        title = "Bells",
        testLabel = "Test bell",
        hint = "Trims the bells down from the phone/speaker volume. 100% = full. " +
            "Test rings one bell — enough to judge the level without sitting through three.",
        storedPct = BellStore.bellGain(ctx),
        onStore = { BellStore.setBellGain(ctx, it) },
        onLiveApply = { BellAudio.setGain(it) },
        // One strike, deliberately: this is a level check, and every recording is
        // loudness-matched, so three would only make the same point three times.
        onTest = { BellAudio.playTest(ctx, BellSounds.rawRes(ctx, single = true)) },
    )
}

/** Talk volume, deliberately separate from the bells: a spoken recording has to
 *  carry across the hall where a bowl strike only has to be noticed, so the two
 *  levels are almost never the same. Test plays a real talk — the next one
 *  scheduled if there is one — because speech is what has to be judged, and the
 *  slider keeps working while it plays. */
@Composable
private fun TalkVolumeCard(bells: List<BellTime>, talks: List<DharmaTalk>, tick: Long) {
    val ctx = LocalContext.current
    val sample = remember(bells, talks, tick) {
        bells.filter { it.isTalk && it.enabled }
            .minByOrNull { BellScheduler.nextTriggerMillis(it) }
            ?.let { it.talkUri!! to (it.talkTitle ?: "Dharma talk") }
            ?: talks.firstOrNull()?.let { it.uri to it.title }
    }
    VolumeCard(
        title = "Talks",
        testLabel = "Test talk",
        hint = if (sample != null) {
            "Trims talks independently of the bells, so speech and bowl strikes stay " +
                "matched. Test plays “${sample.second}”; adjust while it runs."
        } else {
            "Trims talks independently of the bells. Add a talk in the Library tab to test it."
        },
        storedPct = BellStore.talkGain(ctx),
        onStore = { BellStore.setTalkGain(ctx, it) },
        onLiveApply = { BellService.setGain(it) },
        onTest = sample?.let { (uri, title) -> { BellService.playTalk(ctx, uri, title) } },
    )
}

/** Lowest a trim can go — a mis-drag must never silence an unattended bell. */
private const val FLOOR_PCT = 5

/** Intro above the two trims, stating plainly that the master loudness is the
 *  phone's / speaker's own volume and these only trim down from it. */
@Composable
private fun LevelsHeader() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
        Text(
            "Levels",
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink,
        )
        Text(
            "Overall loudness follows the phone's volume — and, over Bluetooth, your speaker's. " +
                "These only trim bells and talks down from there, so the two stay matched through " +
                "an unattended day.",
            fontSize = 12.sp, color = Ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Shared trim card: a 0–100% software gain (see [gainScalar]). Overall loudness
 *  is the phone's / speaker's own volume; this only attenuates below it, and it is
 *  applied inside the player so it works over Bluetooth. [onLiveApply] re-trims a
 *  Test that is already playing, so dragging is heard immediately. */
@Composable
private fun VolumeCard(
    title: String,
    testLabel: String,
    hint: String,
    storedPct: Int,
    onStore: (Int) -> Unit,
    onLiveApply: (Int) -> Unit,
    onTest: (() -> Unit)?,
) {
    var pct by remember { mutableStateOf(storedPct.coerceIn(FLOOR_PCT, 100).toFloat()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                Text(
                    "${pct.toInt()}%", color = Ink.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                TextButton(onClick = { onTest?.invoke() }, enabled = onTest != null) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(4.dp)); Text(testLabel, color = Accent)
                }
            }
            Slider(
                value = pct,
                onValueChange = {
                    pct = it
                    val p = it.toInt().coerceIn(FLOOR_PCT, 100)
                    onStore(p)
                    onLiveApply(p)
                },
                valueRange = FLOOR_PCT.toFloat()..100f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
            Text(hint, fontSize = 12.sp, color = Ink.copy(alpha = 0.6f))
        }
    }
}

/** Opt-in switch that keeps a Bluetooth speaker from sleeping between bells by
 *  playing a faint continuous sound (see [KeepAliveService]). Off by default;
 *  only useful when ringing through an external speaker. */
@Composable
private fun KeepSpeakerAwakeCard(tick: Long) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(BellStore.keepSpeakerAwake(ctx)) }
    val btConnected = remember(tick) { isBluetoothSpeakerConnected(ctx) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep Bluetooth speaker awake", fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(
                        if (btConnected) "✓ A Bluetooth speaker is connected"
                        else "No Bluetooth speaker connected right now",
                        fontSize = 12.sp,
                        color = if (btConnected) GoodGreen else Ink.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        KeepAliveService.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = GoodGreen, checkedTrackColor = GoodGreen.copy(alpha = 0.4f)),
                )
            }
            Text(
                "Portable speakers often disconnect after a few minutes of silence, " +
                    "so the next bell comes faintly from the tablet instead. This holds " +
                    "the link open with a silent keep-alive signal between bells — the " +
                    "same trick as the “silence + bells” loops centres play. Turn it on " +
                    "only when using an external speaker, and keep the tablet charged.",
                fontSize = 12.sp, color = Ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** True if an audio output route that behaves like a wireless speaker is
 *  currently connected. Reads the audio device list, which needs no permission. */
private fun isBluetoothSpeakerConnected(ctx: Context): Boolean = bluetoothOutput(ctx) != null

@Composable
private fun NextBellLine(bells: List<BellTime>, tick: Long) {
    val ctx = LocalContext.current
    val upcoming = remember(bells, tick) { BellScheduler.nextUpcoming(ctx) }
    val text = if (upcoming == null) {
        "Nothing scheduled yet — add a bell below or schedule a talk."
    } else {
        val mins = TimeUnit.MILLISECONDS.toMinutes((upcoming.second - System.currentTimeMillis()).coerceAtLeast(0))
        val rel = if (mins >= 60) "in ${mins / 60}h ${mins % 60}min" else "in ${mins}min"
        val what = when {
            upcoming.first.isTalk -> "🎧 ${upcoming.first.talkTitle}"
            upcoming.first.singleStrike -> "🔔 one bell"
            else -> "🔔 three bells"
        }
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
            "strikes, or one if you set it to (×3 / ×1 beside its time); scheduled " +
            "talks play your chosen recording.",
        fontSize = 12.sp, color = Ink.copy(alpha = 0.55f), textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mp4")

/**
 * What the "from your phone" picker will show. The audio wildcard alone hides
 * files the provider failed to type — FLAC and Opus are routinely handed over as
 * `application/octet-stream` by file managers and cloud providers, and the
 * picker then greys out a talk that plays perfectly well. Allowing the untyped
 * catch-all makes those reachable; the cost is that other binaries show up in
 * the picker too, so a wrong pick is possible. MediaPlayer identifies audio by
 * sniffing content rather than by name, so a genuine recording plays whatever
 * type its provider claimed.
 */
private val PICKABLE_TYPES = arrayOf("audio/*", "application/octet-stream")

/**
 * Paste a kDrive public-share link, list its audio files, and download the chosen
 * ones into the app's private storage. Downloaded files become library talks whose
 * uri is a local file:// path — owned by the app, so neither instant nor scheduled
 * playback can ever lose access to them.
 */
@Composable
private fun KDriveDialog(
    existingTitles: Set<String>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(BellStore.kdriveUrl(ctx)) }
    var config by remember { mutableStateOf<KDriveConfig?>(null) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    fun connect() {
        status = null
        connecting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parsed = KDriveClient.parseShareUrl(url.trim())
                        ?: throw IllegalArgumentException("Not a kDrive share link")
                    val cfg = KDriveClient.init(parsed.first, parsed.second)
                    val list = KDriveClient.listFiles(cfg)
                        .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS }
                    cfg to list
                }
            }
            connecting = false
            result.onSuccess { (cfg, list) ->
                config = cfg
                files = list
                BellStore.setKdriveUrl(ctx, url.trim())
                status = if (list.isEmpty()) "No audio files found in that share." else "${list.size} audio file(s) found."
            }.onFailure { status = "Could not connect: ${it.message}" }
        }
    }

    fun download(file: RemoteFile) {
        val cfg = config ?: return
        val safe = file.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val filename = "${System.nanoTime()}_$safe"
        val title = file.name.substringBeforeLast('.').ifBlank { file.name }
        DownloadService.enqueue(ctx, key = file.id, url = KDriveClient.downloadUrl(cfg, file.id), filename = filename, title = title)
        status = "Downloading in the background — it lands in your Library when done. You can close this."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Accent) }
        },
        title = { Text("Download from kDrive", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Public share link") },
                    placeholder = { Text("https://kdrive.infomaniak.com/app/share/…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { connect() },
                    enabled = !connecting && url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    } else {
                        Text("Connect")
                    }
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                }
                if (files.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        files.forEach { f ->
                            val title = f.name.substringBeforeLast('.').ifBlank { f.name }
                            val isHere = title in existingTitles || title in DownloadState.justAdded
                            val isBusy = f.id in DownloadState.inFlight
                            val err = DownloadState.errors[f.id]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 14.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        err?.let { "Failed: $it — tap to retry" } ?: formatSize(f.size),
                                        fontSize = 11.sp,
                                        color = if (err != null) WarnAmber else Ink.copy(alpha = 0.5f),
                                    )
                                }
                                when {
                                    isBusy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                                    isHere -> Text("✓", color = GoodGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                                    else -> IconButton(onClick = { download(f) }) {
                                        Icon(Icons.Filled.Download, contentDescription = "Download", tint = Accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Paste a podcast RSS feed URL, list its episodes, and download the chosen ones
 * into the app's private storage. Downloaded episodes become library talks whose
 * uri is a local file:// path — owned by the app, so neither instant nor scheduled
 * playback can ever lose access to them. Enclosure URLs may redirect or carry query
 * strings; [Http] follows redirects.
 */
@Composable
private fun PodcastDialog(
    existingTitles: Set<String>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(BellStore.podcastUrl(ctx)) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }

    fun fetch() {
        status = null
        fetching = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PodcastClient.fetch(url.trim()) }
            }
            fetching = false
            result.onSuccess { list ->
                episodes = list
                BellStore.setPodcastUrl(ctx, url.trim())
                status = if (list.isEmpty()) "No episodes found in that feed." else "${list.size} episode(s) found."
            }.onFailure { status = "Could not read the feed: ${it.message}" }
        }
    }

    fun download(ep: Episode) {
        val ext = ep.url.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }?.lowercase() ?: "mp3"
        val safe = ep.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val filename = "${System.nanoTime()}_$safe.$ext"
        DownloadService.enqueue(ctx, key = ep.url, url = ep.url, filename = filename, title = ep.title)
        status = "Downloading in the background — it lands in your Library when done. You can close this."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Accent) }
        },
        title = { Text("Download from podcast feed", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://…/feeds/teacher.xml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { fetch() },
                    enabled = !fetching && url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (fetching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching…")
                    } else {
                        Text("Fetch episodes")
                    }
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                }
                if (episodes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        episodes.forEach { ep ->
                            val isHere = ep.title in existingTitles || ep.title in DownloadState.justAdded
                            val isBusy = ep.url in DownloadState.inFlight
                            val err = DownloadState.errors[ep.url]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(ep.title, fontSize = 14.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val meta = listOfNotNull(
                                        formatDuration(ep.durationMs).ifBlank { null },
                                        formatSize(ep.sizeBytes).takeIf { ep.sizeBytes > 0 },
                                    ).joinToString("  ·  ")
                                    when {
                                        err != null -> Text("Failed: $err — tap to retry", fontSize = 11.sp, color = WarnAmber)
                                        meta.isNotEmpty() -> Text(meta, fontSize = 11.sp, color = Ink.copy(alpha = 0.5f))
                                    }
                                }
                                when {
                                    isBusy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                                    isHere -> Text("✓", color = GoodGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                                    else -> IconButton(onClick = { download(ep) }) {
                                        Icon(Icons.Filled.Download, contentDescription = "Download", tint = Accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** "1h 05m", "39m 55s", "" for 0/unknown. */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

/** A pending "pick a time" interaction: what to seed the picker with, and what to
 *  do with the answer. Held in state so the Compose dialog can be shown.
 *  [single] carries the slot's current strike count, or null for a talk — which
 *  has none, and so is asked for a time only. */
private data class TimeRequest(
    val hour: Int,
    val minute: Int,
    val single: Boolean? = null,
    val onPicked: (Int, Int, Boolean) -> Unit,
)

private fun nowHourMinute(): Pair<Int, Int> {
    val c = java.util.Calendar.getInstance()
    return c.get(java.util.Calendar.HOUR_OF_DAY) to c.get(java.util.Calendar.MINUTE)
}

/**
 * Time picker that accepts any minute. It opens in keyboard-entry mode so an
 * exact time like 06:47 is simply typed — the platform dial snaps to 5-minute
 * steps when tapped, which is what made odd times awkward to set. The dial is
 * still one tap away for anyone who prefers it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initialHour: Int,
    initialMinute: Int,
    initialSingle: Boolean?,
    onDismiss: () -> Unit,
    onPicked: (Int, Int, Boolean) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        // 24h always, matching how every scheduled time is written (BellTime.time()).
        // Following the phone's 12h setting here meant picking "7:00 PM" and then
        // reading it back as "19:00" in the list — one schedule in two formats.
        is24Hour = true,
    )
    var typing by remember { mutableStateOf(true) }
    var single by remember { mutableStateOf(initialSingle ?: false) }

    // A plain Dialog rather than AlertDialog: the dial is wider than the platform
    // default dialog width and would be clipped inside one.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = CardBg, tonalElevation = 6.dp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Pick a time",
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = Ink,
                )
                Spacer(Modifier.height(16.dp))
                if (typing) TimeInput(state = state) else TimePicker(state = state)
                if (initialSingle != null) {
                    StrikeChoice(single = single, onChange = { single = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { typing = !typing }) {
                        Icon(
                            if (typing) Icons.Filled.Schedule else Icons.Filled.Keyboard,
                            contentDescription = null, tint = Accent,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (typing) "Dial" else "Type", color = Accent)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Ink.copy(alpha = 0.6f)) }
                    TextButton(onClick = { onPicked(state.hour, state.minute, single) }) {
                        Text("Set", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * How many times this bell strikes. Three is the default and the long-standing
 * behaviour — the full opening/closing signal; one is for the smaller markers in a
 * day where three would be too much. It applies whichever bowl is selected, and
 * each choice can be heard here at the current bell trim before it is set.
 */
@Composable
private fun StrikeChoice(single: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("This bell rings", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink)
        listOf(false to "Three bells", true to "One bell").forEach { (isSingle, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(
                    selected = single == isSingle,
                    onClick = { onChange(isSingle) },
                    colors = RadioButtonDefaults.colors(selectedColor = Accent),
                )
                Text(
                    label,
                    fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { BellAudio.playTest(ctx, BellSounds.rawRes(ctx, isSingle)) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Preview", tint = Accent)
                }
            }
        }
    }
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
