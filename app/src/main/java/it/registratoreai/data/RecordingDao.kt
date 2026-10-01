package it.registratoreai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("UPDATE recordings SET durationMs = :durationMs, state = :state WHERE id = :id")
    suspend fun updateRecordingState(id: Long, durationMs: Long, state: RecState)

    @Query("UPDATE recordings SET transcription = :state, errorMessage = :error WHERE id = :id")
    suspend fun setTranscription(id: Long, state: TxState, error: String? = null)

    @Query("UPDATE recordings SET transcribedUntilMs = :ms WHERE id = :id")
    suspend fun setTranscribedUntil(id: Long, ms: Long)

    @Query("UPDATE recordings SET modelId = :modelId, language = :language WHERE id = :id")
    suspend fun setModel(id: Long, modelId: String, language: String)

    @Query("UPDATE recordings SET audioPath = :path WHERE id = :id")
    suspend fun setAudioPath(id: Long, path: String)

    @Query("UPDATE recordings SET exportUri = :uri WHERE id = :id")
    suspend fun setExportUri(id: Long, uri: String?)

    @Query("UPDATE recordings SET title = :title, course = :course WHERE id = :id")
    suspend fun rename(id: Long, title: String, course: String)

    @Query("SELECT * FROM recordings WHERE transcription IN ('QUEUED', 'RUNNING') ORDER BY createdAt")
    suspend fun pendingTranscriptions(): List<Recording>

    @Query("SELECT * FROM recordings WHERE state != 'DONE'")
    suspend fun unfinishedRecordings(): List<Recording>

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY startMs")
    fun observeSegments(id: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY startMs")
    suspend fun segments(id: Long): List<Segment>

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY startMs DESC LIMIT :n")
    suspend fun lastSegments(id: Long, n: Int): List<Segment>

    @Insert
    suspend fun insertSegments(segments: List<Segment>)

    @Query("DELETE FROM segments WHERE recordingId = :id")
    suspend fun deleteSegments(id: Long)

    @Query("DELETE FROM segments WHERE recordingId = :id AND startMs >= :fromMs")
    suspend fun deleteSegmentsFrom(id: Long, fromMs: Long)
}
