package it.registratoreai

import it.registratoreai.summary.Summarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTest {
    @Test
    fun removesEmptySectionsAndThinking() {
        val raw = """
            <think>ragiono...</think>
            Ecco il riassunto:
            ## In breve
            La lezione parla di completezza.
            ## Punti chiave
            - Q non è completo
            ## Da fare
            - Non ci sono esercizi, compiti o scadenze citati nel testo.
        """.trimIndent()
        val out = Summarizer.cleanSections(Summarizer.stripThinking(raw))
        assertTrue(out.startsWith("## In breve"))
        assertTrue(out.contains("- Q non è completo"))
        assertFalse(out.contains("Da fare"))
        assertFalse(out.contains("Ecco il riassunto"))
    }

    @Test
    fun keepsRealTasks() {
        val out = Summarizer.cleanSections("## Da fare\n- Esercizi 10-20 del capitolo 6")
        assertEquals("## Da fare\n- Esercizi 10-20 del capitolo 6", out)
    }
}
