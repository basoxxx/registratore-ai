package it.registratoreai.ui.screens

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
        topBar = {
            TopAppBar(
                title = { Text("Registratore Lezioni") },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.FileOpen, "Importa audio")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Impostazioni") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (live != null) onRecord() else showNew = true },
                icon = { Icon(Icons.Default.Mic, null) },
                text = { Text(if (live != null) "Torna alla registrazione" else "Registra") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            update?.let { u ->
                item {
                    UpdateCard(u) { update = null }
                }
            }
            live?.let { l ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { onRecord() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (l.paused) "Registrazione in pausa" else "● Registrazione in corso", fontWeight = FontWeight.Bold)
                            Text(formatDuration(l.elapsedMs), style = MaterialTheme.typography.headlineSmall)
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
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Trascrizione", fontWeight = FontWeight.Bold)
                            if (current != null && currentRec != null) {
                                Text(
                                    "${currentRec.title} · ${(current.fraction * 100).toInt()}%" +
                                        if (current.live) " · in tempo reale" else "",
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                LinearProgressIndicator(progress = { current.fraction }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            }
                            if (queued.isNotEmpty()) {
                                Text("In coda: ${queued.size} ${if (queued.size == 1) "lezione" else "lezioni"}", style = MaterialTheme.typography.bodyMedium)
                            }
                            val total = if (queueMs != null) currentMs + queueMs else null
                            Text(
                                when {
                                    current?.live == true && queued.isEmpty() -> "La trascrizione segue la registrazione in corso"
                                    total != null -> "Fine stimata di tutte le trascrizioni: tra ${formatEta(total)}"
                                    else -> "Calcolo del tempo stimato…"
                                },
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            if (installed.isEmpty()) {
                item {
                    val model = modelById(settings.modelId)
                    val progress = downloads[model.id]
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Scarica il modello di trascrizione", fontWeight = FontWeight.Bold)
                            Text(
                                "La trascrizione avviene interamente sul telefono, senza Internet. " +
                                    "Serve scaricare una volta il modello Whisper “${model.name}” (${model.sizeMb} MB).",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (progress != null) {
                                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Row {
                                    Button(onClick = {
                                        app.appScope.launch {
                                            runCatching { app.models.download(model) }.onFailure {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(ctx, "Download non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Scarica")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = onSettings) { Text("Scegli modello") }
                                }
                            }
                        }
                    }
                }
            }
            if (recordings.size > 3) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text("Cerca per titolo o corso") },
                    )
                }
            }
            val filtered = recordings.filter {
                query.isBlank() || it.title.contains(query, true) || it.course.contains(query, true)
            }
            if (recordings.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Mic, null, Modifier.padding(8.dp))
                        Text("Nessuna lezione registrata", style = MaterialTheme.typography.titleMedium)
                        Text("Premi “Registra” all'inizio della lezione.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(filtered, key = { it.id }) { rec ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(rec.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (rec.course.isNotBlank()) Text(rec.course, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatShortDate(rec.createdAt)} · ${formatDuration(rec.durationMs)}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            )
                            StatusChip(rec, tx, rec.id in queue)
                        }
                    }
                }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.width(8.dp))
                Text("Aggiornamento disponibile: ${info.versionName}", fontWeight = FontWeight.Bold)
            }
            if (progress >= 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            } else {
                Row {
                    TextButton(onClick = {
                        if (!UpdateChecker.canInstall(ctx)) {
                            Toast.makeText(ctx, "Consenti l'installazione di app da questa sorgente, poi riprova", Toast.LENGTH_LONG).show()
                            UpdateChecker.openInstallPermissionSettings(ctx)
                            return@TextButton
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
                    TextButton(onClick = onDismiss) { Text("Più tardi") }
                }
            }
        }
    }
}

@Composable
private fun NewRecordingDialog(courses: List<String>, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    val defaultTitle = remember { "Lezione del " + SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(Date()) }
    var title by remember { mutableStateOf(defaultTitle) }
    var course by remember { mutableStateOf(courses.firstOrNull() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuova registrazione") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Titolo") }, singleLine = true)
                OutlinedTextField(
                    course, { course = it }, label = { Text("Corso (aiuta la trascrizione)") }, singleLine = true,
                    placeholder = { Text("es. Analisi Matematica 1") },
                )
                if (courses.isNotEmpty()) {
                    Text("Corsi recenti:", style = MaterialTheme.typography.labelMedium)
                    courses.forEach { c ->
                        Text(
                            c, Modifier.fillMaxWidth().clickable { course = c }.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(title.ifBlank { defaultTitle }, course.trim()) }) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Inizia")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
