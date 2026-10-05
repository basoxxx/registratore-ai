package it.registratoreai.ui.screens

import it.registratoreai.ui.theme.courseColor
import it.registratoreai.ui.theme.SoftCard
import it.registratoreai.ui.theme.SectionTitle
import it.registratoreai.ui.theme.RoundProgress
import it.registratoreai.ui.theme.RecordGradient
import it.registratoreai.ui.theme.PulsingDot
import it.registratoreai.ui.theme.IconBadge
import it.registratoreai.ui.theme.GradientBox
import it.registratoreai.ui.theme.BrandGradient
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.registratoreai.MainActivity
import it.registratoreai.app
import it.registratoreai.audio.AudioCodec
import it.registratoreai.data.RecState
import it.registratoreai.data.Recording
import it.registratoreai.service.CaptureService
import it.registratoreai.service.ServiceState
import it.registratoreai.transcription.formatEta
import it.registratoreai.transcription.modelById
import it.registratoreai.text.formatDuration
import it.registratoreai.text.formatShortDate
import it.registratoreai.update.UpdateChecker
import it.registratoreai.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activity: MainActivity,
    onOpen: (Long) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val recordings by remember { app.db.recordings().observeAll() }.collectAsState(initial = emptyList())
    val live by ServiceState.recording.collectAsState()
    val tx by ServiceState.transcription.collectAsState()
    val queue by ServiceState.queue.collectAsState()
    val liveSpeed by ServiceState.speed.collectAsState()
    val settings by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState()

    var showNew by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf<Float?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    // Controllo aggiornamenti (al massimo una volta ogni 6 ore)
    LaunchedEffect(Unit) {
        if (settings.checkUpdates && System.currentTimeMillis() - app.settings.lastUpdateCheck > 6 * 3600_000L) {
            update = runCatching { UpdateChecker.check() }.getOrNull()
            app.settings.lastUpdateCheck = System.currentTimeMillis()
        }
    }

    fun importAudio(uri: Uri) {
        scope.launch {
            importing = 0f
            try {
                val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Audio importato"
                val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
                val out = File(dir, "imp_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav")
                val duration = withContext(Dispatchers.IO) {
                    AudioCodec.decodeToWav(ctx, uri, out) { importing = it }
                }
                val id = app.db.recordings().insert(
                    Recording(
                        title = name.substringBeforeLast('.'), audioPath = out.absolutePath,
                        durationMs = duration, state = RecState.DONE,
                    )
                )
                if (app.models.isInstalled(settings.modelId)) {
                    CaptureService.send(ctx, CaptureService.ACTION_TRANSCRIBE, id)
                }
                onOpen(id)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Importazione non riuscita: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                importing = null
            }
        }
    }

    var pendingStart by remember { mutableStateOf<Pair<String, String>?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingStart
        pendingStart = null
        if (granted && p != null) {
            CaptureService.send(ctx, CaptureService.ACTION_START, title = p.first, course = p.second)
            onRecord()
        } else if (!granted) {
            Toast.makeText(ctx, "Serve il permesso del microfono per registrare", Toast.LENGTH_LONG).show()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }

    // Audio condiviso da un'altra app
    val shared by activity.sharedAudio
    LaunchedEffect(shared) {
        shared?.let { activity.sharedAudio.value = null; importAudio(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GradientBox(BrandGradient, Modifier.size(40.dp), RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.align(Alignment.Center), tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Le tue lezioni", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (recordings.isEmpty()) "Trascrizione offline" else "${recordings.size} ${if (recordings.size == 1) "lezione" else "lezioni"} · offline",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                    Icon(Icons.Default.FileOpen, "Importa audio o video", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Impostazioni", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        floatingActionButton = {
            val isLive = live != null
            GradientBox(
                if (isLive) RecordGradient else BrandGradient,
                Modifier.height(60.dp).clickable { if (isLive) onRecord() else showNew = true },
                RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isLive) PulsingDot(Color.White) else Icon(Icons.Default.Mic, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(if (isLive) "Torna alla registrazione" else "Registra", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            update?.let { u ->
                item { UpdateCard(u) { update = null } }
            }
            live?.let { l ->
                item {
                    GradientBox(RecordGradient, Modifier.fillMaxWidth().clickable { onRecord() }, RoundedCornerShape(22.dp)) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (l.paused) Icon(Icons.Default.Pause, null, Modifier.size(14.dp), tint = Color.White) else PulsingDot(Color.White)
                                    Text(if (l.paused) "In pausa" else "Registrazione in corso", Modifier.padding(start = 8.dp),
                                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                                }
                                Text(formatDuration(l.elapsedMs), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light,
                                    fontFamily = FontFamily.Monospace)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White)
                        }
                    }
                }
            }
            // Riepilogo della coda di trascrizione con il tempo stimato complessivo
            val current = tx
            if (current != null || queue.isNotEmpty()) {
                item {
                    val speed = liveSpeed ?: app.transcriber.speedFor(settings.modelId)
                    val currentRec = recordings.firstOrNull { it.id == current?.recordingId }
                    val queued = recordings.filter { it.id in queue }
                    val queueMs = speed?.let { sp -> queued.sumOf { ((it.durationMs - it.transcribedUntilMs).coerceAtLeast(0) / sp).toLong() } }
                    val currentMs = current?.etaMs ?: speed?.let { sp -> current?.let { ((it.totalMs - it.processedMs) / sp).toLong() } } ?: 0L
                    SoftCard(Modifier.fillMaxWidth().clickable(enabled = currentRec != null) { currentRec?.let { onOpen(it.id) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(MaterialTheme.colorScheme.primary, size = 36) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(if (current?.live == true) "Trascrizione in tempo reale" else "Trascrizione in corso",
                                    style = MaterialTheme.typography.titleSmall)
                                Text(currentRec?.title ?: "In coda: ${queued.size}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (current != null) Text("${(current.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (current != null) {
                            Spacer(Modifier.height(12.dp))
                            RoundProgress(current.fraction)
                        }
                        val total = if (queueMs != null) currentMs + queueMs else null
                        Text(
                            listOfNotNull(
                                when {
                                    current?.live == true && queued.isEmpty() -> "Segue la registrazione in corso"
                                    total != null -> "Fine di tutto tra ${formatEta(total)}"
                                    else -> "Calcolo del tempo stimato…"
                                },
                                queued.size.takeIf { it > 0 }?.let { "$it in coda" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (installed.isEmpty()) {
                item {
                    val model = modelById(settings.modelId)
                    val progress = downloads[model.id]
                    SoftCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Scarica il modello di trascrizione", style = MaterialTheme.typography.titleSmall)
                                Text("Una volta sola: Whisper “${model.name}” (${model.sizeMb} MB). Poi tutto funziona senza Internet.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (progress != null) {
                            RoundProgress(progress)
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = {
                                    app.appScope.launch {
                                        runCatching { app.models.download(model) }.onFailure {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "Download non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scarica")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = onSettings) { Text("Scegli modello") }
                            }
                        }
                    }
                }
            }
            if (recordings.size > 3) {
                item { SearchField(query, "Cerca per titolo o corso") { query = it } }
            }
            val filtered = recordings.filter {
                query.isBlank() || it.title.contains(query, true) || it.course.contains(query, true)
            }
            if (recordings.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GradientBox(BrandGradient, Modifier.size(84.dp), RoundedCornerShape(26.dp)) {
                            Icon(Icons.Default.GraphicEq, null, Modifier.size(44.dp).align(Alignment.Center), tint = Color.White)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Registra la lezione,\nritrova tutto scritto.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("Premi “Registra” all'inizio della lezione: trascrizione e riassunto avvengono sul telefono, senza Internet.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            var lastGroup = ""
            filtered.forEach { rec ->
                val g = dayGroup(rec.createdAt)
                if (g != lastGroup) {
                    lastGroup = g
                    item(key = "g-$g") { SectionTitle(g, Modifier.padding(top = 8.dp)) }
                }
                item(key = rec.id) { LessonCard(rec, tx, rec.id in queue) { onOpen(rec.id) } }
            }
        }
    }

    if (showNew) {
        NewRecordingDialog(
            courses = recordings.map { it.course }.filter { it.isNotBlank() }.distinct().take(6),
            onDismiss = { showNew = false },
            onStart = { title, course ->
                showNew = false
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingStart = title to course
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    CaptureService.send(ctx, CaptureService.ACTION_START, title = title, course = course)
                    onRecord()
                }
            },
        )
    }

    importing?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importazione audio…") },
            text = { LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) },
        )
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    SoftCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, border = Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(MaterialTheme.colorScheme.tertiary, size = 36) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Nuova versione ${info.versionName}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("Si installa in un attimo, le lezioni restano", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
            }
        }
        if (progress >= 0) {
            Spacer(Modifier.height(12.dp))
            RoundProgress(progress, color = MaterialTheme.colorScheme.tertiary)
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Più tardi") }
                Button(onClick = {
                    if (!UpdateChecker.canInstall(ctx)) {
                        Toast.makeText(ctx, "Consenti l'installazione di app da questa sorgente, poi riprova", Toast.LENGTH_LONG).show()
                        UpdateChecker.openInstallPermissionSettings(ctx)
                        return@Button
                    }
                    scope.launch {
                        progress = 0f
                        try {
                            val apk = UpdateChecker.download(ctx, info) { progress = it }
                            UpdateChecker.install(ctx, apk)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Download non riuscito: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        progress = -1f
                    }
                }) { Text("Aggiorna ora") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewRecordingDialog(courses: List<String>, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    val defaultTitle = remember { "Lezione del " + SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(Date()) }
    var title by remember { mutableStateOf(defaultTitle) }
    var course by remember { mutableStateOf(courses.firstOrNull() ?: "") }
    val settings = LocalContext.current.app.settings
    var glossary by remember(course) { mutableStateOf(settings.glossary(course)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            GradientBox(BrandGradient, Modifier.size(48.dp), RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Mic, null, Modifier.align(Alignment.Center), tint = Color.White)
            }
        },
        title = { Text("Nuova registrazione") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Titolo") }, singleLine = true)
                OutlinedTextField(
                    course, { course = it }, Modifier.fillMaxWidth(), label = { Text("Corso") }, singleLine = true,
                    placeholder = { Text("es. Analisi Matematica 1") },
                    supportingText = { Text("Aiuta la trascrizione e colora la lezione nell'elenco") },
                )
                if (courses.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        courses.forEach { c ->
                            FilterChip(course == c, onClick = { course = c }, label = { Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(courseColor(c))) })
                        }
                    }
                }
                if (course.isNotBlank()) GlossaryField(glossary) { glossary = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                settings.setGlossary(course, glossary)
                onStart(title.ifBlank { defaultTitle }, course.trim())
            }) {
                Icon(Icons.Default.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Inizia")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
