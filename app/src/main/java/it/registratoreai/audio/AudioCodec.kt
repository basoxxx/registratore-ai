package it.registratoreai.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Conversioni audio con le API native di Android (nessuna libreria esterna). */
object AudioCodec {
    private const val TIMEOUT_US = 10_000L

    /** Converte un WAV 16 kHz mono in M4A/AAC (~10x più piccolo). */
    fun wavToM4a(wav: File, out: File, bitrate: Int = 48_000) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(WAV_HEADER_SIZE)
            val total = raf.length() - WAV_HEADER_SIZE
            var readTotal = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteArray(8192)
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val n = raf.read(chunk, 0, minOf(chunk.size, buf.remaining()))
                        val ptsUs = readTotal / 2 * 1_000_000L / SAMPLE_RATE
                        if (n <= 0 || readTotal >= total) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            readTotal += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
    }

    /**
     * Decodifica qualunque file audio supportato da Android (mp3, m4a, ogg, wav, ...)
     * in un WAV 16 kHz mono, il formato richiesto da Whisper.
     * Restituisce la durata in ms.
     */
    fun decodeToWav(context: Context, source: Uri, out: File, onProgress: (Float) -> Unit = {}): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source, null)
        var trackIdx = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { trackIdx = i; break }
        }
        require(trackIdx >= 0) { "Nessuna traccia audio nel file" }
        extractor.selectTrack(trackIdx)
        val inFormat = extractor.getTrackFormat(trackIdx)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val resampler = Resampler()
        val writer = WavWriter(out)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    srcRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += shorts.get(f * channels + c)
                        mono[f] = s / channels
                    }
                    val res = resampler.process(mono, srcRate)
                    val outShorts = ShortArray(res.size) { res[it].toInt().coerceIn(-32768, 32767).toShort() }
                    writer.write(outShorts, outShorts.size)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
            writer.close()
        }
        return samplesToMs(writer.samplesWritten)
    }
}
