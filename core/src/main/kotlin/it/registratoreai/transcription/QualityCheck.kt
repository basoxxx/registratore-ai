package it.registratoreai.transcription

/**
 * Valuta se la trascrizione di una finestra è sospetta: Whisper a volte "si incanta"
 * (stessa frase ripetuta), restituisce troppo poco testo rispetto al parlato o inventa
 * frasi tipiche. Misurato su una lezione reale: Large v3 e Large v3 Turbo sbagliano in
 * tratti diversi, quindi una seconda opinione sui soli tratti sospetti migliora il risultato.
 */
object QualityCheck {
    data class Score(val uniqueWords: Int, val chars: Int, val suspicious: Boolean, val reason: String = "")

    private fun words(t: String) = Regex("[\\p{L}\\p{N}']+").findAll(t.lowercase()).map { it.value }.toList()

    fun evaluate(texts: List<String>, speechMs: Long): Score {
        val joined = texts.joinToString(" ") { it.trim() }.trim()
        val w = words(joined)
        val unique = w.toSet().size
        val kept = texts.mapNotNull { TextCleaner.clean(it) }.joinToString(" ")
        val chars = kept.length
        val speechS = speechMs / 1000.0

        // stessa frase ripetuta 3+ volte
        val sentences = joined.split(Regex("[.!?]+")).map { words(it).joinToString(" ") }.filter { it.isNotBlank() }
        val maxRepeat = sentences.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        val collapsed = TextCleaner.collapseRepetitions(joined)

        val reason = when {
            speechS >= 3 && kept.isBlank() -> "nessun testo"
            maxRepeat >= 3 -> "frase ripetuta"
            joined.length > 40 && collapsed.length < joined.length * 0.75 -> "parole ripetute"
            w.size >= 12 && unique < w.size * 0.35 -> "poche parole diverse"
            speechS >= 8 && chars / speechS < 4.0 -> "troppo poco testo"
            else -> ""
        }
        return Score(unique, chars, reason.isNotEmpty(), reason)
    }

    /** true se la seconda trascrizione è da preferire alla prima. */
    fun secondIsBetter(first: Score, second: Score): Boolean = when {
        first.suspicious && !second.suspicious -> true
        !first.suspicious && second.suspicious -> false
        else -> second.uniqueWords > first.uniqueWords * 1.2 // entrambe sospette: più contenuto
    }
}
