// Ponte JNI minimale verso whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

static bool abort_cb(void * /*user_data*/) { return g_abort.load(); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_it_registratoreai_transcription_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
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
                                                           jstring prompt, jint threads) {
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

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
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

JNIEXPORT jstring JNICALL
Java_it_registratoreai_transcription_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
