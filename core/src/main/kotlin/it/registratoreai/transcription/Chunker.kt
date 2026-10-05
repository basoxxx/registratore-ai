package it.registratoreai.transcription

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.msToSamples
import it.registratoreai.audio.samplesToMs

/** Suddivisione dell'audio in blocchi per Whisper (comune ad Android e desktop). */
object Chunker {
    const val MAX_CHUNK = SAMPLE_RATE * 29          // whisper elabora al massimo 30 s
    const val MIN_CHUNK = SAMPLE_RATE * 22          // cerca una pausa tra 22 e 29 s
    const val MIN_TAIL = SAMPLE_RATE / 2            // ignora code < 0,5 s
    /**
     * Solo il silenzio digitale (microfono muto, ~-80 dBFS) viene saltato senza chiamare whisper.
     * Prima la soglia era 0,0025 (-52 dBFS): con il docente lontano interi blocchi di
     * lezione finivano sotto soglia e venivano scartati.
     */
    const val SILENCE_RMS = 0.0001f

    fun isSilent(samples: FloatArray): Boolean = AudioMath.rms(samples) <= SILENCE_RMS

    /**
     * Audio da passare a Whisper. Può essere un tratto continuo oppure più frammenti di
     * parlato accostati (vedi [SpeechPacker]): [toSourceMs] riporta i tempi sull'audio originale.
     */
    class Window(val samples: FloatArray, val pieces: List<Piece>, val endMs: Long) {
        /** Un frammento: inizia a [winStartMs] nella finestra e a [srcStartMs] nell'audio originale. */
        data class Piece(val winStartMs: Long, val srcStartMs: Long, val lenMs: Long)

        fun toSourceMs(winMs: Long): Long {
            val p = pieces.lastOrNull { it.winStartMs <= winMs } ?: pieces.first()
            return p.srcStartMs + (winMs - p.winStartMs).coerceIn(0, p.lenMs)
        }

        companion object {
            fun continuous(samples: FloatArray, startMs: Long): Window {
                val len = samplesToMs(samples.size.toLong())
                return Window(samples, listOf(Piece(0, startMs, len)), startMs + len)
            }
        }
    }

    sealed interface Chunk {
        class Audio(val window: Window) : Chunk {
            val samples: FloatArray get() = window.samples
        }
        /** Solo silenzio fino a [endMs]: si avanza senza trascrivere. */
        class Skip(val endMs: Long) : Chunk
        data object Wait : Chunk
        data object End : Chunk
    }

    /**
     * Sceglie il prossimo blocco da trascrivere a partire da [offsetMs]:
     * blocchi pieni da ~22-29 s tagliati nel punto più silenzioso; la coda finale
     * solo quando la registrazione è terminata.
     */
    fun nextChunk(reader: WavReader, offsetMs: Long, live: Boolean): Chunk {
        val start = msToSamples(offsetMs)
        val remaining = reader.availableSamples() - start
        return when {
            remaining >= MAX_CHUNK -> {
                val x = reader.readFloats(start, MAX_CHUNK)
                Chunk.Audio(Window.continuous(x.copyOf(AudioMath.quietestCut(x, MIN_CHUNK, x.size)), offsetMs))
            }
            live -> Chunk.Wait
            remaining > MIN_TAIL -> Chunk.Audio(Window.continuous(reader.readFloats(start, remaining.toInt()), offsetMs))
            else -> Chunk.End
        }
    }
}
