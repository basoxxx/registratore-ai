package it.registratoreai.ui.screens

import it.registratoreai.ui.theme.SoftCard
import it.registratoreai.ui.theme.RoundProgress
import it.registratoreai.ui.theme.Pill
import it.registratoreai.ui.theme.MonoLabel
import it.registratoreai.ui.theme.IndeterminateProgress
import it.registratoreai.ui.theme.IconBadge
import it.registratoreai.ui.theme.GradientBox
import it.registratoreai.ui.theme.CourseTag
import it.registratoreai.ui.theme.BrandGradient
import it.registratoreai.text.TextSegment
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ExperimentalFoundationApi
import it.registratoreai.text.Bookmarks
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
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
import it.registratoreai.data.Pass
import it.registratoreai.data.RecState
import it.registratoreai.data.SummaryState
import it.registratoreai.data.Recording
import it.registratoreai.data.TxState
import it.registratoreai.text.ExportFormat
import it.registratoreai.service.CaptureService
import it.registratoreai.service.ServiceState
import it.registratoreai.service.TxProgress
import it.registratoreai.transcription.canonicalModelId
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var tab by remember(id) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = ""; tab = 0 }) { Icon(Icons.Default.Search, "Cerca") }
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
                        DropdownMenuItem(text = { Text("Modifica") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Elimina") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        bottomBar = {
            // ---- Player sempre a portata di mano
            if (player != null) {
                Surface(color = cs.surface, shadowElevation = 8.dp, tonalElevation = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GradientBox(BrandGradient, Modifier.size(48.dp).clickable {
                            val p = player ?: return@clickable
                            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
                        }, CircleShape) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Riproduci",
                                Modifier.align(Alignment.Center), tint = Color.White)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Slider(
                                value = if (seeking >= 0) seeking else position.toFloat(),
                                onValueChange = { seeking = it },
                                onValueChangeFinished = { player?.seekTo(seeking.toInt()); position = seeking.toInt(); seeking = -1f },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                modifier = Modifier.height(28.dp),
                            )
                            Row {
                                Text(formatDuration(position.toLong()), style = MonoLabel, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(formatDuration(duration.toLong()), style = MonoLabel, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (r == null) return@Scaffold
        val visible = segments.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
        val currentIdx = if (playing || position > 0) visible.indexOfLast { it.startMs <= position } else -1
        val marks = Bookmarks.parse(r.bookmarks)
        val starred = marks.mapNotNull { ms -> segments.lastOrNull { it.startMs <= ms } ?: segments.firstOrNull() }.map { it.id }.toSet()
        val summaryBusy = ServiceState.summary.collectAsState().value?.first == id
        // indici fissi della lista: intestazione, stato, schede
        val headerItems = 3

        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    CourseTag(r.course)
                    Text(r.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                    FlowRowMeta(r, marks.size)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TranscriptionPanel(r, tx?.takeIf { it.recordingId == id }, id in queue, settings.modelId in installed,
                        onTranscribe = { restart ->
                            // "Ritrascrivi" usa il modello finale e sostituisce il testo man mano, senza cancellarlo
                            CaptureService.send(ctx, if (restart) CaptureService.ACTION_REFINE else CaptureService.ACTION_TRANSCRIBE, id)
                        },
                        onCancel = { CaptureService.send(ctx, CaptureService.ACTION_CANCEL_TX, id) },
                    )
                }
            }
            stickyHeader {
                Column(Modifier.background(cs.background)) {
                    TabRow(
                        tab, containerColor = cs.background,
                        divider = { HorizontalDivider(color = cs.outlineVariant) },
                        indicator = { pos ->
                            TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), width = 40.dp,
                                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        },
                    ) {
                        listOf("Testo", "Riassunto", "Momenti").forEachIndexed { i, label ->
                            val badge = when (i) {
                                1 -> if (summaryBusy) "…" else if (r.summary != null) "✓" else null
                                2 -> marks.size.takeIf { it > 0 }?.toString()
                                else -> null
                            }
                            Tab(tab == i, onClick = { tab = i }, text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, style = MaterialTheme.typography.labelLarge)
                                    badge?.let { Pill(it, cs.primary, Modifier.padding(start = 4.dp)) }
                                }
                            })
                        }
                    }
                    if (tab == 0 && searching) {
                        SearchField(query, "Cerca nella trascrizione", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { query = it }
                        if (query.isNotBlank()) Text("${visible.size} risultati", Modifier.padding(start = 32.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
            when (tab) {
                0 -> {
                    if (segments.isEmpty()) item {
                        Text(
                            if (r.transcription == TxState.DONE) "Nessun parlato riconosciuto in questa registrazione."
                            else "La trascrizione comparirà qui.",
                            Modifier.padding(20.dp), color = cs.onSurfaceVariant,
                        )
                    }
                    items(visible.size, key = { visible[it].id }) { i ->
                        val s = visible[i]
                        val active = i == currentIdx
                        val star = s.id in starred
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    when {
                                        active -> cs.primaryContainer
                                        star -> cs.tertiaryContainer.copy(alpha = 0.6f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { seekTo(s.startMs) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.width(64.dp).padding(top = 3.dp)) {
                                Text(formatTimestamp(s.startMs).removePrefix("00:"), style = MonoLabel, color = cs.primary)
                                if (star) Text("⭐", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                highlight(s.text, query, cs.tertiaryContainer),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (s.pass >= Pass.FINAL) cs.onSurface else cs.onSurface.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
                1 -> item { SummaryTab(r, onSummarize = { CaptureService.send(ctx, CaptureService.ACTION_SUMMARIZE, id) }) }
                else -> {
                    if (marks.isEmpty()) item {
                        EmptyCard(Icons.Default.Star, cs.tertiary, "Nessun momento segnato",
                            "Durante la registrazione premi ⭐ (anche dalla notifica) quando il docente dice qualcosa di importante: " +
                                "il passaggio resta evidenziato qui, nel Markdown e nel riassunto.")
                    }
                    val excerpts = Bookmarks.excerpts(segments.map { TextSegment(it.startMs, it.endMs, it.text) }, marks, words = 40).toMap()
                    items(marks, key = { "m$it" }) { ms ->
                        SoftCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(MaterialTheme.shapes.large).clickable {
                            tab = 0; query = ""; searching = false
                            seekTo(ms)
                            val idx = segments.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
                            scope.launch { listState.animateScrollToItem(headerItems + idx) }
                        }) {
                            Row(verticalAlignment = Alignment.Top) {
                                IconBadge(cs.tertiary, size = 34) { Icon(Icons.Default.Star, null, Modifier.size(18.dp), tint = cs.tertiary) }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(formatTimestamp(ms), style = MonoLabel, color = cs.primary)
                                    Text(excerpts[ms] ?: "La trascrizione di questo punto non è ancora pronta.",
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                                }
                                IconButton(onClick = { scope.launch { dao.setBookmarks(id, Bookmarks.format(marks - ms)) } }) {
                                    Icon(Icons.Default.Close, "Togli", tint = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming && r != null) {
        var title by remember { mutableStateOf(r.title) }
        var course by remember { mutableStateOf(r.course) }
        var glossary by remember(course) { mutableStateOf(app.settings.glossary(course)) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Modifica") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Titolo") }, singleLine = true)
                    OutlinedTextField(course, { course = it }, label = { Text("Corso") }, singleLine = true)
                    GlossaryField(glossary, enabled = course.isNotBlank()) { glossary = it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    renaming = false
                    app.settings.setGlossary(course, glossary)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowMeta(r: Recording, marks: Int) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Meta(Icons.Default.Schedule, formatDate(r.createdAt))
        Meta(Icons.Default.GraphicEq, formatDuration(r.durationMs))
        if (marks > 0) Meta(Icons.Default.Star, "$marks momenti")
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val j = text.indexOf(query, i, ignoreCase = true)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(background = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(j, j + query.length)) }
        i = j + query.length
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, color: Color, title: String, text: String) {
    SoftCard(Modifier.fillMaxWidth().padding(16.dp)) {
        IconBadge(color) { Icon(icon, null, tint = color) }
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
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
    val installedModels by app.models.installed.collectAsState()
    val finalReady = settings.finalModelId in installedModels
    val cs = MaterialTheme.colorScheme
    val isQueued = queued || rec.transcription == TxState.QUEUED
    val canImprove = rec.transcription == TxState.DONE && rec.pass != Pass.FINAL && finalReady &&
        canonicalModelId(rec.modelId) != canonicalModelId(settings.finalModelId)

    data class S(val icon: ImageVector, val color: Color, val title: String, val sub: String?)
    val s = when {
        rec.state != RecState.DONE -> S(Icons.Default.Mic, cs.error, "Registrazione in corso", "Il testo si aggiorna automaticamente")
        tx != null -> S(Icons.Default.AutoAwesome, cs.primary,
            (if (rec.pass == Pass.FINAL) "Trascrizione finale" else "Trascrizione") + " · ${(tx.fraction * 100).toInt()}%",
            (tx.etaMs?.let { "Fine tra ${formatEta(it)}" } ?: "Calcolo del tempo rimanente…") +
                if (rec.pass == Pass.FINAL) " · il testo migliora man mano" else " · continua in background")
        isQueued -> S(Icons.Default.HourglassTop, cs.secondary, "In coda per la trascrizione",
            speed?.let { "Durata stimata ${formatEta(((rec.durationMs - rec.transcribedUntilMs) / it).toLong())}" })
        rec.transcription == TxState.RUNNING -> S(Icons.Default.HourglassTop, cs.secondary, "In attesa di ripresa", null)
        !modelReady && rec.transcription != TxState.DONE -> S(Icons.Default.Download, cs.onSurfaceVariant, "Nessun modello scaricato",
            "Scarica un modello dalle Impostazioni per trascrivere")
        canImprove -> S(Icons.Default.AutoAwesome, cs.secondary, "Anteprima con Whisper ${modelById(rec.modelId).name}",
            "La trascrizione finale è molto più precisa")
        rec.transcription == TxState.DONE -> S(Icons.Default.CheckCircle, Success, "Trascritta con ${modelById(rec.modelId).name}", null)
        rec.transcription == TxState.ERROR -> S(Icons.Default.Close, cs.error, "Trascrizione non riuscita", rec.errorMessage)
        rec.transcribedUntilMs > 0 -> S(Icons.Default.Pause, cs.tertiary, "Interrotta a ${formatTimestamp(rec.transcribedUntilMs)}", null)
        else -> S(Icons.Default.AutoAwesome, cs.onSurfaceVariant, "Non ancora trascritta", null)
    }
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(s.color, size = 38) { Icon(s.icon, null, Modifier.size(20.dp), tint = s.color) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                s.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
            when {
                rec.state != RecState.DONE -> {}
                tx != null -> TextButton(onClick = onCancel) { Text("Stop") }
                isQueued || rec.transcription == TxState.RUNNING -> TextButton(onClick = onCancel) { Text("Annulla") }
                !modelReady && rec.transcription != TxState.DONE -> {}
                canImprove -> {}
                rec.transcription == TxState.DONE -> TextButton(onClick = { onTranscribe(true) }) { Text("Ritrascrivi") }
                rec.transcribedUntilMs > 0 -> TextButton(onClick = { onTranscribe(false) }) { Text("Riprendi") }
                else -> Button(onClick = { onTranscribe(false) }) { Text("Trascrivi") }
            }
        }
        if (tx != null) {
            Spacer(Modifier.height(12.dp))
            RoundProgress(tx.fraction, color = s.color)
        }
        if (canImprove) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onTranscribe(true) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Migliora con ${modelById(settings.finalModelId).name}")
            }
        }
    }
}

@Composable
private fun SummaryTab(rec: Recording, onSummarize: () -> Unit) {
    val app = LocalContext.current.app
    val settings by app.settings.state.collectAsState()
    val installed by app.summaryModels.installed.collectAsState()
    val running by ServiceState.summary.collectAsState()
    val progress = running?.takeIf { it.first == rec.id }?.second
    val ready = settings.summaryModelId in installed
    val canRun = rec.transcription == TxState.DONE && rec.state == RecState.DONE
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        when {
            progress != null -> SoftCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(cs.secondary, size = 38) { Icon(Icons.Default.Psychology, null, tint = cs.secondary) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("L'IA sta leggendo la lezione… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                        Text("Offline, sul telefono", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (progress <= 0f) IndeterminateProgress(color = cs.secondary) else RoundProgress(progress, color = cs.secondary)
            }
            rec.summary != null -> {
                MarkdownText(rec.summary)
                if (canRun && ready) TextButton(onClick = onSummarize, Modifier.padding(top = 8.dp)) { Text("Rigenera il riassunto") }
            }
            else -> SoftCard(Modifier.fillMaxWidth()) {
                IconBadge(cs.secondary) { Icon(Icons.Default.Psychology, null, tint = cs.secondary) }
                Text(
                    when {
                        rec.summaryState == SummaryState.ERROR -> "Riassunto non riuscito"
                        rec.summaryState == SummaryState.QUEUED -> "Riassunto in coda"
                        !ready -> "Riassunto con IA locale"
                        !canRun -> "Il riassunto verrà creato al termine della trascrizione"
                        else -> "Nessun riassunto ancora"
                    },
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    if (!ready) "Scarica il modello per il riassunto nelle Impostazioni: in breve, punti chiave, definizioni e scaletta con i minutaggi."
                    else "In breve, punti chiave, definizioni e scaletta con i minutaggi, scritti sul telefono.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (canRun && ready && rec.summaryState != SummaryState.QUEUED) Button(onClick = onSummarize) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Genera riassunto")
                }
            }
        }
    }
}

/** Parole chiave del corso: Whisper le usa come contesto e le scrive correttamente. */
@Composable
fun GlossaryField(value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, enabled = enabled, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        label = { Text("Parole chiave del corso") },
        placeholder = { Text("es. sup, inf, teorema di Bolzano, Cauchy") },
        supportingText = { Text(if (enabled) "Termini tecnici e nomi separati da virgole: verranno trascritti correttamente" else "Indica prima il corso") },
    )
}
