package it.registratoreai.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000
const val WAV_HEADER_SIZE = 44L

/** Millisecondi -> indice campione (16 kHz mono). */
fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000
fun samplesToMs(samples: Long): Long = samples * 1000 / SAMPLE_RATE

/**
 * Scrive un WAV PCM 16 bit mono 16 kHz. L'header viene aggiornato periodicamente
 * (vedi [syncHeader]) così il file resta sempre valido e completo anche se l'app
 * viene chiusa all'improvviso o il telefono si spegne.
 */
class WavWriter(file: File, append: Boolean = false) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long

    init {
        if (append && raf.length() >= WAV_HEADER_SIZE) {
            dataBytes = raf.length() - WAV_HEADER_SIZE
            raf.seek(raf.length())
        } else {
            raf.setLength(0)
            dataBytes = 0
            raf.write(header(0))
        }
    }

    val samplesWritten: Long get() = dataBytes / 2

    fun write(samples: ShortArray, count: Int) {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(samples[i])
        raf.write(buf.array())
        dataBytes += count * 2
    }

    fun syncHeader() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun close() {
        syncHeader()
        raf.close()
    }

    companion object {
        fun header(dataLen: Long): ByteArray {
            val b = ByteBuffer.allocate(WAV_HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt())
            b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(1) // mono
            b.putInt(SAMPLE_RATE)
            b.putInt(SAMPLE_RATE * 2)
            b.putShort(2)
            b.putShort(16)
            b.put("data".toByteArray()); b.putInt(dataLen.toInt())
            return b.array()
        }

        /** Ripara l'header di un WAV rimasto incompleto (es. app terminata durante la registrazione). */
        fun repair(file: File): Long {
            if (!file.exists() || file.length() < WAV_HEADER_SIZE) return 0
            RandomAccessFile(file, "rw").use { raf ->
                var data = raf.length() - WAV_HEADER_SIZE
                if (data % 2 != 0L) { data -= 1; raf.setLength(WAV_HEADER_SIZE + data) }
                raf.seek(0)
                raf.write(header(data))
                return samplesToMs(data / 2)
            }
        }
    }
}

/** Legge porzioni di un WAV 16 kHz mono 16 bit, anche mentre il file è ancora in scrittura. */
class WavReader(private val file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")

    fun availableSamples(): Long = ((file.length() - WAV_HEADER_SIZE).coerceAtLeast(0)) / 2

    fun readFloats(startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        raf.seek(WAV_HEADER_SIZE + startSample * 2)
        var read = 0
        while (read < bytes.size) {
            val r = raf.read(bytes, read, bytes.size - read)
            if (r < 0) break
            read += r
        }
        val n = read / 2
        val sb = ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { sb.get(it) / 32768f }
    }

    override fun close() = raf.close()
}

object AudioMath {
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return Math.sqrt(s / (to - from)).toFloat()
    }

    /**
     * Cerca il punto più silenzioso tra [minIdx] e [maxIdx] (finestre da 50 ms):
     * così i blocchi da trascrivere vengono tagliati nelle pause, non a metà parola.
     */
    fun quietestCut(x: FloatArray, minIdx: Int, maxIdx: Int): Int {
        val win = SAMPLE_RATE / 20
        var best = maxIdx
        var bestRms = Float.MAX_VALUE
        var i = minIdx
        while (i + win <= maxIdx) {
            val r = rms(x, i, i + win)
            if (r < bestRms) { bestRms = r; best = i + win / 2 }
            i += win / 2
        }
        return best.coerceIn(minIdx, maxIdx)
    }
}
