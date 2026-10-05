package it.registratoreai.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.registratoreai.MainActivity
import it.registratoreai.R
import it.registratoreai.app
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavWriter
import it.registratoreai.audio.samplesToMs
import it.registratoreai.data.Pass
import it.registratoreai.data.RecState
import it.registratoreai.data.SummaryState
import it.registratoreai.summary.LlamaLib
import it.registratoreai.summary.SummaryRunner
import it.registratoreai.data.Recording
import it.registratoreai.data.TxState
import it.registratoreai.text.formatDuration
import it.registratoreai.transcription.canonicalModelId
import it.registratoreai.transcription.formatEta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Servizio in primo piano che:
 *  - registra l'audio dal microfono in un WAV sempre valido (anche a schermo spento);
 *  - gestisce la coda di trascrizione (in tempo reale durante la registrazione e dopo).
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_TRANSCRIBE = "transcribe"
        const val ACTION_CANCEL_TX = "cancel_tx"
        /** Ritrascrive con il modello finale mantenendo visibile il testo attuale. */
        const val ACTION_REFINE = "refine"
        /** Genera (o rigenera) il riassunto con l'IA locale. */
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_RESUME_PENDING = "resume_pending"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COURSE = "course"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        /** true mentre il servizio è attivo in primo piano. */
        @Volatile var running = false
            private set

        fun send(context: Context, action: String, id: Long = -1, title: String? = null, course: String? = null) {
            val i = Intent(context, CaptureService::class.java).setAction(action)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_COURSE, course)
            // Se il servizio è già in primo piano basta startService (l'app non è "in background").
            if (running && action != ACTION_START) context.startService(i)
            else ContextCompat.startForegroundService(context, i)
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Registrazione e trascrizione", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueLock = Mutex()
    private val queue = ArrayDeque<Long>()
    private var txJob: Job? = null
    private var currentTx: Long? = null

    // Registrazione
    @Volatile private var recordingId: Long? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    private var recordThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val dao by lazy { app.db.recordings() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // La notifica mostra il progresso della trascrizione
        scope.launch {
            ServiceState.transcription.collectLatest { if (recordingId == null) refreshNotification() }
        }
        scope.launch {
            ServiceState.summary.collectLatest { if (recordingId == null) refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        // Va chiamato subito: Android richiede startForeground entro pochi secondi
        // (sempre all'avvio, e per aggiungere il tipo "microfono" a una nuova registrazione).
        val startingRecording = action == ACTION_START && recordingId == null
        if (!running || startingRecording) {
            val ok = goForeground(startingRecording || recordingId != null)
            if (!ok && recordingId == null && txJob?.isActive != true) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = running || ok
        }
        when (action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_TITLE) ?: defaultTitle(),
                intent.getStringExtra(EXTRA_COURSE) ?: "",
            )
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> scope.launch { stopRecording() }
            ACTION_TRANSCRIBE -> if (id > 0) scope.launch { transcribeLater(id) }
            ACTION_REFINE -> if (id > 0) scope.launch { dao.startFinalPass(id); enqueue(id) }
            ACTION_SUMMARIZE -> if (id > 0) scope.launch { enqueueSummary(id) }
            ACTION_CANCEL_TX -> if (id > 0) scope.launch { cancelTranscription(id) }
            ACTION_RESUME_PENDING -> scope.launch {
                dao.pendingTranscriptions().forEach { enqueue(it.id) }
                dao.pendingSummaries().forEach { enqueueSummary(it.id) }
                maybeStop()
            }
            else -> scope.launch { maybeStop() }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- Notifica

    private fun goForeground(mic: Boolean): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mic && hasMic ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile avviare il servizio in primo piano", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        val rec = ServiceState.recording.value
        val tx = ServiceState.transcription.value
        if (rec != null) {
            b.setContentTitle(if (rec.paused) "Registrazione in pausa" else "Registrazione in corso")
            b.setContentText(formatDuration(rec.elapsedMs) + if (tx?.live == true) " · trascrizione in tempo reale" else "")
            if (rec.paused) b.addAction(0, "Riprendi", actionIntent(ACTION_RESUME))
            else b.addAction(0, "Pausa", actionIntent(ACTION_PAUSE))
            b.addAction(0, "Stop", actionIntent(ACTION_STOP))
        } else if (ServiceState.summary.value != null) {
            val (_, p) = ServiceState.summary.value!!
            b.setContentTitle("Riassunto della lezione in corso")
            b.setContentText("${(p * 100).toInt()}% · IA locale sul telefono")
            b.setProgress(1000, (p * 1000).toInt(), p <= 0f)
        } else {
            b.setContentTitle("Trascrizione in corso")
            if (tx != null) {
                b.setContentText(
                    "${(tx.fraction * 100).toInt()}% · ${formatDuration(tx.processedMs)} di ${formatDuration(tx.totalMs)}" +
                        (tx.etaMs?.let { " · fine tra ${formatEta(it)}" } ?: "")
                )
                b.setProgress(1000, (tx.fraction * 1000).toInt(), false)
            } else {
                b.setContentText("Preparazione…")
                b.setProgress(0, 0, true)
            }
        }
        return b.build()
    }

    private fun actionIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, CaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Registrazione

    private fun defaultTitle(): String =
        "Lezione del " + SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.ITALY).format(Date())

    private fun startRecording(title: String, course: String) {
        if (recordingId != null) return
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav")
        val settings = app.settings.current
        val id = runBlocking {
            dao.insert(
                Recording(
                    title = title, course = course, audioPath = file.absolutePath,
                    language = settings.language, modelId = settings.modelId,
                )
            )
        }
        recordingId = id
        stopRequested = false
        paused = false
        ServiceState.recording.value = LiveRecording(id)
        acquireWakeLock()
        recordThread = thread(name = "recorder", priority = Thread.MAX_PRIORITY) { recordLoop(id, file) }
        refreshNotification()

        if (settings.liveTranscription && app.models.isInstalled(settings.modelId)) {
            scope.launch { enqueue(id, front = true) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(id: Long, file: File) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE) * 2,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord non disponibile", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microfono non inizializzato")
            record?.release()
            scope.launch { stopRecording() }
            return
        }
        val writer = WavWriter(file)
        val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var lastSync = 0L
        var lastUi = 0L
        try {
            record.startRecording()
            while (!stopRequested) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    Log.e(TAG, "Errore lettura microfono: $n")
                    break
                }
                if (n == 0 || paused) continue
                writer.write(buf, n)
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
                val elapsed = samplesToMs(writer.samplesWritten)
                ServiceState.recording.value = LiveRecording(id, elapsed, false, peak / 32768f)
                if (elapsed - lastUi >= 1_000) {
                    lastUi = elapsed
                    refreshNotification()
                }
                // Ogni 5 secondi l'header WAV viene aggiornato e il file sincronizzato su disco:
                // la registrazione resta integra anche se l'app viene chiusa improvvisamente.
                if (elapsed - lastSync >= 5_000) {
                    lastSync = elapsed
                    writer.syncHeader()
                    runBlocking { dao.updateRecordingState(id, elapsed, RecState.RECORDING) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la registrazione", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            writer.close()
            runBlocking { dao.updateRecordingState(id, samplesToMs(writer.samplesWritten), RecState.RECORDING) }
        }
        if (!stopRequested) scope.launch { stopRecording() }
    }

    private fun setPaused(value: Boolean) {
        val id = recordingId ?: return
        paused = value
        ServiceState.recording.value = ServiceState.recording.value?.copy(paused = value, level = 0f)
        scope.launch {
            val r = dao.get(id) ?: return@launch
            dao.updateRecordingState(id, r.durationMs, if (value) RecState.PAUSED else RecState.RECORDING)
        }
        refreshNotification()
    }

    private suspend fun stopRecording() {
        val id = recordingId ?: return maybeStop()
        stopRequested = true
        recordThread?.join()
        recordThread = null
        val duration = dao.get(id)?.durationMs ?: 0
        dao.updateRecordingState(id, duration, RecState.DONE)
        recordingId = null
        ServiceState.recording.value = null
        releaseWakeLock()

        val settings = app.settings.current
        val rec = dao.get(id)
        val alreadyQueued = queueLock.withLock { id in queue } || currentTx == id
        if (!alreadyQueued && rec != null && rec.transcription == TxState.NONE && settings.autoTranscribe) {
            transcribeLater(id)
        }
        // Se l'anteprima in tempo reale era già finita, parte subito la trascrizione finale
        if (!alreadyQueued && rec != null && rec.transcription == TxState.DONE) afterTranscription(id)
        // Ora serve solo il tipo "dataSync" (niente più microfono)
        goForeground(false)
        maybeStop()
    }

    // ---------------------------------------------------------------- Trascrizione

    /** Trascrizione "dopo": direttamente con il modello finale se è scaricato. */
    private suspend fun transcribeLater(id: Long) {
        val s = app.settings.current
        when {
            app.models.isInstalled(s.finalModelId) -> { dao.startFinalPass(id); enqueue(id) }
            app.models.isInstalled(s.modelId) -> enqueue(id)
        }
    }

    /** Dopo l'anteprima: trascrizione finale con il modello grande (se diverso e scaricato). */
    private suspend fun afterTranscription(id: Long) {
        val r = dao.get(id) ?: return
        if (r.transcription != TxState.DONE || r.state != RecState.DONE) return
        val s = app.settings.current
        if (r.pass == Pass.DRAFT && s.refineAfter && app.models.isInstalled(s.finalModelId) &&
            canonicalModelId(r.modelId) != canonicalModelId(s.finalModelId)
        ) {
            dao.startFinalPass(id)
            enqueue(id)
        } else if (s.autoSummary && summaries.isReady() && r.summaryState == SummaryState.NONE) {
            // Trascrizione definitiva pronta: riassunto automatico
            enqueueSummary(id)
        }
    }

    // ---------------------------------------------------------------- Riassunto

    private val summaries by lazy { SummaryRunner(app) }
    private val summaryQueue = ArrayDeque<Long>()

    private suspend fun enqueueSummary(id: Long) {
        if (!summaries.isReady()) return
        queueLock.withLock {
            if (id in summaryQueue || ServiceState.summary.value?.first == id) return
            summaryQueue.addLast(id)
        }
        dao.setSummaryState(id, SummaryState.QUEUED)
        ensureWorker()
    }

    private suspend fun enqueue(id: Long, front: Boolean = false) {
        dao.get(id) ?: return
        var preempt: Long? = null
        queueLock.withLock {
            if (currentTx == id || id in queue) return
            if (front) {
                queue.addFirst(id)
                // La lezione in corso ha la precedenza: sospendiamo eventuali trascrizioni vecchie.
                currentTx?.let { preempt = it }
            } else queue.addLast(id)
            ServiceState.queue.value = queue.toList()
        }
        dao.setTranscription(id, TxState.QUEUED)
        // Una lezione che inizia ha la precedenza anche su un riassunto in corso: lo si riprende dopo
        val runningSummary = ServiceState.summary.value?.first
        if (front && runningSummary != null) {
            txJob?.let { it.cancel(); LlamaLib.requestAbort(true); it.join() }
            queueLock.withLock { summaryQueue.addFirst(runningSummary) }
            dao.setSummaryState(runningSummary, SummaryState.QUEUED)
        }
        preempt?.let { old ->
            txJob?.let { it.cancel(); app.engineAbort(); it.join() }
            queueLock.withLock { if (old !in queue) queue.addLast(old); ServiceState.queue.value = queue.toList() }
            dao.setTranscription(old, TxState.QUEUED)
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        if (txJob?.isActive == true) return
        txJob = scope.launch {
            acquireWakeLock()
            while (true) {
                val next = queueLock.withLock {
                    queue.removeFirstOrNull().also { currentTx = it; ServiceState.queue.value = queue.toList() }
                } ?: break
                try {
                    refreshNotification()
                    app.transcriber.run(next)
                    currentTx = null
                    afterTranscription(next)
                } catch (e: CancellationException) {
                    currentTx = null
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Trascrizione fallita", e)
                    dao.setTranscription(next, TxState.ERROR, e.message ?: e.javaClass.simpleName)
                }
                currentTx = null
            }
            // I riassunti partono quando non c'è nessuna trascrizione da fare (e non si sta registrando)
            while (recordingId == null) {
                val next = queueLock.withLock { if (queue.isEmpty()) summaryQueue.removeFirstOrNull() else null } ?: break
                try {
                    refreshNotification()
                    summaries.run(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Riassunto fallito", e)
                }
            }
            if (recordingId == null) releaseWakeLock()
            maybeStop()
        }
    }

    private suspend fun cancelTranscription(id: Long) {
        queueLock.withLock {
            summaryQueue.remove(id)
            queue.remove(id)
            ServiceState.queue.value = queue.toList()
        }
        if (ServiceState.summary.value?.first == id) {
            txJob?.let { it.cancel(); LlamaLib.requestAbort(true); it.join() }
            ServiceState.summary.value = null
        }
        if (currentTx == id) {
            txJob?.let { it.cancel(); app.engineAbort(); it.join() }
            currentTx = null
            ServiceState.transcription.value = null
        }
        dao.setTranscription(id, TxState.NONE)
        if (queueLock.withLock { queue.isNotEmpty() }) ensureWorker() else maybeStop()
    }

    private fun maybeStop() {
        if (recordingId == null && txJob?.isActive != true) {
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- Varie

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RegistratoreAI:capture")
            .apply { setReferenceCounted(false); acquire(8 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Android 15: limite di tempo per i servizi dataSync. Il lavoro è salvato e riprenderà più tardi. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (recordingId == null) {
            app.engineAbort()
            scope.cancel()
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        stopRequested = true
        recordThread?.join(3_000)
        if (recordingId != null) {
            ServiceState.recording.value = null
        }
        app.engineAbort()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
