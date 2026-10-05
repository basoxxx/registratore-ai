package it.registratoreai.transcription

/** Pulizia del testo e filtro delle tipiche "allucinazioni" di Whisper sul silenzio. */
object TextCleaner {
    private val hallucinations = listOf(
        Regex("sottotitoli", RegexOption.IGNORE_CASE) to Regex("amara|qtss|a cura di|creati|realizzati|revisione", RegexOption.IGNORE_CASE),
        Regex("grazie (a tutti )?per (la|l'|aver) (visione|guardato|ascoltato)", RegexOption.IGNORE_CASE) to null,
        Regex("iscriviti al canale|iscrivetevi al canale", RegexOption.IGNORE_CASE) to null,
        Regex("thank(s| you) for watching|subtitles by|amara\\.org", RegexOption.IGNORE_CASE) to null,
    )

    /** "di di di di di" -> "di"; "va bene va bene va bene va bene" -> "va bene" (whisper che si incastra). */
    fun collapseRepetitions(t: String): String {
        var s = t
        for (n in 1..4) {
            // stessa sequenza di n parole ripetuta 3+ volte di fila
            val word = "[\\p{L}\\p{N}']+"
            val group = (1..n).joinToString("\\s+") { word }
            s = Regex("\\b($group)(?:[\\s,]+\\1\\b){2,}", RegexOption.IGNORE_CASE).replace(s) { it.groupValues[1] }
        }
        return s
    }

    fun clean(raw: String): String? {
        val t = collapseRepetitions(raw.trim().replace(Regex("\\s+"), " "))
        if (t.isEmpty()) return null
        // Solo annotazioni tipo "[Musica]", "(applausi)", "*rumore*"
        if (Regex("^[\\[(*♪].*[\\])*♪]$").matches(t)) return null
        if (!t.any { it.isLetterOrDigit() }) return null
        for ((a, b) in hallucinations) {
            if (a.containsMatchIn(t) && (b == null || b.containsMatchIn(t))) return null
        }
        return t
    }
}
