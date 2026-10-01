package it.registratoreai.transcription

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.msToSamples

/** Suddivisione dell'audio in blocchi per Whisper (comune ad Android e desktop). */
object Chunker {
    const val MAX_CHUNK = SAMPLE_RATE * 29          // whisper elabora al massimo 30 s
    const val MIN_CHUNK = SAMPLE_RATE * 22          // cerca una pausa tra 22 e 29 s
    const val MIN_TAIL = SAMPLE_RATE / 2            // ignora code < 0,5 s
    /** Blocchi più silenziosi di così non vengono passati a whisper (evita "allucinazioni"). */
    const val SILENCE_RMS = 0.0025f

    sealed interface Chunk {
        class Audio(val samples: FloatArray) : Chunk
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
                Chunk.Audio(x.copyOf(AudioMath.quietestCut(x, MIN_CHUNK, x.size)))
            }
            live -> Chunk.Wait
            remaining > MIN_TAIL -> Chunk.Audio(reader.readFloats(start, remaining.toInt()))
            else -> Chunk.End
        }
    }
}
