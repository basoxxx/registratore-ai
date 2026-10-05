package it.registratoreai.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.registratoreai.text.ExportFormat
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.text.formatDate
import it.registratoreai.text.formatDuration
import it.registratoreai.text.formatShortDate
import it.registratoreai.text.formatTimestamp
import it.registratoreai.summary.SUMMARY_MODELS
import it.registratoreai.transcription.CUSTOM_MODEL_ID
import it.registratoreai.transcription.MODELS
import it.registratoreai.transcription.canonicalModelId
import it.registratoreai.transcription.formatEta
import it.registratoreai.transcription.modelById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LightColors = lightColorScheme(primary = Color(0xFF3949AB), secondary = Color(0xFFFFA000))
private val DarkColors = darkColorScheme(primary = Color(0xFF9FA8FF), secondary = Color(0xFFFFCA28))

private sealed interface Pane {
    data object Empty : Pane
    data object Settings : Pane
    data class Detail(val id: String) : Pane
}

@Composable
fun AppUi(app: DesktopApp, frame: Frame) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var pane by remember { mutableStateOf<Pane>(Pane.Empty) }
            val message by app.messages.collectAsState()
            Row(Modifier.fillMaxSize()) {
                Sidebar(app, frame, selected = (pane as? Pane.Detail)?.id, onSelect = { pane = it })
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val p = pane) {
                        Pane.Empty -> Welcome(app) { pane = Pane.Settings }
                        Pane.Settings -> SettingsPane(app, frame)
                        is Pane.Detail -> DetailPane(app, frame, p.id) { pane = Pane.Empty }
                    }
                }
            }
            message?.let {
                AlertDialog(
                    onDismissRequest = { app.messages.value = null },
                    confirmButton = { TextButton(onClick = { app.messages.value = null }) { Text("OK") } },
                    text = { Text(it) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- Barra laterale

@Composable
private fun Sidebar(app: DesktopApp, frame: Frame, selected: String?, onSelect: (Pane) -> Unit) {
    val lessons by app.lessons.collectAsState()
    val live by app.recording.collectAsState()
    val progress by app.progress.collectAsState()
    val queue by app.queue.collectAsState()
    val update by app.update.collectAsState()
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.width(320.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Registratore Lezioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        val l = live
        if (l != null) {
            Card(
                Modifier.fillMaxWidth().clickable { onSelect(Pane.Detail(l.lessonId)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (l.paused) "Registrazione in pausa" else "● Registrazione in corso", fontWeight = FontWeight.Bold)
                    Text(formatDuration(l.elapsedMs), fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
                    LinearProgressIndicator(
                        progress = { Math.sqrt(l.level.toDouble()).toFloat() },
                        Modifier.fillMaxWidth().height(6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { app.setPaused(!l.paused) }) {
                            Icon(if (l.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pausa")
                        }
                        FilledIconButton(
                            onClick = { scope.launch(Dispatchers.IO) { app.stopRecording() } },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Default.Stop, "Stop") }
                        Text("Stop salva la lezione", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Button(
                onClick = { showNew = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Registra", fontSize = 17.sp)
            }
        }

        update?.let { u ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nuova versione ${u.version}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { browse(u.url) }) { Text("Scarica") }
                }
            }
        }

        val p = progress
        if (p != null || queue.isNotEmpty()) {
            val speed by app.speed.collectAsState()
            val total = remember(p, queue, speed) { app.totalEtaMs() }
            val liveOnly = p != null && p.lessonId == live?.lessonId && queue.isEmpty()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Trascrizione", fontWeight = FontWeight.Bold)
                    if (p != null) {
                        Text("${app.lesson(p.lessonId)?.title ?: ""} · ${(p.fraction * 100).toInt()}%", maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { p.fraction }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    if (queue.isNotEmpty()) Text("In coda: ${queue.size}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            liveOnly -> "Segue la registrazione in corso"
                            total != null -> "Fine di tutto tra ${formatEta(total)}"
                            else -> "Calcolo del tempo stimato…"
                        },
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Cerca nelle lezioni") },
        )

        val filtered = lessons.filter {
            query.isBlank() || it.title.contains(query, true) || it.course.contains(query, true) ||
                it.segments.any { s -> s.text.contains(query, true) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lessons.isEmpty()) item {
                Text("Nessuna lezione. Premi “Registra” all'inizio della lezione.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(filtered, key = { it.id }) { lesson ->
                val sel = lesson.id == selected
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onSelect(Pane.Detail(lesson.id)) }
                        .padding(10.dp)
                ) {
                    Text(lesson.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (lesson.course.isNotBlank()) Text(lesson.course, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${formatShortDate(lesson.createdAt)} · ${formatDuration(lesson.durationMs)} · ${statusLabel(lesson, progress, lesson.id in queue)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                chooseFile(frame, "Importa audio (WAV/AIFF)")?.let { f ->
                    scope.launch(Dispatchers.IO) { app.importAudio(f)?.let { onSelect(Pane.Detail(it)) } }
                }
            }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(4.dp)); Text("Importa") }
            TextButton(onClick = { onSelect(Pane.Settings) }) {
                Icon(Icons.Default.Settings, null); Spacer(Modifier.width(4.dp)); Text("Impostazioni")
            }
        }
    }

    if (showNew) {
        NewRecordingDialog(
            courses = lessons.map { it.course }.filter { it.isNotBlank() }.distinct().take(6),
            onDismiss = { showNew = false },
            onStart = { title, course ->
                showNew = false
                app.startRecording(title, course)?.let { onSelect(Pane.Detail(it)) }
            },
        )
    }
}

private fun statusLabel(l: Lesson, p: TxProgress?, queued: Boolean): String = when {
    l.recording -> "● in registrazione"
    p?.lessonId == l.id -> "trascrizione ${(p.fraction * 100).toInt()}%" + (p.etaMs?.takeIf { !l.recording }?.let { ", ${formatEta(it)}" } ?: "")
    queued || l.status == TxStatus.QUEUED -> "in coda"
    l.status == TxStatus.DONE -> "trascritta"
    l.status == TxStatus.ERROR -> "errore"
    l.transcribedUntilMs > 0 -> "parziale"
    else -> "da trascrivere"
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
                OutlinedTextField(course, { course = it }, label = { Text("Corso (aiuta la trascrizione)") }, singleLine = true)
                if (courses.isNotEmpty()) {
                    Text("Corsi recenti:", style = MaterialTheme.typography.labelMedium)
                    courses.forEach { c ->
                        Text(c, Modifier.fillMaxWidth().clickable { course = c }.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.primary)
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

// ---------------------------------------------------------------------------- Benvenuto

@Composable
private fun Welcome(app: DesktopApp, openSettings: () -> Unit) {
    val installed by app.models.installed.collectAsState()
    val settings by app.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Mic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Registra la lezione, ritrova tutto scritto.", style = MaterialTheme.typography.headlineSmall)
        Text(
            "La trascrizione avviene interamente su questo computer, senza Internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (!app.nativeOk) {
            Text(Native.error ?: "", color = MaterialTheme.colorScheme.error)
        } else if (installed.isEmpty()) {
            ModelDownloadCard(app, modelById(settings.modelId).id)
            TextButton(onClick = openSettings) { Text("Scegli un altro modello") }
        } else {
            Text("Seleziona una lezione a sinistra o premi “Registra”.", style = MaterialTheme.typography.bodyLarge)
            Text("Le lezioni sono salvate in: ${settings.libraryDir}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelDownloadCard(app: DesktopApp, modelId: String) {
    val downloads by app.models.downloads.collectAsState()
    val model = modelById(modelId)
    val p = downloads[model.id]
    Card(Modifier.width(460.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp)) {
            Text("Scarica il modello di trascrizione", fontWeight = FontWeight.Bold)
            Text("Serve una sola volta: Whisper “${model.name}” (${model.sizeMb} MB).", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            if (p != null) {
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            } else {
                Button(onClick = { download(app, model.id) }) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Scarica")
                }
            }
        }
    }
}

private fun download(app: DesktopApp, id: String) {
    app.scope.launch(Dispatchers.IO) {
        runCatching { app.models.download(modelById(id)) }
            .onFailure { app.messages.value = "Download non riuscito: ${it.message}" }
    }
}

// ---------------------------------------------------------------------------- Dettaglio lezione

@Composable
private fun DetailPane(app: DesktopApp, frame: Frame, id: String, onDeleted: () -> Unit) {
    val lessons by app.lessons.collectAsState()
    val lesson = lessons.firstOrNull { it.id == id } ?: return
    val progress by app.progress.collectAsState()
    val queue by app.queue.collectAsState()
    val settings by app.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    var query by remember(id) { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(lesson.segments.size, lesson.recording) {
        if (lesson.recording && lesson.segments.isNotEmpty()) listState.animateScrollToItem(lesson.segments.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(formatDate(lesson.createdAt), lesson.course, formatDuration(lesson.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(Icons.Default.Description, "Esporta .md") {
                saveFile(frame, TranscriptFormatter.fileName(lesson.title, lesson.createdAt, "md"))?.writeText(lesson.export(ExportFormat.MARKDOWN, settings.timestamps))
            }
            Action(Icons.Default.Description, "Esporta .txt") {
                saveFile(frame, TranscriptFormatter.fileName(lesson.title, lesson.createdAt, "txt"))?.writeText(lesson.export(ExportFormat.TEXT, settings.timestamps))
            }
            Action(Icons.Default.ContentCopy, "Copia testo") {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(lesson.export(ExportFormat.TEXT, false)), null)
            }
            Action(Icons.Default.PlayArrow, "Ascolta") { open(lesson.audio) }
            Action(Icons.Default.Folder, "Cartella") { open(lesson.dir) }
            Action(Icons.Default.Edit, "Rinomina") { renaming = true }
            Action(Icons.Default.Delete, "Elimina", enabled = !lesson.recording) { deleting = true }
        }

        // Stato della trascrizione
        Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Column(Modifier.padding(14.dp)) {
                val p = progress?.takeIf { it.lessonId == id }
                val queued = id in queue || lesson.status == TxStatus.QUEUED
                when {
                    lesson.recording && p != null -> Text("Trascrizione in tempo reale · aggiornata a ${formatTimestamp(p.processedMs)}" +
                        (p.etaMs?.takeIf { p.totalMs - p.processedMs > 60_000 }?.let { " · in ritardo, recupero in ${formatEta(it)}" } ?: ""))
                    lesson.recording -> Text(
                        if (settings.liveTranscription && settings.modelId in installed) "La trascrizione comparirà qui ogni ~30 secondi."
                        else "La trascrizione partirà al termine della registrazione."
                    )
                    p != null -> {
                        Text(
                            if (lesson.pass == 2) "Trascrizione finale con ${modelById(lesson.modelId).name}… ${(p.fraction * 100).toInt()}% · il testo migliora man mano"
                            else "Trascrizione in corso… ${(p.fraction * 100).toInt()}%",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(p.etaMs?.let { "Fine stimata tra ${formatEta(it)}" } ?: "Calcolo del tempo rimanente…",
                            color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(progress = { p.fraction }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        TextButton(onClick = { app.cancel(id) }) { Text("Interrompi") }
                    }
                    queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val sp = app.speedFor(settings.modelId)
                        Text("In coda per la trascrizione" +
                            (sp?.let { " · durata stimata ${formatEta(((lesson.durationMs - lesson.transcribedUntilMs).coerceAtLeast(0) / it).toLong())}" } ?: ""),
                            Modifier.weight(1f))
                        TextButton(onClick = { app.cancel(id) }) { Text("Annulla") }
                    }
                    settings.modelId !in installed -> ModelDownloadCard(app, settings.modelId)
                    lesson.status == TxStatus.DONE && lesson.pass != 2 && settings.finalModelId in installed &&
                        canonicalModelId(lesson.modelId) != canonicalModelId(settings.finalModelId) -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Anteprima con Whisper ${modelById(lesson.modelId).name}", Modifier.weight(1f))
                        Button(onClick = { app.transcribe(id, restart = true) }) { Text("Migliora con ${modelById(settings.finalModelId).name}") }
                    }
                    lesson.status == TxStatus.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trascritta con Whisper ${modelById(lesson.modelId).name} · il file .md nella cartella è sempre aggiornato",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { app.transcribe(id, restart = true) }) { Text("Ritrascrivi") }
                    }
                    else -> Column {
                        if (lesson.status == TxStatus.ERROR) Text("Errore: ${lesson.error}", color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (lesson.transcribedUntilMs > 0) {
                                Button(onClick = { app.transcribe(id, restart = false) }) { Text("Riprendi da ${formatTimestamp(lesson.transcribedUntilMs)}") }
                                OutlinedButton(onClick = { app.transcribe(id, restart = true) }) { Text("Da capo") }
                            } else {
                                Button(onClick = { app.transcribe(id, restart = false) }) { Text("Trascrivi ora") }
                            }
                        }
                    }
                }
            }
        }

        SummaryCard(app, lesson)

        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Cerca nella trascrizione") },
        )
        Spacer(Modifier.height(8.dp))
        val visible = lesson.segments.filter { query.isBlank() || it.text.contains(query, true) }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lesson.segments.isEmpty() && lesson.status == TxStatus.DONE) item { Text("Nessun parlato riconosciuto.") }
                items(visible) { s ->
                    Row {
                        Text(formatTimestamp(s.startMs), Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(lesson.title) }
        var course by remember { mutableStateOf(lesson.course) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rinomina") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Titolo") }, singleLine = true)
                    OutlinedTextField(course, { course = it }, label = { Text("Corso") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = { renaming = false; app.rename(id, title.ifBlank { lesson.title }, course.trim()) }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Eliminare la lezione?") },
            text = { Text("Verranno eliminati audio e trascrizione (l'intera cartella della lezione).") },
            confirmButton = {
                Button(
                    onClick = { deleting = false; app.delete(id); onDeleted() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun SummaryCard(app: DesktopApp, lesson: Lesson) {
    val settings by app.settings.collectAsState()
    val installed by app.summaryModels.installed.collectAsState()
    val running by app.summaryProgress.collectAsState()
    var expanded by remember(lesson.id) { mutableStateOf(true) }
    val progress = running?.takeIf { it.first == lesson.id }?.second
    val ready = settings.summaryModelId in installed
    val canRun = lesson.status == TxStatus.DONE && !lesson.recording
    if (lesson.summary == null && !ready && progress == null) return
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Riassunto", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    progress != null -> {}
                    lesson.summary != null -> {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Comprimi" else "Mostra") }
                        if (canRun && ready) TextButton(onClick = { app.summarize(lesson.id) }) { Text("Rigenera") }
                    }
                    lesson.summaryStatus == TxStatus.QUEUED -> Text("In coda", style = MaterialTheme.typography.labelMedium)
                    canRun && ready -> Button(onClick = { app.summarize(lesson.id) }) { Text("Genera riassunto") }
                }
            }
            when {
                progress != null -> {
                    Text("L'IA locale sta leggendo la lezione… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                lesson.summaryStatus == TxStatus.ERROR -> Text("Riassunto non riuscito: ${lesson.error ?: ""}", color = MaterialTheme.colorScheme.error)
                lesson.summary != null && expanded -> SelectionContainer {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { MarkdownText(lesson.summary) }
                }
                !canRun -> Text("Verrà creato al termine della trascrizione.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> Text(inlineMd(line.trimStart('#').trim()), fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Row {
                    Text("•", Modifier.width(16.dp))
                    Text(inlineMd(line.replaceFirst(Regex("^\\s*[-*•] "), "")))
                }
                else -> Text(inlineMd(line))
            }
        }
    }
}

private fun inlineMd(s: String) = buildAnnotatedString {
    s.replace("$", "").split("**").forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------------------- Impostazioni

private val LANGUAGES = listOf("it" to "Italiano", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "auto" to "Automatica")

@Composable
private fun SettingsPane(app: DesktopApp, frame: Frame) {
    val s by app.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    val upgradable by app.models.upgradable.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val summaryInstalled by app.summaryModels.installed.collectAsState()
    val summaryDownloads by app.summaryModels.downloads.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    var updateMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)

        Section("Modelli di trascrizione (offline)")
        Text("Durante la lezione un modello leggero mostra l'anteprima; al termine il modello grande ritrascrive tutto.",
            style = MaterialTheme.typography.bodySmall)
        MODELS.forEach { m ->
            val p = downloads[m.id]
            val needsUpgrade = m.id in upgradable
            if (m.id == CUSTOM_MODEL_ID) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Medium)
                            Text(m.description, style = MaterialTheme.typography.bodySmall)
                            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                            else if (m.id in installed) Text("Caricato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (m.id in installed) TextButton(onClick = { app.models.delete(m) }) { Text("Elimina") }
                        else if (!importing) TextButton(onClick = {
                            chooseFile(frame, "Scegli un modello Whisper (.bin)")?.let { f ->
                                importing = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { runCatching { app.importModel(f) }.getOrDefault(false) }
                                    importing = false
                                    app.messages.value = if (ok) "Modello caricato: verrà usato per la trascrizione finale."
                                    else "Il file non è un modello Whisper in formato ggml (.bin)."
                                }
                            }
                        }) { Text("Importa…") }
                    }
                }
                return@forEach
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${m.name} · ${m.sizeMb} MB", fontWeight = FontWeight.Medium)
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                        when {
                            p != null -> LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            needsUpgrade -> Text("Versione ottimizzata disponibile: più veloce e più precisa",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            m.id in installed -> Text("Scaricato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    when {
                        p != null -> {}
                        needsUpgrade -> TextButton(onClick = { download(app, m.id) }) { Text("Aggiorna") }
                        m.id in installed -> TextButton(onClick = { app.models.delete(m) }) { Text("Elimina") }
                        else -> TextButton(onClick = { download(app, m.id) }) { Text("Scarica") }
                    }
                }
            }
        }
        Text("Tempo reale (anteprima durante la lezione)", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODELS.take(4).forEach { m ->
                FilterChip(s.modelId == m.id, onClick = { app.updateSettings { it.copy(modelId = m.id) } }, label = { Text(m.name) })
            }
        }
        Toggle("Trascrizione finale al termine", "Appena finisce la lezione ritrascrive tutto con il modello scelto qui sotto.", s.refineAfter) { v ->
            app.updateSettings { it.copy(refineAfter = v) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODELS.drop(2).filter { it.id != CUSTOM_MODEL_ID || it.id in installed }.forEach { m ->
                FilterChip(s.finalModelId == m.id, onClick = { app.updateSettings { it.copy(finalModelId = m.id) } }, label = { Text(m.name) })
            }
        }
        if (s.finalModelId !in installed) Text("Scarica ${modelById(s.finalModelId).name} per attivare la trascrizione finale.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        Section("Riassunto con IA locale")
        Text("Un modello linguistico gira sul computer, offline, e scrive il riassunto: punti chiave, definizioni e scaletta con i minutaggi.",
            style = MaterialTheme.typography.bodySmall)
        Toggle("Riassunto automatico", "Al termine della trascrizione finale.", s.autoSummary) { v -> app.updateSettings { it.copy(autoSummary = v) } }
        SUMMARY_MODELS.forEach { m ->
            val p = summaryDownloads[m.id]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.summaryModelId == m.id, onClick = { app.updateSettings { it.copy(summaryModelId = m.id) } })
                    Column(Modifier.weight(1f)) {
                        Text("${m.name} · ${m.sizeMb} MB", fontWeight = FontWeight.Medium)
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                        if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 4.dp))
                        else if (m.id in summaryInstalled) Text("Scaricato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    when {
                        p != null -> {}
                        m.id in summaryInstalled -> TextButton(onClick = { app.summaryModels.delete(m) }) { Text("Elimina") }
                        else -> TextButton(onClick = {
                            app.updateSettings { it.copy(summaryModelId = m.id) }
                            app.scope.launch(Dispatchers.IO) {
                                runCatching { app.summaryModels.download(m) }.onFailure { app.messages.value = "Download non riuscito: ${it.message}" }
                            }
                        }) { Text("Scarica") }
                    }
                }
            }
        }

        Section("Lavoro in background")
        Toggle(
            "Impedisci lo standby mentre lavora",
            "Il computer non va in standby finché trascrizione e riassunto non sono finiti (lo schermo può spegnersi). " +
                "Durante la registrazione lo standby è sempre impedito. Chiudere il coperchio del portatile lo sospende comunque.",
            s.keepAwake,
        ) { v -> app.updateSettings { it.copy(keepAwake = v) } }

        Section("Lingua delle lezioni")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LANGUAGES.forEach { (code, name) ->
                FilterChip(s.language == code, onClick = { app.updateSettings { it.copy(language = code) } }, label = { Text(name) })
            }
        }

        Section("Trascrizione")
        Toggle("Trascrizione in tempo reale", "Il testo si aggiorna durante la lezione.", s.liveTranscription) { v -> app.updateSettings { it.copy(liveTranscription = v) } }
        Toggle("Trascrivi al termine", "Avvia la trascrizione appena fermi la registrazione.", s.autoTranscribe) { v -> app.updateSettings { it.copy(autoTranscribe = v) } }
        Toggle("Timestamp nei file Markdown", "Aggiunge [hh:mm:ss] all'inizio di ogni paragrafo.", s.timestamps) { v -> app.updateSettings { it.copy(timestamps = v) } }
        Text("Thread di calcolo: ${s.threads}")
        Slider(s.threads.toFloat(), onValueChange = { v -> app.updateSettings { it.copy(threads = v.toInt()) } }, valueRange = 1f..16f, steps = 14)

        Section("Archivio")
        Text("Ogni lezione è una cartella con l'audio (audio.wav) e la trascrizione in Markdown sempre aggiornata. " +
            "Puoi scegliere una cartella sincronizzata (iCloud, OneDrive, Drive) o il vault di Obsidian.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open(File(s.libraryDir).apply { mkdirs() }) }) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text(s.libraryDir) }
            TextButton(onClick = { chooseFolder(frame)?.let { f -> app.updateSettings { it.copy(libraryDir = f.path) } } }) { Text("Cambia cartella") }
        }

        Section("Aggiornamenti")
        Toggle("Controlla aggiornamenti all'avvio", null, s.checkUpdates) { v -> app.updateSettings { it.copy(checkUpdates = v) } }
        Text("Versione installata: ${app.version}", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                scope.launch {
                    updateMsg = "Controllo…"
                    val r = withContext(Dispatchers.IO) { runCatching { app.checkUpdate() } }
                    updateMsg = when {
                        r.isFailure -> "Impossibile contattare GitHub"
                        r.getOrNull() == null -> "Hai già l'ultima versione"
                        else -> "Disponibile la versione ${r.getOrNull()!!.version}"
                    }
                }
            }) { Text("Controlla ora") }
            app.update.collectAsState().value?.let { u -> TextButton(onClick = { browse(u.url) }) { Text("Scarica ${u.version}") } }
        }
        updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

// ---------------------------------------------------------------------------- Integrazione col sistema

private fun open(file: File) = runCatching { Desktop.getDesktop().open(file) }
private fun browse(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }

private fun chooseFile(frame: Frame, title: String): File? {
    val d = FileDialog(frame, title, FileDialog.LOAD)
    d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

private fun saveFile(frame: Frame, name: String): File? {
    val d = FileDialog(frame, "Salva", FileDialog.SAVE)
    d.file = name
    d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

private fun chooseFolder(frame: Frame): File? {
    if (Paths.isMac) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val d = FileDialog(frame, "Scegli la cartella delle lezioni", FileDialog.LOAD)
            d.isVisible = true
            return d.file?.let { File(d.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Scegli la cartella delle lezioni"
    }
    return if (chooser.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

