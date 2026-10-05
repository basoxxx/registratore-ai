package it.registratoreai.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.registratoreai.summary.SUMMARY_MODELS
import it.registratoreai.text.Bookmarks
import it.registratoreai.text.ExportFormat
import it.registratoreai.text.TextSegment
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.text.formatDate
import it.registratoreai.text.formatDuration
import it.registratoreai.text.formatTimestamp
import it.registratoreai.transcription.CUSTOM_MODEL_ID
import it.registratoreai.transcription.GlossaryStore
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface Pane {
    data object Empty : Pane
    data object Settings : Pane
    data class Detail(val id: String) : Pane
}

@Composable
fun AppUi(app: DesktopApp, frame: Frame) {
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var pane by remember { mutableStateOf<Pane>(Pane.Empty) }
            val message by app.messages.collectAsState()
            Row(Modifier.fillMaxSize()) {
                Sidebar(app, frame, pane, onSelect = { pane = it })
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
private fun Sidebar(app: DesktopApp, frame: Frame, pane: Pane, onSelect: (Pane) -> Unit) {
    val lessons by app.lessons.collectAsState()
    val live by app.recording.collectAsState()
    val progress by app.progress.collectAsState()
    val queue by app.queue.collectAsState()
    val update by app.update.collectAsState()
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = (pane as? Pane.Detail)?.id

    Surface(
        Modifier.width(340.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Marchio
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientBox(BrandGradient, Modifier.size(40.dp), RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.align(Alignment.Center), tint = Color.White)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Registratore Lezioni", style = MaterialTheme.typography.titleMedium)
                    Text("Trascrizione offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val l = live
            if (l != null) {
                LiveCard(app, l, lessons.firstOrNull { it.id == l.lessonId }?.bookmarks?.size ?: 0,
                    onOpen = { onSelect(Pane.Detail(l.lessonId)) },
                    onStop = { scope.launch(Dispatchers.IO) { app.stopRecording() } })
            } else {
                GradientBox(BrandGradient, Modifier.fillMaxWidth().height(54.dp).clickable { showNew = true }, RoundedCornerShape(16.dp)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Nuova registrazione", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            update?.let { u ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Versione ${u.version} disponibile", Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        TextButton(onClick = { browse(u.url) }) { Text("Scarica") }
                    }
                }
            }

            val p = progress
            if (p != null || queue.isNotEmpty()) {
                val speed by app.speed.collectAsState()
                val total = remember(p, queue, speed) { app.totalEtaMs() }
                val liveOnly = p != null && p.lessonId == live?.lessonId && queue.isEmpty()
                SoftCard(Modifier.fillMaxWidth().clickable(enabled = p != null) { p?.let { onSelect(Pane.Detail(it.lessonId)) } },
                    shape = MaterialTheme.shapes.medium) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (liveOnly) "Trascrizione in tempo reale" else "Trascrizione in corso",
                            Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                        if (p != null) Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (p != null) {
                        Text(app.lesson(p.lessonId)?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                        RoundProgress(p.fraction)
                    }
                    Text(
                        listOfNotNull(
                            when {
                                liveOnly -> "Segue la registrazione"
                                total != null -> "Fine di tutto tra ${formatEta(total)}"
                                else -> "Calcolo del tempo stimato…"
                            },
                            queue.size.takeIf { it > 0 }?.let { "$it in coda" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SearchField(query, "Cerca nelle lezioni") { query = it }

            val filtered = lessons.filter {
                query.isBlank() || it.title.contains(query, true) || it.course.contains(query, true) ||
                    it.segments.any { s -> s.text.contains(query, true) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (lessons.isEmpty()) item {
                    Text("Nessuna lezione ancora.\nPremi “Nuova registrazione” all'inizio della lezione.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp))
                }
                var lastGroup = ""
                filtered.forEach { lesson ->
                    val g = dayGroup(lesson.createdAt)
                    if (g != lastGroup) {
                        lastGroup = g
                        item(key = "g-$g") { SectionTitle(g, Modifier.padding(top = 6.dp)) }
                    }
                    item(key = lesson.id) {
                        LessonRow(lesson, lesson.id == selected, progress, lesson.id in queue) { onSelect(Pane.Detail(lesson.id)) }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavButton(Icons.Default.FileOpen, "Importa", Modifier.weight(1f)) {
                    chooseFile(frame, "Importa audio o video (mp3, m4a, wav, mp4…)")?.let { f ->
                        scope.launch(Dispatchers.IO) { app.importAudio(f)?.let { onSelect(Pane.Detail(it)) } }
                    }
                }
                NavButton(Icons.Default.Settings, "Impostazioni", Modifier.weight(1f), selected = pane == Pane.Settings) {
                    onSelect(Pane.Settings)
                }
            }
        }
    }

    if (showNew) {
        NewRecordingDialog(
            courses = lessons.map { it.course }.filter { it.isNotBlank() }.distinct().take(6),
            glossaries = app.glossaries,
            onDismiss = { showNew = false },
            onStart = { title, course ->
                showNew = false
                app.startRecording(title, course)?.let { onSelect(Pane.Detail(it)) }
            },
        )
    }
}

@Composable
private fun LiveCard(app: DesktopApp, l: LiveRecording, marks: Int, onOpen: () -> Unit, onStop: () -> Unit) {
    val levels = remember(l.lessonId) { mutableStateListOf<Float>().apply { repeat(36) { add(0f) } } }
    LaunchedEffect(l.elapsedMs / 100, l.paused) {
        levels.removeAt(0)
        levels.add(if (l.paused) 0f else l.level)
    }
    GradientBox(RecordGradient, Modifier.fillMaxWidth().clickable(onClick = onOpen), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.paused) Icon(Icons.Default.Pause, null, Modifier.size(14.dp), tint = Color.White)
                else PulsingDot(Color.White)
                Text(if (l.paused) "In pausa" else "Registrazione in corso", Modifier.padding(start = 8.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Text(formatDuration(l.elapsedMs), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
            LevelBars(levels, Modifier.fillMaxWidth().height(34.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(if (l.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (l.paused) "Riprendi" else "Pausa") {
                    app.setPaused(!l.paused)
                }
                RoundAction(Icons.Default.Stop, "Ferma e salva", filled = true, onClick = onStop)
                RoundAction(Icons.Default.Star, "Segna questo momento", enabled = !l.paused) { app.addBookmark() }
                Text(if (marks == 0) "Segna un momento" else "⭐ $marks segnat${if (marks == 1) "o" else "i"}",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundAction(icon: ImageVector, label: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (filled) Color.White else Color.White.copy(alpha = if (enabled) 0.2f else 0.08f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (filled) Color(0xFFE11D48) else Color.White.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}

@Composable
private fun Tooltip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LessonRow(lesson: Lesson, selected: Boolean, progress: TxProgress?, queued: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).hoverable(hover).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(CircleShape)
            .background(if (lesson.course.isBlank()) MaterialTheme.colorScheme.outlineVariant else courseColor(lesson.course)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(lesson.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    listOf(lesson.course, formatDuration(lesson.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (lesson.bookmarks.isNotEmpty()) Text("  ⭐${lesson.bookmarks.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary)
                if (lesson.summary != null) Icon(Icons.Default.Psychology, "Riassunto pronto", Modifier.padding(start = 4.dp).size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary)
            }
        }
        StatusPill(lesson, progress, queued, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StatusPill(l: Lesson, p: TxProgress?, queued: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (text, color) = when {
        l.recording -> "REC" to cs.error
        p?.lessonId == l.id -> "${(p.fraction * 100).toInt()}%" to cs.primary
        queued || l.status == TxStatus.QUEUED -> "in coda" to cs.secondary
        l.status == TxStatus.ERROR -> "errore" to cs.error
        l.status == TxStatus.DONE -> "✓" to Color(0xFF10B981)
        l.transcribedUntilMs > 0 -> "parziale" to cs.tertiary
        else -> "da fare" to cs.onSurfaceVariant
    }
    Pill(text, color, modifier)
}

@Composable
private fun NavButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth().heightIn(min = 48.dp), singleLine = true,
        shape = CircleShape,
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Cancella", Modifier.size(18.dp)) } }) else null,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

/** "Oggi", "Ieri", "Questa settimana" oppure il mese. */
private fun dayGroup(t: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val day = 86_400_000L
    return when {
        t >= today -> "Oggi"
        t >= today - day -> "Ieri"
        t >= today - 6 * day -> "Questa settimana"
        else -> SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewRecordingDialog(
    courses: List<String>, glossaries: GlossaryStore, onDismiss: () -> Unit, onStart: (String, String) -> Unit,
) {
    val defaultTitle = remember { "Lezione del " + SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(Date()) }
    var title by remember { mutableStateOf(defaultTitle) }
    var course by remember { mutableStateOf(courses.firstOrNull() ?: "") }
    var glossary by remember(course) { mutableStateOf(glossaries.get(course)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            GradientBox(BrandGradient, Modifier.size(48.dp), RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Mic, null, Modifier.align(Alignment.Center), tint = Color.White)
            }
        },
        title = { Text("Nuova registrazione") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Titolo") }, singleLine = true)
                OutlinedTextField(course, { course = it }, Modifier.fillMaxWidth(), label = { Text("Corso") }, singleLine = true,
                    supportingText = { Text("Aiuta la trascrizione e colora la lezione nell'elenco") })
                if (courses.isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        courses.take(6).forEach { c ->
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
                glossaries.set(course, glossary)
                onStart(title.ifBlank { defaultTitle }, course.trim())
            }) {
                Icon(Icons.Default.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Inizia a registrare")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Parole chiave del corso: Whisper le usa come contesto e le scrive correttamente. */
@Composable
private fun GlossaryField(value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, enabled = enabled, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        label = { Text("Parole chiave del corso") },
        placeholder = { Text("es. sup, inf, teorema di Bolzano, Cauchy") },
        supportingText = { Text(if (enabled) "Termini tecnici e nomi separati da virgole: verranno trascritti correttamente" else "Indica prima il corso") },
    )
}

// ---------------------------------------------------------------------------- Benvenuto

@Composable
private fun Welcome(app: DesktopApp, openSettings: () -> Unit) {
    val installed by app.models.installed.collectAsState()
    val settings by app.settings.collectAsState()
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GradientBox(BrandGradient, Modifier.size(84.dp), RoundedCornerShape(26.dp)) {
                Icon(Icons.Default.GraphicEq, null, Modifier.size(44.dp).align(Alignment.Center), tint = Color.White)
            }
            Spacer(Modifier.height(22.dp))
            Text("Registra la lezione,\nritrova tutto scritto.", style = MaterialTheme.typography.headlineMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Trascrizione e riassunto avvengono interamente su questo computer, senza Internet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            when {
                !app.nativeOk -> Text(Native.error ?: "", color = MaterialTheme.colorScheme.error)
                installed.isEmpty() -> {
                    ModelDownloadCard(app, modelById(settings.modelId).id)
                    TextButton(onClick = openSettings) { Text("Scegli un altro modello") }
                }
                else -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Feature(Icons.Default.Mic, "Registra", "Audio sempre al sicuro, anche se il computer si spegne", Modifier.weight(1f).fillMaxHeight())
                    Feature(Icons.Default.AutoAwesome, "Trascrive", "In tempo reale e poi con Large v3 per la massima precisione", Modifier.weight(1f).fillMaxHeight())
                    Feature(Icons.Default.Psychology, "Riassume", "Punti chiave, definizioni e scaletta con i minutaggi", Modifier.weight(1f).fillMaxHeight())
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Lezioni salvate in ${settings.libraryDir}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    SoftCard(modifier) {
        IconBadge(MaterialTheme.colorScheme.primary) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ModelDownloadCard(app: DesktopApp, modelId: String) {
    val downloads by app.models.downloads.collectAsState()
    val model = modelById(modelId)
    val p = downloads[model.id]
    SoftCard(Modifier.width(460.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Scarica il modello di trascrizione", style = MaterialTheme.typography.titleSmall)
                Text("Serve una sola volta: Whisper “${model.name}” (${model.sizeMb} MB).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (p != null) {
            RoundProgress(p)
            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        } else {
            Button(onClick = { download(app, model.id) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scarica")
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

private enum class Tab(val label: String) { TRANSCRIPT("Trascrizione"), SUMMARY("Riassunto"), MOMENTS("Momenti") }

/** Paragrafo della trascrizione con i segmenti che lo compongono. */
private data class Para(val startMs: Long, val segments: List<TextSegment>) {
    val text get() = segments.joinToString(" ") { it.text }
}

private fun paragraphsOf(segments: List<TextSegment>): List<Para> {
    val out = mutableListOf<Para>()
    var cur = mutableListOf<TextSegment>()
    var chars = 0
    for (s in segments) {
        val last = cur.lastOrNull()
        if (last != null && (s.startMs - last.endMs > 2_500 || (chars > 500 && last.text.endsWith('.')) || chars > 900)) {
            out += Para(cur.first().startMs, cur); cur = mutableListOf(); chars = 0
        }
        cur += s; chars += s.text.length + 1
    }
    if (cur.isNotEmpty()) out += Para(cur.first().startMs, cur)
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailPane(app: DesktopApp, frame: Frame, id: String, onDeleted: () -> Unit) {
    val lessons by app.lessons.collectAsState()
    val lesson = lessons.firstOrNull { it.id == id } ?: return
    val settings by app.settings.collectAsState()
    var query by remember(id) { mutableStateOf("") }
    var tab by remember(id) { mutableStateOf(Tab.TRANSCRIPT) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val paras = remember(lesson.segments, query) {
        paragraphsOf(lesson.segments).filter { query.isBlank() || it.text.contains(query, true) }
    }
    LaunchedEffect(lesson.segments.size, lesson.recording) {
        if (lesson.recording && paras.isNotEmpty()) listState.animateScrollToItem(paras.size - 1)
    }
    fun jumpTo(ms: Long) {
        query = ""
        tab = Tab.TRANSCRIPT
        val idx = paragraphsOf(lesson.segments).indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
        scope.launch { listState.animateScrollToItem(idx) }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- Intestazione
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourseTag(lesson.course)
                        if (lesson.recording) Pill("● REC", MaterialTheme.colorScheme.error)
                    }
                    Text(lesson.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Meta(Icons.Default.Schedule, formatDate(lesson.createdAt))
                        Meta(Icons.Default.GraphicEq, formatDuration(lesson.durationMs))
                        if (lesson.bookmarks.isNotEmpty()) Meta(Icons.Default.Star, "${lesson.bookmarks.size} momenti segnati")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.Default.PlayArrow, "Ascolta l'audio") { open(lesson.audio) }
                    ToolIcon(Icons.Default.Folder, "Apri la cartella") { open(lesson.dir) }
                    ToolIcon(Icons.Default.Edit, "Modifica titolo, corso e parole chiave") { renaming = true }
                    ToolIcon(Icons.Default.Delete, "Elimina", enabled = !lesson.recording) { deleting = true }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(onClick = { exportMenu = true }) {
                            Icon(Icons.Default.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Esporta")
                        }
                        DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("Markdown (.md)") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = {
                                exportMenu = false
                                saveFile(frame, TranscriptFormatter.fileName(lesson.title, lesson.createdAt, "md"))?.writeText(lesson.export(ExportFormat.MARKDOWN, settings.timestamps))
                            })
                            DropdownMenuItem(text = { Text("Testo (.txt)") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = {
                                exportMenu = false
                                saveFile(frame, TranscriptFormatter.fileName(lesson.title, lesson.createdAt, "txt"))?.writeText(lesson.export(ExportFormat.TEXT, settings.timestamps))
                            })
                            DropdownMenuItem(text = { Text("Copia il testo") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                exportMenu = false
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(lesson.export(ExportFormat.TEXT, false)), null)
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            StatusCard(app, lesson)
            Spacer(Modifier.height(8.dp))
            // ---- Schede
            val summaryBusy = app.summaryProgress.collectAsState().value?.first == id
            TabRow(
                tab.ordinal, containerColor = Color.Transparent, divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                indicator = { pos ->
                    TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(pos[tab.ordinal]), width = 48.dp, shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                },
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                Tab.entries.forEach { t ->
                    val badge = when (t) {
                        Tab.MOMENTS -> lesson.bookmarks.size.takeIf { it > 0 }?.toString()
                        Tab.SUMMARY -> if (summaryBusy) "…" else if (lesson.summary != null) "✓" else null
                        else -> null
                    }
                    Tab(tab == t, onClick = { tab = t }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge)
                            badge?.let { Pill(it, MaterialTheme.colorScheme.primary, Modifier.padding(start = 6.dp)) }
                        }
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.TRANSCRIPT -> Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                    SearchField(query, "Cerca nella trascrizione", Modifier.widthIn(max = 520.dp).padding(top = 14.dp)) { query = it }
                    if (query.isNotBlank()) Text("${paras.size} paragrafi trovati", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                    val starred = remember(lesson.bookmarks, paras) {
                        paras.indices.filter { i ->
                            val end = paras.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                            lesson.bookmarks.any { it in (if (i == 0) Long.MIN_VALUE else paras[i].startMs) until end }
                        }.toSet()
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 900.dp)) {
                            if (lesson.segments.isEmpty()) item {
                                EmptyNote(
                                    if (lesson.status == TxStatus.DONE) "Nessun parlato riconosciuto in questa registrazione."
                                    else "La trascrizione comparirà qui."
                                )
                            }
                            items(paras.size) { i -> ParagraphRow(paras[i], i in starred, query) }
                        }
                    }
                }
                Tab.SUMMARY -> SummaryTab(app, lesson)
                Tab.MOMENTS -> MomentsTab(lesson, onJump = ::jumpTo, onRemove = { app.removeBookmark(id, it) })
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(lesson.title) }
        var course by remember { mutableStateOf(lesson.course) }
        var glossary by remember(course) { mutableStateOf(app.glossaries.get(course)) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Modifica lezione") },
            text = {
                Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Titolo") }, singleLine = true)
                    OutlinedTextField(course, { course = it }, Modifier.fillMaxWidth(), label = { Text("Corso") }, singleLine = true)
                    GlossaryField(glossary, enabled = course.isNotBlank()) { glossary = it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    renaming = false
                    app.glossaries.set(course, glossary)
                    app.rename(id, title.ifBlank { lesson.title }, course.trim())
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
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
private fun Meta(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ParagraphRow(p: Para, starred: Boolean, query: String) {
    val cs = MaterialTheme.colorScheme
    val final = p.segments.all { it.pass >= 2 }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (starred) cs.tertiaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(84.dp).padding(top = 3.dp)) {
            Text(formatTimestamp(p.startMs), style = MonoLabel, color = cs.primary)
            if (starred) Text("⭐ segnato", style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
        }
        Text(
            highlight(p.text, query, cs.tertiaryContainer),
            style = MaterialTheme.typography.bodyLarge,
            color = if (final) cs.onSurface else cs.onSurface.copy(alpha = 0.72f),
        )
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
private fun EmptyNote(text: String) {
    Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/** Stato della trascrizione (con avanzamento e azioni). */
@Composable
private fun StatusCard(app: DesktopApp, lesson: Lesson) {
    val progress by app.progress.collectAsState()
    val queue by app.queue.collectAsState()
    val settings by app.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    val id = lesson.id
    val p = progress?.takeIf { it.lessonId == id }
    val queued = id in queue || lesson.status == TxStatus.QUEUED
    val cs = MaterialTheme.colorScheme
    if (p == null && !lesson.recording && settings.modelId !in installed && lesson.status != TxStatus.DONE) {
        ModelDownloadCard(app, settings.modelId); return
    }
    val canImprove = lesson.status == TxStatus.DONE && lesson.pass != 2 && settings.finalModelId in installed &&
        canonicalModelId(lesson.modelId) != canonicalModelId(settings.finalModelId)

    data class S(val icon: ImageVector, val color: Color, val title: String, val sub: String?)
    val s = when {
        lesson.recording && p != null -> S(Icons.Default.AutoAwesome, cs.error, "Trascrizione in tempo reale",
            "Aggiornata a ${formatTimestamp(p.processedMs)}" +
                (p.etaMs?.takeIf { p.totalMs - p.processedMs > 60_000 }?.let { " · in ritardo, recupero in ${formatEta(it)}" } ?: ""))
        lesson.recording -> S(Icons.Default.Mic, cs.error, "Registrazione in corso",
            if (settings.liveTranscription && settings.modelId in installed) "Il testo comparirà qui ogni ~30 secondi" else "La trascrizione partirà alla fine")
        p != null -> S(Icons.Default.AutoAwesome, cs.primary,
            if (lesson.pass == 2) "Trascrizione finale con ${modelById(lesson.modelId).name} · ${(p.fraction * 100).toInt()}%"
            else "Trascrizione in corso · ${(p.fraction * 100).toInt()}%",
            (p.etaMs?.let { "Fine stimata tra ${formatEta(it)}" } ?: "Calcolo del tempo rimanente…") +
                if (lesson.pass == 2) " · il testo migliora man mano" else "")
        queued -> S(Icons.Default.HourglassTop, cs.secondary, "In coda per la trascrizione",
            app.speedFor(settings.modelId)?.let { "Durata stimata ${formatEta(((lesson.durationMs - lesson.transcribedUntilMs).coerceAtLeast(0) / it).toLong())}" })
        canImprove -> S(Icons.Default.AutoAwesome, cs.secondary, "Anteprima con Whisper ${modelById(lesson.modelId).name}",
            "La trascrizione finale con ${modelById(settings.finalModelId).name} è molto più precisa")
        lesson.status == TxStatus.DONE -> S(Icons.Default.CheckCircle, Color(0xFF10B981), "Trascritta con Whisper ${modelById(lesson.modelId).name}",
            "Il file Markdown nella cartella della lezione è sempre aggiornato")
        lesson.status == TxStatus.ERROR -> S(Icons.Default.Close, cs.error, "Trascrizione non riuscita", lesson.error)
        lesson.transcribedUntilMs > 0 -> S(Icons.Default.Pause, cs.tertiary, "Trascrizione interrotta a ${formatTimestamp(lesson.transcribedUntilMs)}", null)
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
                p != null && !lesson.recording -> TextButton(onClick = { app.cancel(id) }) { Text("Interrompi") }
                queued -> TextButton(onClick = { app.cancel(id) }) { Text("Annulla") }
                lesson.recording -> {}
                canImprove -> Button(onClick = { app.transcribe(id, restart = true) }) { Text("Migliora con ${modelById(settings.finalModelId).name}") }
                lesson.status == TxStatus.DONE -> TextButton(onClick = { app.transcribe(id, restart = true) }) { Text("Ritrascrivi") }
                lesson.transcribedUntilMs > 0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { app.transcribe(id, restart = true) }) { Text("Da capo") }
                    Button(onClick = { app.transcribe(id, restart = false) }) { Text("Riprendi") }
                }
                else -> Button(onClick = { app.transcribe(id, restart = false) }) { Text("Trascrivi ora") }
            }
        }
        if (p != null) {
            Spacer(Modifier.height(12.dp))
            RoundProgress(p.fraction, color = s.color)
        }
    }
}

@Composable
private fun SummaryTab(app: DesktopApp, lesson: Lesson) {
    val settings by app.settings.collectAsState()
    val installed by app.summaryModels.installed.collectAsState()
    val running by app.summaryProgress.collectAsState()
    val progress = running?.takeIf { it.first == lesson.id }?.second
    val ready = settings.summaryModelId in installed
    val canRun = lesson.status == TxStatus.DONE && !lesson.recording
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp)) {
        Column(Modifier.widthIn(max = 860.dp)) {
            when {
                progress != null -> SoftCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(MaterialTheme.colorScheme.secondary, size = 38) { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.secondary) }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("L'IA locale sta leggendo la lezione… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                            Text("Offline, sul tuo computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (progress <= 0f) IndeterminateProgress(color = MaterialTheme.colorScheme.secondary)
                    else RoundProgress(progress, color = MaterialTheme.colorScheme.secondary)
                }
                lesson.summary != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Generato con l'IA locale", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(lesson.summary), null)
                        }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Copia") }
                        if (canRun && ready) TextButton(onClick = { app.summarize(lesson.id) }) { Text("Rigenera") }
                    }
                    SelectionContainer { MarkdownText(lesson.summary) }
                }
                else -> SoftCard(Modifier.fillMaxWidth()) {
                    IconBadge(MaterialTheme.colorScheme.secondary) { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.secondary) }
                    Text(
                        when {
                            lesson.summaryStatus == TxStatus.ERROR -> "Riassunto non riuscito"
                            lesson.summaryStatus == TxStatus.QUEUED -> "Riassunto in coda"
                            !ready -> "Riassunto con IA locale"
                            !canRun -> "Il riassunto verrà creato al termine della trascrizione"
                            else -> "Nessun riassunto ancora"
                        },
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        if (!ready) "Scarica un modello per il riassunto nelle Impostazioni: In breve, punti chiave, definizioni e scaletta con i minutaggi."
                        else "In breve, punti chiave, definizioni e scaletta con i minutaggi, scritti offline.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    if (canRun && ready && lesson.summaryStatus != TxStatus.QUEUED) Button(onClick = { app.summarize(lesson.id) }) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Genera riassunto")
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentsTab(lesson: Lesson, onJump: (Long) -> Unit, onRemove: (Long) -> Unit) {
    val excerpts = remember(lesson.segments, lesson.bookmarks) { Bookmarks.excerpts(lesson.segments, lesson.bookmarks, words = 50) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 32.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (lesson.bookmarks.isEmpty()) item {
            SoftCard(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
                IconBadge(MaterialTheme.colorScheme.tertiary) { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary) }
                Text("Nessun momento segnato", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text("Durante la registrazione premi ⭐ quando il docente dice qualcosa di importante: " +
                    "il passaggio resta evidenziato qui, nel Markdown e nel riassunto.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val byMs = excerpts.toMap()
        items(lesson.bookmarks) { ms ->
            SoftCard(Modifier.widthIn(max = 860.dp).fillMaxWidth().clickable { onJump(ms) }) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(MaterialTheme.colorScheme.tertiary, size = 36) { Icon(Icons.Default.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(formatTimestamp(ms), style = MonoLabel, color = MaterialTheme.colorScheme.primary)
                        Text(byMs[ms] ?: "La trascrizione di questo punto non è ancora pronta.", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { onRemove(ms) }) { Icon(Icons.Default.Close, "Togli", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(md: String) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(20.dp).clip(CircleShape).background(BrandGradient))
                    Text(inlineMd(line.trimStart('#').trim()), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                }
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Row(Modifier.padding(start = 4.dp)) {
                    Box(Modifier.padding(top = 10.dp).size(6.dp).clip(CircleShape).background(cs.primary))
                    Text(inlineMd(line.replaceFirst(Regex("^\\s*[-*•] "), "")), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Text(inlineMd(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun inlineMd(s: String) = buildAnnotatedString {
    s.replace("$", "").split("**").forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
      Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineMedium)

        SettingsSection(Icons.Default.AutoAwesome, "Modelli di trascrizione",
            "Durante la lezione un modello leggero mostra l'anteprima; al termine il modello grande ritrascrive tutto. Tutto offline.") {
            MODELS.forEach { m ->
                val p = downloads[m.id]
                val needsUpgrade = m.id in upgradable
                if (m.id == CUSTOM_MODEL_ID) {
                    ModelRow(m.name, m.description, when {
                        importing -> null
                        m.id in installed -> "Caricato"
                        else -> null
                    }, progress = if (importing) -1f else null) {
                        if (m.id in installed) TextButton(onClick = { app.models.delete(m) }) { Text("Elimina") }
                        else if (!importing) OutlinedButton(onClick = {
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
                    return@forEach
                }
                ModelRow("${m.name}  ·  ${m.sizeMb} MB", m.description, when {
                    needsUpgrade -> "Versione ottimizzata disponibile"
                    m.id in installed -> "Scaricato"
                    else -> null
                }, progress = p, highlight = needsUpgrade) {
                    when {
                        p != null -> {}
                        needsUpgrade -> Button(onClick = { download(app, m.id) }) { Text("Aggiorna") }
                        m.id in installed -> TextButton(onClick = { app.models.delete(m) }) { Text("Elimina") }
                        else -> OutlinedButton(onClick = { download(app, m.id) }) {
                            Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Scarica")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Anteprima in tempo reale", style = MaterialTheme.typography.labelLarge)
            ChipRow(MODELS.take(4).map { it.id to it.name }, s.modelId) { v -> app.updateSettings { it.copy(modelId = v) } }
            Toggle("Trascrizione finale al termine", "Appena finisce la lezione ritrascrive tutto con il modello scelto qui sotto.", s.refineAfter) { v ->
                app.updateSettings { it.copy(refineAfter = v) }
            }
            ChipRow(MODELS.drop(2).filter { it.id != CUSTOM_MODEL_ID || it.id in installed }.map { it.id to it.name }, s.finalModelId) { v ->
                app.updateSettings { it.copy(finalModelId = v) }
            }
            if (s.finalModelId !in installed) Text("Scarica ${modelById(s.finalModelId).name} per attivare la trascrizione finale.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(Icons.Default.Psychology, "Riassunto con IA locale",
            "Un modello linguistico gira sul computer, offline, e scrive punti chiave, definizioni e scaletta con i minutaggi.") {
            Toggle("Riassunto automatico", "Al termine della trascrizione finale.", s.autoSummary) { v -> app.updateSettings { it.copy(autoSummary = v) } }
            SUMMARY_MODELS.forEach { m ->
                val p = summaryDownloads[m.id]
                ModelRow("${m.name}  ·  ${m.sizeMb} MB", m.description, if (m.id in summaryInstalled) "Scaricato" else null, progress = p,
                    leading = { RadioButton(s.summaryModelId == m.id, onClick = { app.updateSettings { it.copy(summaryModelId = m.id) } }) }) {
                    when {
                        p != null -> {}
                        m.id in summaryInstalled -> TextButton(onClick = { app.summaryModels.delete(m) }) { Text("Elimina") }
                        else -> OutlinedButton(onClick = {
                            app.updateSettings { it.copy(summaryModelId = m.id) }
                            app.scope.launch(Dispatchers.IO) {
                                runCatching { app.summaryModels.download(m) }.onFailure { app.messages.value = "Download non riuscito: ${it.message}" }
                            }
                        }) { Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Scarica") }
                    }
                }
            }
        }

        SettingsSection(Icons.Default.Tune, "Trascrizione") {
            Text("Lingua delle lezioni", style = MaterialTheme.typography.labelLarge)
            ChipRow(LANGUAGES, s.language) { v -> app.updateSettings { it.copy(language = v) } }
            Toggle("Trascrizione in tempo reale", "Il testo si aggiorna durante la lezione.", s.liveTranscription) { v -> app.updateSettings { it.copy(liveTranscription = v) } }
            Toggle("Trascrivi al termine", "Avvia la trascrizione appena fermi la registrazione.", s.autoTranscribe) { v -> app.updateSettings { it.copy(autoTranscribe = v) } }
            Toggle("Orari nei file Markdown", "Aggiunge [hh:mm:ss] all'inizio di ogni paragrafo.", s.timestamps) { v -> app.updateSettings { it.copy(timestamps = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(220.dp)) {
                    Text("Thread di calcolo: ${s.threads}", style = MaterialTheme.typography.bodyMedium)
                    Text("Più thread = più veloce, ma il computer è più occupato", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(s.threads.toFloat(), onValueChange = { v -> app.updateSettings { it.copy(threads = v.toInt()) } },
                    valueRange = 1f..16f, steps = 14, modifier = Modifier.weight(1f).padding(start = 16.dp))
            }
        }

        SettingsSection(Icons.Default.Memory, "Lavoro in background") {
            Toggle(
                "Impedisci lo standby mentre lavora",
                "Il computer non va in standby finché trascrizione e riassunto non sono finiti (lo schermo può spegnersi). " +
                    "Durante la registrazione lo standby è sempre impedito.",
                s.keepAwake,
            ) { v -> app.updateSettings { it.copy(keepAwake = v) } }
        }

        SettingsSection(Icons.Default.Folder, "Archivio",
            "Ogni lezione è una cartella con l'audio e la trascrizione in Markdown sempre aggiornata: va bene una cartella sincronizzata (iCloud, OneDrive, Drive) o il vault di Obsidian.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Text(s.libraryDir, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MonoLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { open(File(s.libraryDir).apply { mkdirs() }) }) { Text("Apri") }
                TextButton(onClick = { chooseFolder(frame)?.let { f -> app.updateSettings { it.copy(libraryDir = f.path) } } }) { Text("Cambia") }
            }
        }

        SettingsSection(Icons.Default.SystemUpdate, "Aggiornamenti", "Versione installata: ${app.version}") {
            Toggle("Controlla aggiornamenti all'avvio", null, s.checkUpdates) { v -> app.updateSettings { it.copy(checkUpdates = v) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                app.update.collectAsState().value?.let { u -> Button(onClick = { browse(u.url) }) { Text("Scarica ${u.version}") } }
                updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Audio, trascrizioni e riassunti non lasciano mai questo computer.", Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
}

@Composable
private fun SettingsSection(icon: ImageVector, title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(MaterialTheme.colorScheme.primary, size = 36) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ModelRow(
    title: String, description: String, status: String?, progress: Float? = null, highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = if (highlight) cs.tertiaryContainer.copy(alpha = 0.5f) else cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(start = if (leading != null) 4.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    status?.let { Pill(it, if (highlight) cs.tertiary else Color(0xFF10B981), Modifier.padding(start = 8.dp)) }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                when {
                    progress == null -> {}
                    progress < 0f -> IndeterminateProgress(Modifier.padding(top = 8.dp))
                    else -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundProgress(progress, Modifier.weight(1f))
                        Text("${(progress * 100).toInt()}%", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            actions()
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, name) ->
            FilterChip(selected == id, onClick = { onSelect(id) }, label = { Text(name) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ))
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
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

