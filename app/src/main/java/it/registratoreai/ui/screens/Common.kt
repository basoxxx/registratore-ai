package it.registratoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.registratoreai.data.RecState
import it.registratoreai.data.Recording
import it.registratoreai.data.TxState
import it.registratoreai.service.TxProgress
import it.registratoreai.text.Bookmarks
import it.registratoreai.text.formatDuration
import it.registratoreai.ui.theme.Pill
import it.registratoreai.ui.theme.SoftCard
import it.registratoreai.ui.theme.courseColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Success = Color(0xFF10B981)

/** Pastiglia con lo stato della trascrizione. */
@Composable
fun StatusPill(rec: Recording, progress: TxProgress?, queued: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (text, color) = when {
        rec.state != RecState.DONE -> "REC" to cs.error
        progress?.recordingId == rec.id -> "${(progress.fraction * 100).toInt()}%" to cs.primary
        queued || rec.transcription == TxState.QUEUED -> "in coda" to cs.secondary
        rec.transcription == TxState.RUNNING -> "in pausa" to cs.tertiary
        rec.transcription == TxState.DONE -> "✓" to Success
        rec.transcription == TxState.ERROR -> "errore" to cs.error
        rec.transcribedUntilMs > 0 -> "parziale" to cs.tertiary
        else -> "da fare" to cs.onSurfaceVariant
    }
    Pill(text, color, modifier)
}

/** Lezione nell'elenco: striscia del colore del corso, titolo, dettagli e stato. */
@Composable
fun LessonCard(rec: Recording, progress: TxProgress?, queued: Boolean, onClick: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(4.dp).height(42.dp).clip(CircleShape)
                    .background(if (rec.course.isBlank()) MaterialTheme.colorScheme.outlineVariant else courseColor(rec.course))
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(rec.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(rec.course, formatDuration(rec.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    val marks = Bookmarks.parse(rec.bookmarks).size
                    if (marks > 0) Text("  ⭐$marks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    if (rec.summary != null) Icon(
                        Icons.Default.Psychology, "Riassunto pronto", Modifier.padding(start = 4.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            StatusPill(rec, progress, queued, Modifier.padding(start = 8.dp))
        }
    }
}

/** Campo di ricerca a pillola. */
@Composable
fun SearchField(value: String, placeholder: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth().heightIn(min = 52.dp), singleLine = true,
        shape = CircleShape,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Cancella") } }) else null,
        placeholder = { Text(placeholder) },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

/** "Oggi", "Ieri", "Questa settimana" oppure il mese. */
fun dayGroup(t: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 86_400_000L
    return when {
        t >= today -> "Oggi"
        t >= today - day -> "Ieri"
        t >= today - 6 * day -> "Questa settimana"
        else -> SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun BoldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
}
