package it.registratoreai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Visualizzazione semplice del Markdown del riassunto: titoli, elenchi e grassetto. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#').trim()),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
                )
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Row {
                    Text("•", Modifier.width(16.dp))
                    Text(inline(line.replaceFirst(Regex("^\\s*[-*•] "), "")), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** **grassetto** e $formule$ (mostrate come testo). */
fun inline(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.replace("$", "").split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p) }
}
