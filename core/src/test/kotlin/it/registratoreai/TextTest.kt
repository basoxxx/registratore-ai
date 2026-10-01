package it.registratoreai

import it.registratoreai.text.TextSegment
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.transcription.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTest {
    @Test
    fun filtersWhisperHallucinations() {
        assertNull(TextCleaner.clean(" Sottotitoli creati dalla comunità Amara.org "))
        assertNull(TextCleaner.clean("Sottotitoli a cura di QTSS"))
        assertNull(TextCleaner.clean("Grazie per la visione!"))
        assertNull(TextCleaner.clean("[Musica]"))
        assertNull(TextCleaner.clean("(applausi)"))
        assertNull(TextCleaner.clean(" ... "))
        assertEquals("Oggi parliamo di sottotitoli nei film.", TextCleaner.clean("  Oggi parliamo   di sottotitoli nei film. "))
        assertEquals("Grazie, iniziamo.", TextCleaner.clean("Grazie, iniziamo."))
    }

    @Test
    fun groupsSegmentsIntoParagraphs() {
        val segs = listOf(
            TextSegment(0, 2000, "Buongiorno."),
            TextSegment(2100, 4000, "Iniziamo."),
            TextSegment(10_000, 12_000, "Dopo una pausa."),
        )
        val p = TranscriptFormatter.paragraphs(segs)
        assertEquals(2, p.size)
        assertEquals("Buongiorno. Iniziamo.", p[0].text)
        assertEquals(10_000, p[1].startMs)
    }
}
