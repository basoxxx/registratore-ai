package it.registratoreai.transcription

/** Pulizia del testo e filtro delle tipiche "allucinazioni" di Whisper sul silenzio. */
object TextCleaner {
    private val hallucinations = listOf(
        Regex("sottotitoli", RegexOption.IGNORE_CASE) to Regex("amara|qtss|a cura di|creati|realizzati|revisione", RegexOption.IGNORE_CASE),
        Regex("grazie (a tutti )?per (la|l'|aver) (visione|guardato|ascoltato)", RegexOption.IGNORE_CASE) to null,
        Regex("iscriviti al canale|iscrivetevi al canale", RegexOption.IGNORE_CASE) to null,
        Regex("thank(s| you) for watching|subtitles by|amara\\.org", RegexOption.IGNORE_CASE) to null,
    )

    fun clean(raw: String): String? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
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
