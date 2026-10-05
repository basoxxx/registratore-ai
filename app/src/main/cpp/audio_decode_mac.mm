// Decodifica di file audio/video (m4a, mp3, aac, mp4, mov, flac, wav, …) con AVFoundation.
// Solo versione desktop macOS: restituisce PCM float mono a 16 kHz a un oggetto Kotlin PcmSink.
#import <AVFoundation/AVFoundation.h>
#include <jni.h>
#include <vector>

extern "C" JNIEXPORT jint JNICALL
Java_it_registratoreai_desktop_NativeAudio_decode(JNIEnv *env, jclass, jstring jpath, jobject sink) {
    @autoreleasepool {
        const char *p = env->GetStringUTFChars(jpath, nullptr);
        NSString *path = [NSString stringWithUTF8String:p];
        env->ReleaseStringUTFChars(jpath, p);
        if (path == nil) return -1;

        AVURLAsset *asset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:path] options:nil];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        NSArray<AVAssetTrack *> *tracks = [asset tracksWithMediaType:AVMediaTypeAudio];
#pragma clang diagnostic pop
        if (tracks.count == 0) return -2;

        NSError *error = nil;
        AVAssetReader *reader = [[AVAssetReader alloc] initWithAsset:asset error:&error];
        if (reader == nil) return -3;
        NSDictionary *settings = @{
            AVFormatIDKey: @(kAudioFormatLinearPCM),
            AVLinearPCMBitDepthKey: @32,
            AVLinearPCMIsFloatKey: @YES,
            AVLinearPCMIsBigEndianKey: @NO,
            AVLinearPCMIsNonInterleaved: @NO,
            AVSampleRateKey: @16000,
            AVNumberOfChannelsKey: @1,
        };
        AVAssetReaderAudioMixOutput *output =
            [AVAssetReaderAudioMixOutput assetReaderAudioMixOutputWithAudioTracks:@[tracks[0]] audioSettings:settings];
        output.alwaysCopiesSampleData = NO;
        if (![reader canAddOutput:output]) return -4;
        [reader addOutput:output];
        if (![reader startReading]) return -5;

        jclass cls = env->GetObjectClass(sink);
        jmethodID mid = env->GetMethodID(cls, "pcm", "([FIII)V");
        if (mid == nullptr) return -6;
        std::vector<float> buf;
        while (true) {
            CMSampleBufferRef sample = [output copyNextSampleBuffer];
            if (sample == nullptr) break;
            CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
            size_t n = block ? CMBlockBufferGetDataLength(block) / sizeof(float) : 0;
            if (n > 0) {
                buf.resize(n);
                CMBlockBufferCopyDataBytes(block, 0, n * sizeof(float), buf.data());
                jfloatArray arr = env->NewFloatArray((jsize) n);
                env->SetFloatArrayRegion(arr, 0, (jsize) n, buf.data());
                env->CallVoidMethod(sink, mid, arr, (jint) n, (jint) 1, (jint) 16000);
                env->DeleteLocalRef(arr);
            }
            CFRelease(sample);
            if (env->ExceptionCheck()) {
                [reader cancelReading];
                return -7;
            }
        }
        return reader.status == AVAssetReaderStatusCompleted ? 0 : -8;
    }
}
