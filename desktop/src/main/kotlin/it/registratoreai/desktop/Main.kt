package it.registratoreai.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import it.registratoreai.audio.WavReader
import it.registratoreai.transcription.Chunker
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

/** Verifica senza interfaccia: `--selftest modello.bin audio.wav` trascrive un file e stampa il testo. */
private fun selfTest(args: List<String>) {
    check(Native.load()) { Native.error ?: "libreria non caricata" }
    val engine = WhisperEngine()
    check(engine.ensureLoaded(args[0])) { "modello non caricato" }
    val wav = File.createTempFile("selftest", ".wav")
    AudioImport.toWav16k(File(args[1]), wav)
    var offset = 0L
    WavReader(wav).use { r ->
        while (true) {
            val c = Chunker.nextChunk(r, offset, live = false) as? Chunker.Chunk.Audio ?: break
            engine.transcribe(c.samples, args.getOrElse(2) { "it" }, null, 4)!!
                .mapNotNull { TextCleaner.clean(it.text) }.forEach { println(it) }
            offset += c.samples.size / 16
        }
    }
    println("SELFTEST OK")
}
