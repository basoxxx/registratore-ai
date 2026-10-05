package it.registratoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import it.registratoreai.ui.theme.BrandGradient

/** Visualizzazione del Markdown del riassunto: titoli, elenchi e grassetto. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("#") -> Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).clip(CircleShape).background(BrandGradient))
                    Text(inline(line.trimStart('#').trim()), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                }
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Row(Modifier.padding(start = 2.dp)) {
                    Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Text(inline(line.replaceFirst(Regex("^\\s*[-*•] "), "")), Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** **grassetto** e $formule$ (mostrate come testo). */
fun inline(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.replace("$", "").split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p) }
}
