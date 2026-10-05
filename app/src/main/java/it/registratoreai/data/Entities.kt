package it.registratoreai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecState { RECORDING, PAUSED, DONE }

enum class TxState { NONE, QUEUED, RUNNING, DONE, ERROR }

enum class SummaryState { NONE, QUEUED, RUNNING, DONE, ERROR }

/** Passaggi di trascrizione: anteprima (modello leggero, in tempo reale) e finale (modello grande). */
object Pass {
    const val NONE = 0
    const val DRAFT = 1
    const val FINAL = 2
}

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val course: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    /** Percorso assoluto del file audio (WAV durante/ dopo la registrazione, M4A se compresso). */
    val audioPath: String,
    val state: RecState = RecState.RECORDING,
    val transcription: TxState = TxState.NONE,
    /** Fino a che punto dell'audio è già stata fatta la trascrizione (permette di riprendere). */
    val transcribedUntilMs: Long = 0,
    val language: String = "it",
    val modelId: String = "",
    val errorMessage: String? = null,
    /** Documento Markdown nella cartella di esportazione automatica (se attiva). */
    val exportUri: String? = null,
    /** Passaggio in corso o completato (vedi [Pass]). */
    val pass: Int = Pass.NONE,
    /** Riassunto generato dall'IA locale (Markdown). */
    val summary: String? = null,
    val summaryState: SummaryState = SummaryState.NONE,
    /** Momenti segnati con "⭐ Segna" (ms separati da virgola, vedi Bookmarks). */
    val bookmarks: String = "",
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Passaggio che ha prodotto il segmento: la trascrizione finale sostituisce l'anteprima. */
    val pass: Int = Pass.DRAFT,
)
