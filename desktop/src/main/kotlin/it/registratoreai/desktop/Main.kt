package it.registratoreai.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import it.registratoreai.audio.WavReader
import it.registratoreai.transcription.Chunker
import it.registratoreai.transcription.SpeechPacker
import it.registratoreai.transcription.WhisperVad
import it.registratoreai.transcription.TextCleaner
import it.registratoreai.transcription.WhisperEngine
import java.io.File

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") return selfTest(args.drop(1))
    val app = DesktopApp()
    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 760.dp))
        Window(
            onCloseRequest = { app.shutdown(); exitApplication() },
            state = state,
            title = "Registratore Lezioni",
            icon = painterResource("icon.png"),
        ) {
            window.minimumSize = java.awt.Dimension(820, 560)
            AppUi(app, window)
        }
    }
}

/**
 * Verifica senza interfaccia: `--selftest modello.bin audio.wav [lingua] [plain|vad]`
 * trascrive un file come fa l'app e stampa testo, numero di finestre e tempo impiegato.
 */
private fun selfTest(args: List<String>) {
    check(Native.load()) { Native.error ?: "libreria non caricata" }
    val engine = WhisperEngine()
    check(engine.ensureLoaded(args[0])) { "modello non caricato" }
    val wav = File.createTempFile("selftest", ".wav")
    AudioImport.toWav16k(File(args[1]), wav)
    val lang = args.getOrElse(2) { "it" }
    val useVad = args.getOrElse(3) { "vad" } == "vad"
    val packer = if (useVad) {
        val model = WhisperVad.extractModel(File(System.getProperty("java.io.tmpdir"), "rl-vad"))!!
        SpeechPacker(WhisperVad(model.absolutePath))
    } else SpeechPacker { null }
    val threads = Runtime.getRuntime().availableProcessors()
    var offset = 0L
    var windows = 0
    var whisperMs = 0L
    val t0 = System.currentTimeMillis()
    WavReader(wav).use { r ->
        while (true) {
            val w = when (val c = packer.next(r, offset, live = false)) {
                is Chunker.Chunk.Audio -> c.window
                is Chunker.Chunk.Skip -> { offset = c.endMs; continue }
                else -> break
            }
            val t = System.currentTimeMillis()
            engine.transcribe(w.samples, lang, null, threads)!!
                .forEach { s -> TextCleaner.clean(s.text)?.let { println("[${w.toSourceMs(s.startMs) / 1000}s] $it") } }
            whisperMs += System.currentTimeMillis() - t
            windows++
            offset = w.endMs
        }
    }
    println("SELFTEST OK modalità=${if (useVad) "vad" else "plain"} finestre=$windows whisper=${whisperMs} ms totale=${System.currentTimeMillis() - t0} ms")
}
