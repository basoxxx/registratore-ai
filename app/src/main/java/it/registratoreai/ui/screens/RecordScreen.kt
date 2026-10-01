package it.registratoreai.ui.screens

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rec?.title ?: "Registrazione") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rec?.course?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                formatDuration(live?.elapsedMs ?: rec?.durationMs ?: 0),
                fontSize = 56.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light,
            )
            Text(
                when {
                    live == null -> "Salvataggio…"
                    live!!.paused -> "In pausa"
                    else -> "● Registrazione in corso"
                },
                color = if (live?.paused == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (live?.level ?: 0f).let { Math.sqrt(it.toDouble()).toFloat() } },
                modifier = Modifier.fillMaxWidth(0.7f).height(8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = {
                        CaptureService.send(ctx, if (live?.paused == true) CaptureService.ACTION_RESUME else CaptureService.ACTION_PAUSE)
                    },
                    enabled = live != null,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(if (live?.paused == true) Icons.Default.PlayArrow else Icons.Default.Pause, "Pausa", Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = { CaptureService.send(ctx, CaptureService.ACTION_STOP) },
                    enabled = live != null,
                    modifier = Modifier.size(80.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, "Stop", Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))

            val liveTx = tx?.takeIf { it.recordingId == id }
            Text(
                when {
                    settings.modelId !in installed -> "Nessun modello scaricato: potrai trascrivere dopo dalle Impostazioni."
                    liveTx != null -> "Trascrizione in tempo reale · aggiornata a ${formatTimestamp(liveTx.processedMs)}" +
                        (liveTx.etaMs?.takeIf { liveTx.totalMs - liveTx.processedMs > 60_000 }
                            ?.let { " · in ritardo, recupero in ${formatEta(it)}" } ?: "")
                    settings.liveTranscription -> "La trascrizione comparirà qui ogni ~30 secondi"
                    settings.autoTranscribe -> "La trascrizione partirà al termine della registrazione"
                    else -> "Trascrizione automatica disattivata"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth().weight(1f)) {
                if (segments.isEmpty()) {
                    Text(
                        "La registrazione continua anche a schermo spento o se chiudi l'app.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(segments, key = { it.id }) { s ->
                        Column {
                            Text(formatTimestamp(s.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(s.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
