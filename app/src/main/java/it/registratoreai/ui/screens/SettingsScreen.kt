package it.registratoreai.ui.screens

import it.registratoreai.ui.theme.SoftCard
import it.registratoreai.ui.theme.RoundProgress
import it.registratoreai.ui.theme.Pill
import it.registratoreai.ui.theme.IndeterminateProgress
import it.registratoreai.ui.theme.IconBadge
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import it.registratoreai.BuildConfig
import it.registratoreai.app
import it.registratoreai.summary.SUMMARY_MODELS
import it.registratoreai.transcription.CUSTOM_MODEL_ID
import it.registratoreai.transcription.MODELS
import it.registratoreai.transcription.modelById
import it.registratoreai.update.UpdateChecker
import it.registratoreai.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LANGUAGES = listOf("it" to "Italiano", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "auto" to "Automatica")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()
    val upgradable by app.models.upgradable.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            app.appScope.launch {
                val ok = runCatching {
                    app.models.import(modelById(CUSTOM_MODEL_ID), ctx.contentResolver.openInputStream(uri)!!)
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    importing = false
                    Toast.makeText(ctx, if (ok) "Modello caricato" else "Il file non è un modello Whisper (.bin ggml)", Toast.LENGTH_LONG).show()
                    if (ok) app.settings.update { it.copy(finalModelId = CUSTOM_MODEL_ID) }
                }
            }
        }
    }
    val summaryInstalled by app.summaryModels.installed.collectAsState()
    val summaryDownloads by app.summaryModels.downloads.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            app.settings.update { it.copy(exportTreeUri = uri.toString(), autoExport = true) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(Icons.Default.AutoAwesome, "Modelli di trascrizione") {
                Text(
                    "Whisper gira interamente sul telefono: nessun audio viene inviato online. " +
                        "Durante la lezione un modello leggero mostra l'anteprima; al termine il modello " +
                        "grande ritrascrive tutto con la massima precisione.",
                    style = MaterialTheme.typography.bodySmall,
                )
                fun download(m: it.registratoreai.transcription.WhisperModel) {
                    app.appScope.launch {
                        runCatching { app.models.download(m) }.onFailure {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "Download non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                MODELS.forEach { m ->
                    val isInstalled = m.id in installed
                    val needsUpgrade = m.id in upgradable
                    if (m.id == CUSTOM_MODEL_ID) {
                        ModelSurface {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                                    Text(m.description, style = MaterialTheme.typography.bodySmall)
                                    if (importing) IndeterminateProgress(Modifier.padding(top = 8.dp))
                                    else if (isInstalled) Pill("Caricato", Success, Modifier.padding(top = 4.dp))
                                }
                                if (isInstalled) IconButton(onClick = { app.models.delete(m) }) { Icon(Icons.Default.Delete, "Elimina modello", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                else if (!importing) TextButton(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("Importa") }
                            }
                        }
                        return@forEach
                    }
                    val progress = downloads[m.id]
                    ModelSurface {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${m.name} · ${m.sizeMb} MB", style = MaterialTheme.typography.titleSmall)
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                                when {
                                    progress != null -> RoundProgress(progress, Modifier.padding(top = 8.dp))
                                    needsUpgrade -> Text("Versione ottimizzata disponibile: più veloce e più precisa",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    isInstalled -> Pill("Scaricato", Success, Modifier.padding(top = 4.dp))
                                }
                            }
                            when {
                                progress != null -> {}
                                needsUpgrade -> TextButton(onClick = { download(m) }) { Text("Aggiorna") }
                                isInstalled -> IconButton(onClick = { app.models.delete(m) }) { Icon(Icons.Default.Delete, "Elimina modello", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                else -> IconButton(onClick = { download(m) }) { Icon(Icons.Default.Download, "Scarica", tint = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                }

                Text("Tempo reale (anteprima durante la lezione)", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Per il tempo reale solo i modelli abbastanza veloci
                    MODELS.take(4).forEach { m ->
                        FilterChip(selected = s.modelId == m.id, onClick = { app.settings.update { it.copy(modelId = m.id) } },
                            label = { Text(m.name.substringBefore(" ")) }, colors = chipColors())
                    }
                }
                Toggle(
                    "Trascrizione finale al termine",
                    "Appena finisce la lezione ritrascrive tutto con il modello scelto qui sotto, sostituendo l'anteprima.",
                    s.refineAfter,
                ) { v -> app.settings.update { it.copy(refineAfter = v) } }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MODELS.drop(2).filter { it.id != CUSTOM_MODEL_ID || it.id in installed }.forEach { m ->
                        FilterChip(selected = s.finalModelId == m.id, onClick = { app.settings.update { it.copy(finalModelId = m.id) } },
                            label = { Text(m.name) }, colors = chipColors())
                    }
                }
                if (s.finalModelId !in installed) {
                    Text("Scarica ${modelById(s.finalModelId).name} per attivare la trascrizione finale.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            SettingsSection(Icons.Default.Psychology, "Riassunto con IA locale") {
                Text(
                    "Un modello linguistico gira sul telefono, offline, e scrive il riassunto della lezione " +
                        "(punti chiave, definizioni, scaletta con i minutaggi). Download una tantum.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Toggle("Riassunto automatico", "Al termine della trascrizione finale.", s.autoSummary) { v ->
                    app.settings.update { it.copy(autoSummary = v) }
                }
                SUMMARY_MODELS.forEach { m ->
                    val progress = summaryDownloads[m.id]
                    val isInstalled = m.id in summaryInstalled
                    ModelSurface {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.summaryModelId == m.id, onClick = { app.settings.update { it.copy(summaryModelId = m.id) } })
                            Column(Modifier.weight(1f)) {
                                Text("${m.name} · ${m.sizeMb} MB", style = MaterialTheme.typography.titleSmall)
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                                if (progress != null) RoundProgress(progress, Modifier.padding(top = 8.dp))
                                else if (isInstalled) Pill("Scaricato", Success, Modifier.padding(top = 4.dp))
                            }
                            when {
                                progress != null -> {}
                                isInstalled -> IconButton(onClick = { app.summaryModels.delete(m) }) { Icon(Icons.Default.Delete, "Elimina") }
                                else -> IconButton(onClick = {
                                    app.settings.update { it.copy(summaryModelId = m.id) }
                                    app.appScope.launch {
                                        runCatching { app.summaryModels.download(m) }.onFailure {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "Download non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }) { Icon(Icons.Default.Download, "Scarica", tint = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                }
            }
            SettingsSection(Icons.Default.BatteryChargingFull, "Lavoro in background") {
                Toggle(
                    "Continua anche a schermo spento",
                    "Trascrizione finale e riassunto proseguono con il tablet o il telefono in standby. " +
                        "Se disattivato, il lavoro si mette in pausa quando lo schermo si spegne e riprende dopo.",
                    s.keepAwake,
                ) { v ->
                    app.settings.update { it.copy(keepAwake = v) }
                    if (v) requestBatteryExemption(ctx)
                }
                if (s.keepAwake && !isIgnoringBatteryOptimizations(ctx)) {
                    Text(
                        "Il risparmio energetico di Android può comunque fermare l'app: consenti l'uso della batteria senza restrizioni.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { requestBatteryExemption(ctx) }) { Text("Consenti") }
                }
            }
            SettingsSection(Icons.Default.Language, "Lingua delle lezioni") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LANGUAGES.take(3).forEach { (code, name) ->
                        FilterChip(selected = s.language == code, onClick = { app.settings.update { it.copy(language = code) } }, label = { Text(name) }, colors = chipColors())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LANGUAGES.drop(3).forEach { (code, name) ->
                        FilterChip(selected = s.language == code, onClick = { app.settings.update { it.copy(language = code) } }, label = { Text(name) }, colors = chipColors())
                    }
                }
            }
            SettingsSection(Icons.Default.Tune, "Trascrizione") {
                Toggle("Trascrizione in tempo reale", "Il testo si aggiorna durante la lezione (consuma più batteria).", s.liveTranscription) { v ->
                    app.settings.update { it.copy(liveTranscription = v) }
                }
                Toggle("Trascrivi al termine", "Se non è in tempo reale, avvia la trascrizione appena fermi la registrazione.", s.autoTranscribe) { v ->
                    app.settings.update { it.copy(autoTranscribe = v) }
                }
                Text("Thread di calcolo: ${s.threads}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.threads.toFloat(), onValueChange = { v -> app.settings.update { it.copy(threads = v.toInt()) } },
                    valueRange = 1f..8f, steps = 6,
                )
                Toggle("Comprimi l'audio dopo la trascrizione", "Converte il WAV in M4A (circa 10 volte più piccolo).", s.compressAudio) { v ->
                    app.settings.update { it.copy(compressAudio = v) }
                }
            }
            SettingsSection(Icons.Default.Folder, "Esportazione") {
                Toggle("Timestamp nei file esportati", "Aggiunge [hh:mm:ss] all'inizio di ogni paragrafo.", s.includeTimestamps) { v ->
                    app.settings.update { it.copy(includeTimestamps = v) }
                }
                val folderName = s.exportTreeUri?.let { DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(it))?.name }
                Toggle(
                    "Esportazione automatica Markdown",
                    "Mantiene un file .md per ogni lezione sempre aggiornato nella cartella scelta " +
                        "(perfetto con Obsidian, Drive, Syncthing…).",
                    s.autoExport && s.exportTreeUri != null,
                ) { v ->
                    if (v && s.exportTreeUri == null) folderPicker.launch(null)
                    else app.settings.update { it.copy(autoExport = v) }
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp))
                    Text(folderName?.let { "Cartella: $it" } ?: "Scegli cartella")
                }
            }
            SettingsSection(Icons.Default.SystemUpdate, "Aggiornamenti app") {
                Toggle("Controlla aggiornamenti automaticamente", null, s.checkUpdates) { v ->
                    app.settings.update { it.copy(checkUpdates = v) }
                }
                Text("Versione installata: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            updateMsg = "Controllo…"
                            val u = runCatching { UpdateChecker.check() }
                            update = u.getOrNull()
                            updateMsg = when {
                                u.isFailure -> "Impossibile contattare il server degli aggiornamenti"
                                update == null -> "Hai già l'ultima versione"
                                else -> "Disponibile la versione ${update!!.versionName}"
                            }
                        }
                    }) { Text("Controlla ora") }
                    update?.let { info ->
                        TextButton(onClick = {
                            if (!UpdateChecker.canInstall(ctx)) {
                                UpdateChecker.openInstallPermissionSettings(ctx); return@TextButton
                            }
                            scope.launch {
                                updateProgress = 0f
                                runCatching { UpdateChecker.install(ctx, UpdateChecker.download(ctx, info) { updateProgress = it }) }
                                    .onFailure { updateMsg = "Download non riuscito: ${it.message}" }
                                updateProgress = null
                            }
                        }) { Text("Installa") }
                    }
                }
                updateProgress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(MaterialTheme.colorScheme.primary, size = 34) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ModelSurface(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun isIgnoringBatteryOptimizations(ctx: android.content.Context): Boolean =
    ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@android.annotation.SuppressLint("BatteryLife")
private fun requestBatteryExemption(ctx: android.content.Context) {
    if (isIgnoringBatteryOptimizations(ctx)) return
    runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
