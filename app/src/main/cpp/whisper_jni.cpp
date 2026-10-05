// Ponte JNI minimale verso whisper.cpp (usato sia dall'app Android sia dalla versione desktop).
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

static bool abort_cb(void * /*user_data*/) { return g_abort.load(); }

extern "C" {

/**
 * Carica i backend di calcolo dalla cartella indicata. Con le build "a varianti"
 * (Android, Windows, Linux) ggml sceglie qui la libreria CPU più veloce supportata
 * dal processore (es. ARMv8.2 dotprod/fp16, ARMv8.6 i8mm, AVX2, AVX-512...).
 */
JNIEXPORT void JNICALL
Java_it_registratoreai_transcription_WhisperLib_initBackends(JNIEnv *env, jobject, jstring dir) {
    static bool done = false;
    if (done) return;
    done = true;
    const char *d = dir ? env->GetStringUTFChars(dir, nullptr) : nullptr;
    ggml_backend_load_all_from_path(d);
    if (d) env->ReleaseStringUTFChars(dir, d);
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        LOGI("Backend disponibile: %s", ggml_backend_dev_description(ggml_backend_dev_get(i)));
    }
}

JNIEXPORT jlong JNICALL
Java_it_registratoreai_transcription_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
    if (ggml_backend_dev_count() == 0) {
        // Nessun backend caricato (cartella sbagliata?): ultimo tentativo con i percorsi predefiniti
        ggml_backend_load_all();
        if (ggml_backend_dev_count() == 0) {
            LOGE("Nessun backend di calcolo disponibile");
            return 0;
        }
    }
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
#if defined(__APPLE__) && !defined(__ANDROID__)
    cparams.use_gpu = true;     // Metal sui Mac
    cparams.flash_attn = true;  // più veloce su GPU (su CPU invece rallenta: misurato -20%)
#else
    cparams.use_gpu = false;
    cparams.flash_attn = false;
#endif
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (!ctx) LOGE("Impossibile caricare il modello %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_it_registratoreai_transcription_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_it_registratoreai_transcription_WhisperLib_requestAbort(JNIEnv *, jobject, jboolean value) {
    g_abort.store(value == JNI_TRUE);
}

/**
 * Trascrive un blocco di campioni float 16 kHz mono.
 * Restituisce il numero di segmenti, oppure -1 in caso di errore.
 * I segmenti si leggono poi con segmentT0/segmentT1/segmentText.
 */
JNIEXPORT jint JNICALL
Java_it_registratoreai_transcription_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr,
                                                           jfloatArray samples, jstring language,
                                                           jstring prompt, jint threads, jint beamSize) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return -1;

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    std::string promptStr;
    if (prompt) {
        const char *p = env->GetStringUTFChars(prompt, nullptr);
        promptStr = p;
        env->ReleaseStringUTFChars(prompt, p);
    }

    // Beam search: più robusto sull'audio difficile; con Large v3 Turbo costa pochissimo
    // perché il decoder ha solo 4 strati (l'encoder, che domina il tempo, gira una volta sola).
    whisper_full_params params = whisper_full_default_params(beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beamSize > 1) params.beam_search.beam_size = beamSize;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = langStr.c_str();
    params.detect_language = false;
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.initial_prompt = promptStr.empty() ? nullptr : promptStr.c_str();
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = nullptr;

    g_abort.store(false);
    int rc = whisper_full(ctx, params, pcm.data(), n);
    if (rc != 0) {
        LOGE("whisper_full fallito: %d", rc);
        return -1;
    }
    return whisper_full_n_segments(ctx);
}

// Tempi in millisecondi (whisper li restituisce in centesimi di secondo)
JNIEXPORT jlong JNICALL
Java_it_registratoreai_transcription_WhisperLib_segmentT0(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(reinterpret_cast<whisper_context *>(ptr), i) * 10;
}

JNIEXPORT jlong JNICALL
Java_it_registratoreai_transcription_WhisperLib_segmentT1(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(reinterpret_cast<whisper_context *>(ptr), i) * 10;
}

// Il testo viene restituito come byte UTF-8 grezzi (decodificati lato Kotlin) per evitare
// problemi con sequenze UTF-8 non valide in NewStringUTF.
JNIEXPORT jbyteArray JNICALL
Java_it_registratoreai_transcription_WhisperLib_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    const char *txt = whisper_full_get_segment_text(reinterpret_cast<whisper_context *>(ptr), i);
    jsize len = txt ? (jsize) strlen(txt) : 0;
    jbyteArray arr = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(txt));
    return arr;
}

// ---------------------------------------------------------------- VAD (rilevamento del parlato)

JNIEXPORT jlong JNICALL
Java_it_registratoreai_transcription_WhisperLib_vadInit(JNIEnv *env, jobject, jstring modelPath, jint threads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_vad_context_params p = whisper_vad_default_context_params();
    p.n_threads = threads;
    p.use_gpu = false;
    whisper_vad_context *v = whisper_vad_init_from_file_with_params(path, p);
    if (!v) LOGE("Impossibile caricare il modello VAD %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(v);
}

JNIEXPORT void JNICALL
Java_it_registratoreai_transcription_WhisperLib_vadFree(JNIEnv *, jobject, jlong ptr) {
    auto *v = reinterpret_cast<whisper_vad_context *>(ptr);
    if (v) whisper_vad_free(v);
}

/**
 * Restituisce gli intervalli di parlato come array piatto [inizio, fine, inizio, fine, ...]
 * in millisecondi relativi all'inizio di [samples]; null in caso di errore.
 */
JNIEXPORT jlongArray JNICALL
Java_it_registratoreai_transcription_WhisperLib_vadSegments(JNIEnv *env, jobject, jlong ptr, jfloatArray samples,
                                                            jfloat threshold, jint minSpeechMs, jint minSilenceMs,
                                                            jfloat maxSpeechS, jint padMs) {
    auto *v = reinterpret_cast<whisper_vad_context *>(ptr);
    if (!v) return nullptr;
    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = threshold;
    vp.min_speech_duration_ms = minSpeechMs;
    vp.min_silence_duration_ms = minSilenceMs;
    vp.max_speech_duration_s = maxSpeechS;
    vp.speech_pad_ms = padMs;
    whisper_vad_segments *segs = whisper_vad_segments_from_samples(v, vp, pcm.data(), n);
    if (!segs) return nullptr;
    int count = whisper_vad_segments_n_segments(segs);
    std::vector<jlong> out(count * 2);
    for (int i = 0; i < count; ++i) {
        // whisper restituisce centesimi di secondo
        out[i * 2] = (jlong) (whisper_vad_segments_get_segment_t0(segs, i) * 10.0f);
        out[i * 2 + 1] = (jlong) (whisper_vad_segments_get_segment_t1(segs, i) * 10.0f);
    }
    whisper_vad_free_segments(segs);
    jlongArray arr = env->NewLongArray(count * 2);
    if (count > 0) env->SetLongArrayRegion(arr, 0, count * 2, out.data());
    return arr;
}

JNIEXPORT jstring JNICALL
Java_it_registratoreai_transcription_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
