package it.registratoreai.transcription

/**
 * Contesto passato a Whisper prima di ogni blocco: nome del corso, glossario del corso
 * (termini tecnici, nomi di autori, sigle) e la coda del testo già trascritto.
 * Whisper tende a scrivere le parole che ha appena "visto", quindi i termini del glossario
 * escono scritti giusti invece che storpiati.
 */
object WhisperPrompt {
    /** Massimo di caratteri del glossario: Whisper accetta ~220 token di contesto in tutto. */
    const val GLOSSARY_CHARS = 350

    /** Chiave con cui salvare il glossario di un corso. */
    fun courseKey(course: String) = course.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Termini separati da virgole/a capo → "a, b, c" (senza doppioni, tagliato a [GLOSSARY_CHARS]). */
    fun normalizeGlossary(text: String): String {
        val terms = text.split(',', ';', '\n').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val sb = StringBuilder()
        for (t in terms) {
            val add = if (sb.isEmpty()) t else ", $t"
            if (sb.length + add.length > GLOSSARY_CHARS) break
            sb.append(add)
        }
        return sb.toString()
    }

    fun build(course: String, glossary: String, tail: String): String? =
        listOf(course.trim(), normalizeGlossary(glossary), tail.trim()).filter { it.isNotEmpty() }
            .joinToString(". ").ifEmpty { null }
}

/** Glossari dei corsi salvati in un file .properties (versione desktop). */
class GlossaryStore(private val file: java.io.File) {
    private val props = java.util.Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    @Synchronized
    fun get(course: String): String =
        if (course.isBlank()) "" else props.getProperty(WhisperPrompt.courseKey(course), "")

    @Synchronized
    fun set(course: String, text: String) {
        if (course.isBlank()) return
        props.setProperty(WhisperPrompt.courseKey(course), text.trim())
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Parole chiave dei corsi") }
    }
}
