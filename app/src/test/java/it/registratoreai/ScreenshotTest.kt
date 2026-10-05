package it.registratoreai

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureScreenRoboImage
import it.registratoreai.data.Pass
import it.registratoreai.data.RecState
import it.registratoreai.data.Recording
import it.registratoreai.data.Segment
import it.registratoreai.data.SummaryState
import it.registratoreai.data.TxState
import it.registratoreai.transcription.modelById
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Screenshot delle schermate principali con dati di esempio (in app/build/shots). */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val out = System.getProperty("shots.dir") ?: "build/shots"

    private fun seed(app: RegistratoreApp) = runBlocking {
        // modelli "installati" (file finti) e nessun controllo aggiornamenti, come su un telefono già configurato
        for (id in listOf("base-q8_0", "large-v3-q8_0")) app.models.import(modelById(id), "lmgg".byteInputStream())
        app.settings.update { it.copy(checkUpdates = false) }
        val dao = app.db.recordings()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val texts = File(System.getProperty("demo.segments") ?: "").takeIf { it.isFile }?.readLines()
            ?: listOf("Oggi parliamo della completezza dei numeri reali.", "Ogni insieme limitato superiormente ha un estremo superiore.")
        val id = dao.insert(
            Recording(
                title = "Completezza e estremo superiore", course = "Analisi Matematica 1", createdAt = now - 2 * 3600_000L,
                durationMs = 1_568_000, audioPath = "/nonexistent.wav", state = RecState.DONE, transcription = TxState.DONE,
                transcribedUntilMs = 1_568_000, modelId = "large-v3-q8_0", pass = Pass.FINAL,
                summary = "## In breve\nLa lezione riprende la **completezza** dei numeri reali e mostra come definire √2 come estremo superiore.\n\n" +
                    "## Punti chiave\n- La completezza garantisce l'esistenza del **sup**.\n- Per smentire un'affermazione basta un **controesempio**.\n\n" +
                    "## Concetti e definizioni\n- **Maggiorante**: numero maggiore o uguale a tutti gli elementi.",
                summaryState = SummaryState.DONE, bookmarks = "149000,619000",
            )
        )
        dao.insertSegments(texts.mapIndexed { i, t ->
            val parts = t.split("\t")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: (i * 8000L)
            Segment(recordingId = id, startMs = start, endMs = start + 7000, text = parts.last(), pass = Pass.FINAL)
        })
        dao.insert(Recording(title = "Domanda, offerta ed elasticità", course = "Microeconomia", createdAt = now - day - 3600_000L,
            durationMs = 5_400_000, audioPath = "/x.wav", state = RecState.DONE, transcription = TxState.DONE, modelId = "large-v3-q8_0"))
        dao.insert(Recording(title = "Cinematica del punto", course = "Fisica I", createdAt = now - 3 * day,
            durationMs = 4_800_000, audioPath = "/y.wav", state = RecState.DONE, transcription = TxState.NONE, transcribedUntilMs = 24_000))
        dao.insert(Recording(title = "Matrici e sistemi lineari", course = "Geometria e Algebra", createdAt = now - 27 * day,
            durationMs = 4_200_000, audioPath = "/z.wav", state = RecState.DONE, transcription = TxState.DONE, modelId = "large-v3-q8_0"))
    }

    @Test
    fun screens() {
        val app = RuntimeEnvironment.getApplication() as RegistratoreApp
        seed(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            Thread.sleep(500)
            captureScreenRoboImage("$out/a_home.png")
            compose.onNodeWithText("Completezza e estremo superiore").performClick()
            compose.waitForIdle()
            captureScreenRoboImage("$out/b_detail.png")
            compose.onNodeWithText("Riassunto").performClick()
            compose.waitForIdle()
            captureScreenRoboImage("$out/c_summary.png")
            compose.onNodeWithText("Momenti").performClick()
            compose.waitForIdle()
            captureScreenRoboImage("$out/d_moments.png")
            compose.onNodeWithContentDescription("Indietro").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Impostazioni").performClick()
            compose.waitForIdle()
            captureScreenRoboImage("$out/e_settings.png")
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun dark() {
        val app = RuntimeEnvironment.getApplication() as RegistratoreApp
        seed(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            captureScreenRoboImage("$out/f_home_dark.png")
            compose.onNodeWithText("Completezza e estremo superiore").performClick()
            compose.waitForIdle()
            captureScreenRoboImage("$out/g_detail_dark.png")
        }
    }
}
