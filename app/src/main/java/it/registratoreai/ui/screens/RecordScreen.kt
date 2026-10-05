package it.registratoreai.ui.screens

import it.registratoreai.ui.theme.SoftCard
import it.registratoreai.ui.theme.RecordGradient
import it.registratoreai.ui.theme.PulsingDot
import it.registratoreai.ui.theme.MonoLabel
import it.registratoreai.ui.theme.LevelBars
import it.registratoreai.ui.theme.GradientBox
import it.registratoreai.ui.theme.CourseTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.registratoreai.app
import it.registratoreai.data.Recording
import it.registratoreai.data.Segment
import it.registratoreai.service.CaptureService
import it.registratoreai.service.ServiceState
import it.registratoreai.text.Bookmarks
import it.registratoreai.text.formatDuration
import it.registratoreai.text.formatTimestamp
import it.registratoreai.transcription.formatEta
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit, onFinished: (Long) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val live by ServiceState.recording.collectAsState()
    val tx by ServiceState.transcription.collectAsState()
    val settings by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()

    // Ricorda l'id della registrazione per poter aprire il dettaglio quando finisce
    var lastId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(live?.recordingId) {
        val id = live?.recordingId
        if (id != null) lastId = id
        else if (lastId > 0) onFinished(lastId)
    }

    val id = live?.recordingId ?: lastId
    val recFlow = remember(id) { if (id > 0) app.db.recordings().observe(id) else flowOf(null) }
    val rec: Recording? by recFlow.collectAsState(initial = null)
    val segFlow = remember(id) { if (id > 0) app.db.recordings().observeSegments(id) else emptyFlow() }
    val segments: List<Segment> by segFlow.collectAsState(initial = emptyList())

    val listState = rememberLazyListState()
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.size - 1)
    }

    val cs = MaterialTheme.colorScheme
    val levels = remember(id) { mutableStateListOf<Float>().apply { repeat(48) { add(0f) } } }
    LaunchedEffect(live?.elapsedMs?.div(100), live?.paused) {
        levels.removeAt(0)
        levels.add(if (live?.paused != false) 0f else live!!.level)
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text(rec?.title ?: "Registrazione", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rec?.course?.takeIf { it.isNotBlank() }?.let { CourseTag(it) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    live == null -> {}
                    live!!.paused -> Icon(Icons.Default.Pause, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    else -> PulsingDot(cs.error)
                }
                Text(
                    when {
                        live == null -> "Salvataggio…"
                        live!!.paused -> "In pausa"
                        else -> "Registrazione in corso"
                    },
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (live?.paused == false) cs.error else cs.onSurfaceVariant,
                )
            }
            Text(
                formatDuration(live?.elapsedMs ?: rec?.durationMs ?: 0),
                fontSize = 60.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light,
            )
            LevelBars(levels, Modifier.fillMaxWidth(0.9f).height(56.dp), color = if (live?.paused == false) cs.error else cs.outline)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (live?.paused == true) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (live?.paused == true) "Riprendi" else "Pausa", enabled = live != null,
                ) {
                    CaptureService.send(ctx, if (live?.paused == true) CaptureService.ACTION_RESUME else CaptureService.ACTION_PAUSE)
                }
                GradientBox(
                    RecordGradient,
                    Modifier.size(88.dp).clickable(enabled = live != null) { CaptureService.send(ctx, CaptureService.ACTION_STOP) },
                    CircleShape,
                ) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).align(Alignment.Center))
                }
                RoundButton(Icons.Default.Star, "Segna questo momento", enabled = live != null && !live!!.paused, tint = cs.tertiary) {
                    CaptureService.send(ctx, CaptureService.ACTION_BOOKMARK)
                }
            }
            val marks = Bookmarks.parse(rec?.bookmarks).size
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Text("Pausa", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("Stop e salva", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(if (marks == 0) "Segna" else "⭐ $marks", style = MaterialTheme.typography.labelSmall,
                    color = if (marks == 0) cs.onSurfaceVariant else cs.tertiary)
            }
            Spacer(Modifier.height(18.dp))

            val liveTx = tx?.takeIf { it.recordingId == id }
            SoftCard(Modifier.fillMaxWidth().weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = cs.primary)
                    Text(
                        when {
                            settings.modelId !in installed -> "Nessun modello scaricato: potrai trascrivere dopo"
                            liveTx != null -> "In tempo reale · aggiornata a ${formatTimestamp(liveTx.processedMs)}" +
                                (liveTx.etaMs?.takeIf { liveTx.totalMs - liveTx.processedMs > 60_000 }
                                    ?.let { " · recupero in ${formatEta(it)}" } ?: "")
                            settings.liveTranscription -> "La trascrizione comparirà qui ogni ~30 secondi"
                            settings.autoTranscribe -> "La trascrizione partirà al termine"
                            else -> "Trascrizione automatica disattivata"
                        },
                        Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    )
                }
                if (segments.isEmpty()) {
                    Text(
                        "La registrazione continua anche a schermo spento o se chiudi l'app. " +
                            "Premi ⭐ quando il docente dice qualcosa di importante.",
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(segments, key = { it.id }) { s ->
                        Row {
                            Text(formatTimestamp(s.startMs), Modifier.width(72.dp).padding(top = 3.dp), style = MonoLabel, color = cs.primary)
                            Text(s.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(64.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(28.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
        }
    }
}
