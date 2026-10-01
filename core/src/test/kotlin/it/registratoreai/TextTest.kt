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

class EtaTest {
    @org.junit.Test
    fun estimatesRemainingTime() {
        val e = it.registratoreai.transcription.EtaEstimator()
        org.junit.Assert.assertNull(e.etaMs(60_000))
        e.record(audioMs = 30_000, wallMs = 3_000) // 10x tempo reale
        org.junit.Assert.assertEquals(360_000L, e.etaMs(3_600_000)) // 1 h di audio -> 6 min
        org.junit.Assert.assertEquals("~6 min", it.registratoreai.transcription.formatEta(360_000))
        org.junit.Assert.assertEquals("meno di 1 min", it.registratoreai.transcription.formatEta(20_000))
        org.junit.Assert.assertEquals("~1 h 20 min", it.registratoreai.transcription.formatEta(80 * 60_000L))
    }
}
