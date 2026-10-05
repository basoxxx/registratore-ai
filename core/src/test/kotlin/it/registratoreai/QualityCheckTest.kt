package it.registratoreai

import it.registratoreai.transcription.QualityCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casi presi dalla lezione reale di prova (stesso tratto, modelli diversi). */
class QualityCheckTest {
    private val good = listOf(
        "per il vero, sulle mani. Cosa vuol dire metto tre quarti? Formuliamo bene, e quindi immagino sia per il falso.",
        "Ok, proviamo a andare a... Ma è vero",
    )

    @Test
    fun detectsLoopedSentence() {
        val bad = QualityCheck.evaluate(listOf("Per me è vero. Per me è vero. Per me è vero. Per me è vero."), 29_000)
        assertTrue(bad.suspicious)
        val ok = QualityCheck.evaluate(good, 29_000)
        assertFalse(ok.suspicious)
        assertTrue(QualityCheck.secondIsBetter(bad, ok))
        assertFalse(QualityCheck.secondIsBetter(ok, bad))
    }

    @Test
    fun detectsHallucinationAndTooLittleText() {
        assertTrue(QualityCheck.evaluate(listOf("Grazie a tutti."), 29_000).suspicious)
        assertTrue(QualityCheck.evaluate(listOf("Sottotitoli a cura di QTSS"), 20_000).suspicious)
        assertTrue(QualityCheck.evaluate(listOf("di di di di di di di di di di di di di"), 10_000).suspicious)
    }

    @Test
    fun shortQuietWindowIsFine() {
        assertFalse(QualityCheck.evaluate(listOf("Bene."), 2_000).suspicious)
    }
}
