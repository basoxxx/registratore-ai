package it.registratoreai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecState { RECORDING, PAUSED, DONE }

enum class TxState { NONE, QUEUED, RUNNING, DONE, ERROR }

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
)
