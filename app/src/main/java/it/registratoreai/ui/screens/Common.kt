package it.registratoreai.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.registratoreai.data.RecState
import it.registratoreai.data.Recording
import it.registratoreai.data.TxState
import it.registratoreai.service.TxProgress
import it.registratoreai.transcription.formatEta

@Composable
fun StatusChip(rec: Recording, progress: TxProgress?, queued: Boolean) {
    val running = progress?.recordingId == rec.id
    val label = when {
        rec.state != RecState.DONE -> "● In registrazione"
        running -> "Trascrizione ${(progress!!.fraction * 100).toInt()}%" +
            (progress.etaMs?.takeIf { !progress.live }?.let { " · ${formatEta(it)}" } ?: "")
        queued || rec.transcription == TxState.QUEUED -> "In coda"
        rec.transcription == TxState.RUNNING -> "In attesa di ripresa"
        rec.transcription == TxState.DONE -> "Trascritta"
        rec.transcription == TxState.ERROR -> "Errore"
        rec.transcribedUntilMs > 0 -> "Trascrizione parziale"
        else -> "Da trascrivere"
    }
    AssistChip(
        onClick = {},
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        },
        colors = when (rec.transcription) {
            TxState.DONE -> AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            TxState.ERROR -> AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else -> AssistChipDefaults.assistChipColors()
        },
    )
}
