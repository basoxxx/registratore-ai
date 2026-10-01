package it.registratoreai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.registratoreai.app
import it.registratoreai.data.RecState
import it.registratoreai.data.Recording
import it.registratoreai.data.TxState
import it.registratoreai.text.ExportFormat
import it.registratoreai.service.CaptureService
import it.registratoreai.service.ServiceState
import it.registratoreai.service.TxProgress
import it.registratoreai.transcription.formatEta
import it.registratoreai.transcription.modelById
import it.registratoreai.text.formatDate
import it.registratoreai.text.formatDuration
import it.registratoreai.text.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val dao = app.db.recordings()
    val scope = rememberCoroutineScope()
    val rec: Recording? by remember(id) { dao.observe(id) }.collectAsState(initial = null)
    val segments by remember(id) { dao.observeSegments(id) }.collectAsState(initial = emptyList())
    val tx by ServiceState.transcription.collectAsState()
    val queue by ServiceState.queue.collectAsState()
    val settings by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    // ---- Player
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableFloatStateOf(-1f) }
    val audioPath = rec?.audioPath
    val recording = rec?.state != null && rec?.state != RecState.DONE
    DisposableEffect(audioPath, recording) {
        val mp = if (audioPath != null && !recording && File(audioPath).exists()) {
            runCatching { MediaPlayer().apply { setDataSource(audioPath); prepare() } }.getOrNull()
        } else null
        player = mp
        duration = mp?.duration ?: 0
        mp?.setOnCompletionListener { playing = false }
        onDispose {
            mp?.release()
            player = null
            playing = false
        }
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            player?.let { if (seeking < 0) position = it.currentPosition }
            delay(250)
        }
    }
    fun seekTo(ms: Long, play: Boolean = true) {
        val p = player ?: return
        p.seekTo(ms.toInt())
        position = ms.toInt()
        if (play && !p.isPlaying) { p.start(); playing = true }
    }

    // ---- Export
    var pendingFormat by remember { mutableStateOf(ExportFormat.MARKDOWN) }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/*")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.exporter.writeTo(id, uri, pendingFormat) }
                .onSuccess { Toast.makeText(ctx, "File salvato", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Errore: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val saveAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.exporter.copyAudioTo(id, uri) }
                .onSuccess { Toast.makeText(ctx, "Audio salvato", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Errore: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun share(format: ExportFormat) = scope.launch {
        app.exporter.shareIntent(id, format)?.let { ctx.startActivity(Intent.createChooser(it, "Condividi trascrizione")) }
    }

    val r = rec
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.title ?: "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Cerca") }
                    IconButton(onClick = { share(ExportFormat.MARKDOWN) }) { Icon(Icons.Default.Share, "Condividi") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Altro") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Condividi Markdown (.md)") }, onClick = { menu = false; share(ExportFormat.MARKDOWN) })
                        DropdownMenuItem(text = { Text("Condividi testo (.txt)") }, onClick = { menu = false; share(ExportFormat.TEXT) })
                        DropdownMenuItem(text = { Text("Salva Markdown in…") }, onClick = {
                            menu = false; pendingFormat = ExportFormat.MARKDOWN
                            r?.let { saveText.launch(app.exporter.fileName(it, "md")) }
                        })
                        DropdownMenuItem(text = { Text("Salva testo in…") }, onClick = {
                            menu = false; pendingFormat = ExportFormat.TEXT
                            r?.let { saveText.launch(app.exporter.fileName(it, "txt")) }
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Condividi audio") }, onClick = {
                            menu = false
                            scope.launch { app.exporter.audioShareIntent(id)?.let { ctx.startActivity(Intent.createChooser(it, "Condividi audio")) } }
                        })
                        DropdownMenuItem(text = { Text("Salva audio in…") }, onClick = {
                            menu = false
                            r?.let { saveAudio.launch(app.exporter.fileName(it, File(it.audioPath).extension)) }
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Copia tutto il testo") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                            menu = false
                            r?.let {
                                val text = app.exporter.build(it, segments, ExportFormat.TEXT, timestamps = false)
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(it.title, text))
                                Toast.makeText(ctx, "Testo copiato", Toast.LENGTH_SHORT).show()
                            }
                        })
                        DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Elimina") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
    ) { padding ->
        if (r == null) return@Scaffold
        val listState = rememberLazyListState()
        val visible = segments.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
        val currentIdx = if (playing || position > 0) visible.indexOfLast { it.startMs <= position } else -1

        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(formatDate(r.createdAt), style = MaterialTheme.typography.bodySmall)
                if (r.course.isNotBlank()) Text(r.course, color = MaterialTheme.colorScheme.primary)

                // ---- Player
                if (player != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val p = player ?: return@IconButton
                            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
                        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Riproduci") }
                        Slider(
                            value = if (seeking >= 0) seeking else position.toFloat(),
                            onValueChange = { seeking = it },
                            onValueChangeFinished = { player?.seekTo(seeking.toInt()); position = seeking.toInt(); seeking = -1f },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (recording) {
                    Text("Registrazione in corso…", color = MaterialTheme.colorScheme.error)
                }

                // ---- Stato trascrizione
                TranscriptionPanel(r, tx?.takeIf { it.recordingId == id }, id in queue, settings.modelId in installed,
                    onTranscribe = { restart ->
                        scope.launch {
                            if (restart) withContext(Dispatchers.IO) {
                                dao.deleteSegments(id); dao.setTranscribedUntil(id, 0)
                            }
                            CaptureService.send(ctx, CaptureService.ACTION_TRANSCRIBE, id)
                        }
                    },
                    onCancel = { CaptureService.send(ctx, CaptureService.ACTION_CANCEL_TX, id) },
                )

                if (searching) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Cerca nella trascrizione") },
                        supportingText = { if (query.isNotBlank()) Text("${visible.size} risultati") },
                    )
                }
            }

            LazyColumn(
                state = listState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (segments.isEmpty() && r.transcription == TxState.DONE) {
                    item { Text("Nessun parlato riconosciuto in questa registrazione.") }
                }
                items(visible.size, key = { visible[it].id }) { i ->
                    val s = visible[i]
                    val active = i == currentIdx
                    Column(
                        Modifier.fillMaxWidth()
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { seekTo(s.startMs) }
                            .padding(8.dp)
                    ) {
                        Text(formatTimestamp(s.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    if (renaming && r != null) {
        var title by remember { mutableStateOf(r.title) }
        var course by remember { mutableStateOf(r.course) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rinomina") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Titolo") }, singleLine = true)
                    OutlinedTextField(course, { course = it }, label = { Text("Corso") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    renaming = false
                    scope.launch { dao.rename(id, title.ifBlank { r.title }, course.trim()); app.exporter.autoExport(id) }
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
    }

    if (deleting && r != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Eliminare la registrazione?") },
            text = { Text("Verranno eliminati l'audio e la trascrizione. I file già esportati non vengono toccati.") },
            confirmButton = {
                Button(onClick = {
                    deleting = false
                    CaptureService.send(ctx, CaptureService.ACTION_CANCEL_TX, id)
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            File(r.audioPath).delete()
                            dao.delete(r)
                        }
                        onBack()
                    }
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun TranscriptionPanel(
    rec: Recording,
    tx: TxProgress?,
    queued: Boolean,
    modelReady: Boolean,
    onTranscribe: (restart: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val app = LocalContext.current.app
    val settings by app.settings.state.collectAsState()
    val liveSpeed by ServiceState.speed.collectAsState()
    val speed = liveSpeed ?: app.transcriber.speedFor(settings.modelId)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            when {
                rec.state != RecState.DONE -> Text("La trascrizione viene aggiornata automaticamente durante la registrazione.")
                // In coda: stima basata sulla velocità misurata in precedenza
                (queued || rec.transcription == TxState.QUEUED) && speed != null -> {
                    Text("In coda per la trascrizione · durata stimata ${formatEta(((rec.durationMs - rec.transcribedUntilMs) / speed).toLong())}")
                    TextButton(onClick = onCancel) { Text("Annulla") }
                }
                tx != null -> {
                    Text("Trascrizione in corso… ${(tx.fraction * 100).toInt()}%", fontWeight = FontWeight.Medium)
                    Text(
                        tx.etaMs?.let { "Fine stimata tra ${formatEta(it)}" } ?: "Calcolo del tempo rimanente…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { tx.fraction }, Modifier.fillMaxWidth())
                    Text(
                        "Il testo compare qui sotto man mano. Puoi uscire dall'app: continua in background.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onCancel) { Text("Interrompi") }
                }
                queued || rec.transcription == TxState.QUEUED || rec.transcription == TxState.RUNNING -> {
                    Text("In coda per la trascrizione")
                    TextButton(onClick = onCancel) { Text("Annulla") }
                }
                !modelReady -> Text("Scarica un modello di trascrizione dalle Impostazioni per trascrivere questa lezione.")
                rec.transcription == TxState.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Trascritta con Whisper ${modelById(rec.modelId).name}",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onTranscribe(true) }) { Text("Ritrascrivi") }
                }
                else -> {
                    if (rec.transcription == TxState.ERROR) {
                        Text("Errore: ${rec.errorMessage ?: "sconosciuto"}", color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rec.transcribedUntilMs > 0) {
                            Button(onClick = { onTranscribe(false) }) { Text("Riprendi da ${formatTimestamp(rec.transcribedUntilMs)}") }
                            OutlinedButton(onClick = { onTranscribe(true) }) { Text("Da capo") }
                        } else {
                            Button(onClick = { onTranscribe(false) }) { Text("Trascrivi ora") }
                        }
                    }
                }
            }
        }
    }
}
