package it.registratoreai.transcription

import java.io.File
import java.util.Properties

/**
 * Stima del tempo rimanente: misura quanti millisecondi di audio vengono trascritti per
 * ogni millisecondo di calcolo (media mobile) e la applica all'audio ancora da trascrivere.
 */
class EtaEstimator(initialSpeed: Float? = null) {
    /** ms di audio trascritti per ms di calcolo (es. 8 = 8 volte più veloce del tempo reale). */
    @Volatile
    var speed: Float? = initialSpeed?.takeIf { it > 0 }
        private set

    fun record(audioMs: Long, wallMs: Long) {
        if (audioMs <= 0 || wallMs <= 0) return
        val s = audioMs.toFloat() / wallMs
        speed = speed?.let { it * 0.7f + s * 0.3f } ?: s
    }

    fun etaMs(remainingAudioMs: Long): Long? =
        speed?.let { (remainingAudioMs.coerceAtLeast(0) / it).toLong() }
}

/** Ricorda la velocità misurata per ogni modello, per stimare subito anche le lezioni in coda. */
class SpeedStore(private val file: File) {
    private val props = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    @Synchronized
    fun get(modelId: String): Float? = props.getProperty(modelId)?.toFloatOrNull()

    @Synchronized
    fun put(modelId: String, speed: Float?) {
        speed ?: return
        props.setProperty(modelId, speed.toString())
        runCatching { file.parentFile?.mkdirs(); file.outputStream().use { props.store(it, null) } }
    }
}

/** "meno di 1 min", "~4 min", "~1 h 20 min" */
fun formatEta(ms: Long): String {
    val min = Math.round(ms / 60_000.0)
    return when {
        ms < 60_000 -> "meno di 1 min"
        min < 60 -> "~$min min"
        else -> "~${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
    }
}
