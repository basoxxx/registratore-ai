package it.registratoreai.service

import kotlinx.coroutines.flow.MutableStateFlow

data class LiveRecording(
    val recordingId: Long,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    /** Livello audio istantaneo 0..1 per il VU meter. */
    val level: Float = 0f,
)

data class TxProgress(
    val recordingId: Long,
    val processedMs: Long,
    val totalMs: Long,
    val live: Boolean,
    /** Tempo stimato per trascrivere l'audio rimanente (null finché non c'è una misura). */
    val etaMs: Long? = null,
) {
    val fraction: Float get() = if (totalMs <= 0) 0f else (processedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/** Stato condiviso tra il servizio in primo piano e l'interfaccia. */
object ServiceState {
    val recording = MutableStateFlow<LiveRecording?>(null)
    val transcription = MutableStateFlow<TxProgress?>(null)
    val queue = MutableStateFlow<List<Long>>(emptyList())
    /** Riassunto in corso: id della registrazione e avanzamento 0..1. */
    val summary = MutableStateFlow<Pair<Long, Float>?>(null)
    /** Ultima velocità di trascrizione misurata (ms di audio per ms di calcolo). */
    val speed = MutableStateFlow<Float?>(null)
}
