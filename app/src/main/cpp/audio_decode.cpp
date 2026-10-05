// Decodifica di file audio/video (mp3, m4a, aac, wma, mp4, …) per la versione desktop.
// Windows: Media Foundation (incluso nel sistema). Altri sistemi: non disponibile (-100),
// l'app ripiega su Java Sound / ffmpeg. Su macOS si usa audio_decode_mac.mm.
#include <jni.h>

#ifdef _WIN32
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <string>

template <class T> static void release(T *&p) {
    if (p) { p->Release(); p = nullptr; }
}

static jint decode_mf(JNIEnv *env, const std::wstring &path, jobject sink) {
    jclass cls = env->GetObjectClass(sink);
    jmethodID mid = env->GetMethodID(cls, "pcm", "([FIII)V");
    if (mid == nullptr) return -6;

    IMFSourceReader *reader = nullptr;
    if (FAILED(MFCreateSourceReaderFromURL(path.c_str(), nullptr, &reader))) return -2;
    reader->SetStreamSelection((DWORD) MF_SOURCE_READER_ALL_STREAMS, FALSE);
    reader->SetStreamSelection((DWORD) MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE);

    IMFMediaType *type = nullptr;
    HRESULT hr = MFCreateMediaType(&type);
    if (SUCCEEDED(hr)) hr = type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    if (SUCCEEDED(hr)) hr = type->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_Float);
    if (SUCCEEDED(hr)) hr = reader->SetCurrentMediaType((DWORD) MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, type);
    release(type);
    if (FAILED(hr)) { release(reader); return -3; }

    UINT32 channels = 0, rate = 0;
    auto readFormat = [&]() {
        IMFMediaType *cur = nullptr;
        if (SUCCEEDED(reader->GetCurrentMediaType((DWORD) MF_SOURCE_READER_FIRST_AUDIO_STREAM, &cur))) {
            cur->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &channels);
            cur->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &rate);
            release(cur);
        }
    };
    readFormat();
    if (channels == 0 || rate == 0) { release(reader); return -4; }

    jint result = 0;
    while (true) {
        DWORD flags = 0;
        IMFSample *sample = nullptr;
        hr = reader->ReadSample((DWORD) MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, nullptr, &flags, nullptr, &sample);
        if (FAILED(hr)) { result = -8; break; }
        if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) readFormat();
        if (sample != nullptr) {
            IMFMediaBuffer *buffer = nullptr;
            if (SUCCEEDED(sample->ConvertToContiguousBuffer(&buffer))) {
                BYTE *data = nullptr;
                DWORD len = 0;
                if (SUCCEEDED(buffer->Lock(&data, nullptr, &len))) {
                    jsize n = (jsize) (len / (sizeof(float) * channels)) * (jsize) channels;
                    if (n > 0) {
                        jfloatArray arr = env->NewFloatArray(n);
                        env->SetFloatArrayRegion(arr, 0, n, reinterpret_cast<const jfloat *>(data));
                        env->CallVoidMethod(sink, mid, arr, (jint) (n / (jsize) channels), (jint) channels, (jint) rate);
                        env->DeleteLocalRef(arr);
                    }
                    buffer->Unlock();
                }
                release(buffer);
            }
            release(sample);
            if (env->ExceptionCheck()) { result = -7; break; }
        }
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) break;
    }
    release(reader);
    return result;
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_it_registratoreai_desktop_NativeAudio_decode(JNIEnv *env, jclass, jstring jpath, jobject sink) {
#ifdef _WIN32
    const jchar *chars = env->GetStringChars(jpath, nullptr);
    std::wstring path(reinterpret_cast<const wchar_t *>(chars), (size_t) env->GetStringLength(jpath));
    env->ReleaseStringChars(jpath, chars);
    HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) {
        if (SUCCEEDED(co)) CoUninitialize();
        return -1;
    }
    jint r = decode_mf(env, path, sink);
    MFShutdown();
    if (SUCCEEDED(co)) CoUninitialize();
    return r;
#else
    (void) env; (void) jpath; (void) sink;
    return -100;
#endif
}
