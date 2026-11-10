/*
 * This project-authored Yingshu integration is licensed under AGPL-3.0.
 * The limited NDI linking permission is in LICENSE-NDI-EXCEPTION.txt.
 * It does not grant exceptions for upstream or other third-party materials.
 */
// Independent JNI bridge to the official NDI SDK. All instance calls are serialized
// by NdiReceiver's worker. No original application's source or binary is used.
#define NDILIB_CPP_DEFAULT_CONSTRUCTORS 0
#include <Processing.NDI.Lib.h>
#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include "ndi_pixels.h"

struct Session {
    NDIlib_find_instance_t finder;
    NDIlib_recv_instance_t receiver;
};
static pthread_once_t runtimeOnce = PTHREAD_ONCE_INIT;
static bool runtimeReady = false;
static void initializeRuntime() { runtimeReady = NDIlib_initialize(); }
static Session* session(jlong handle) { return reinterpret_cast<Session*>(handle); }
static jstring string(JNIEnv* env, const char* value) { return env->NewStringUTF(value ? value : ""); }

extern "C" JNIEXPORT jstring JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeVersion(JNIEnv* env, jclass) {
    return string(env, NDIlib_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeCreate(JNIEnv* env, jclass, jstring extraIps) {
    pthread_once(&runtimeOnce, initializeRuntime);
    if (!runtimeReady) return 0;
    Session* s = static_cast<Session*>(calloc(1, sizeof(Session)));
    if (!s) return 0;
    const char* ips = extraIps ? env->GetStringUTFChars(extraIps, nullptr) : nullptr;
    if (env->ExceptionCheck()) { free(s); return 0; }
    NDIlib_find_create_t options{};
    options.show_local_sources = true;
    options.p_extra_ips = (ips && *ips) ? ips : nullptr;
    s->finder = NDIlib_find_create_v2(&options);
    if (ips) env->ReleaseStringUTFChars(extraIps, ips);
    if (!s->finder) { free(s); return 0; }
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeSources(JNIEnv* env, jclass, jlong handle) {
    Session* s = session(handle);
    uint32_t count = 0;
    const NDIlib_source_t* sources = s && s->finder ? NDIlib_find_get_current_sources(s->finder, &count) : nullptr;
    if (count > 4096) count = 4096;
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count * 2), cls, nullptr);
    if (!result || env->ExceptionCheck()) return nullptr;
    for (uint32_t i = 0; sources && i < count; ++i) {
        jstring name = string(env, sources[i].p_ndi_name);
        if (!name || env->ExceptionCheck()) return nullptr;
        jstring address = string(env, sources[i].p_url_address);
        if (!address || env->ExceptionCheck()) return nullptr;
        env->SetObjectArrayElement(result, i * 2, name);
        env->SetObjectArrayElement(result, i * 2 + 1, address);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(address);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeConnect(JNIEnv* env, jclass, jlong handle, jstring name, jstring address) {
    Session* s = session(handle);
    if (!s) return false;
    if (s->receiver) { NDIlib_recv_destroy(s->receiver); s->receiver = nullptr; }
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    if (env->ExceptionCheck()) return false;
    const char* a = address ? env->GetStringUTFChars(address, nullptr) : nullptr;
    if (env->ExceptionCheck()) { if (n) env->ReleaseStringUTFChars(name, n); return false; }
    NDIlib_recv_create_v3_t options{};
    options.source_to_connect_to.p_ndi_name = n && *n ? n : nullptr;
    options.source_to_connect_to.p_url_address = a && *a ? a : nullptr;
    options.color_format = NDIlib_recv_color_format_RGBX_RGBA;
    options.bandwidth = NDIlib_recv_bandwidth_highest;
    options.allow_video_fields = false;
    options.p_ndi_recv_name = "Yingshu video preview";
    s->receiver = NDIlib_recv_create_v3(&options);
    if (n) env->ReleaseStringUTFChars(name, n);
    if (a) env->ReleaseStringUTFChars(address, a);
    return s->receiver != nullptr;
}

static int64_t elapsedMillis() {
    timespec value{};
    clock_gettime(CLOCK_BOOTTIME, &value); // Android SystemClock.elapsedRealtime time base.
    return static_cast<int64_t>(value.tv_sec) * 1000 + value.tv_nsec / 1000000;
}
static int64_t elapsedMicros() {
    timespec value{};
    clock_gettime(CLOCK_BOOTTIME, &value);
    return static_cast<int64_t>(value.tv_sec) * 1000000 + value.tv_nsec / 1000;
}

static bool rgbSource(const NDIlib_video_frame_v2_t& frame, NdiRgbSource* source) {
    bool blue = frame.FourCC == NDIlib_FourCC_video_type_BGRA || frame.FourCC == NDIlib_FourCC_video_type_BGRX;
    bool red = frame.FourCC == NDIlib_FourCC_video_type_RGBA || frame.FourCC == NDIlib_FourCC_video_type_RGBX;
    if ((!blue && !red) || frame.xres < 1 || frame.xres > 16384 || frame.yres < 1 || frame.yres > 16384) return false;
    *source = {frame.p_data, frame.xres, frame.yres,
               frame.line_stride_in_bytes == 0 ? frame.xres * 4 : frame.line_stride_in_bytes, blue};
    return ndiRgbValid(*source, {0, 0, frame.xres, frame.yres}, frame.xres, frame.yres, frame.xres * 4);
}

static jobject makeBitmap(JNIEnv* env, const NdiRgbSource& source, const NdiRgbRegion& region,
                          int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass || env->ExceptionCheck()) return nullptr;
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!configClass || env->ExceptionCheck()) return nullptr;
    jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (!configField || env->ExceptionCheck()) return nullptr;
    jobject config = env->GetStaticObjectField(configClass, configField);
    if (!config || env->ExceptionCheck()) return nullptr;
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!create || env->ExceptionCheck()) return nullptr;
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, width, height, config);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        env->DeleteLocalRef(bitmap); return nullptr;
    }
    bool copied = ndiCopyRgb(source, region, static_cast<uint8_t*>(pixels), width, height, static_cast<int>(info.stride));
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!copied) { env->DeleteLocalRef(bitmap); return nullptr; }
    return bitmap;
}

// result: type,width,height,fpsN,fpsD,connections,read,discarded,queueNow,queuePeak,formatOK.
// timing: received elapsedRealtime ms,conversion us,SDK received,SDK dropped,source timecode.
// The SDK frame queue is not a display queue: take the newest available frame, with a
// bounded drain so a producer flood cannot prevent stop/close from reaching this worker.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeCapture(JNIEnv* env, jclass, jlong handle,
    jboolean wantPreview, jboolean wantPanel, jlong deliveryNotBeforeMs, jintArray result, jlongArray timing) {
    Session* s = session(handle);
    if (!s || !s->receiver || !result || env->GetArrayLength(result) < 11 ||
        !timing || env->GetArrayLength(timing) < 5) return nullptr;
    NDIlib_video_frame_v2_t video{};
    NDIlib_frame_type_e type = NDIlib_recv_capture_v2(s->receiver, &video, nullptr, nullptr, 100);
    bool held = type == NDIlib_frame_type_video;
    int read = held ? 1 : 0, discarded = 0;
    NDIlib_recv_queue_t queue{};
    NDIlib_recv_get_queue(s->receiver, &queue);
    int peak = queue.video_frames;
    const int64_t drainDeadline = elapsedMicros() + 8000;
    for (int attempt = 0; held && queue.video_frames > 0 && attempt < 8 && elapsedMicros() < drainDeadline; ++attempt) {
        NDIlib_video_frame_v2_t candidate{};
        NDIlib_frame_type_e next = NDIlib_recv_capture_v2(s->receiver, &candidate, nullptr, nullptr, 0);
        if (next == NDIlib_frame_type_video) {
            ++read; ++discarded;
            NDIlib_recv_free_video_v2(s->receiver, &video);
            video = candidate;
        } else if (next == NDIlib_frame_type_error) {
            NDIlib_recv_free_video_v2(s->receiver, &video);
            held = false; ++discarded; type = next;
        }
        NDIlib_recv_get_queue(s->receiver, &queue);
        if (queue.video_frames > peak) peak = queue.video_frames;
        if (next == NDIlib_frame_type_none) break;
    }
    const int64_t receivedAt = elapsedMillis();
    jint values[11] = {static_cast<jint>(type), held ? video.xres : 0, held ? video.yres : 0,
                      held ? video.frame_rate_N : 0, held ? video.frame_rate_D : 0,
                      NDIlib_recv_get_no_connections(s->receiver), read, discarded,
                      queue.video_frames, peak, 1};
    NDIlib_recv_performance_t total{}, dropped{};
    NDIlib_recv_get_performance(s->receiver, &total, &dropped);
    jlong times[5] = {receivedAt, 0, total.video_frames, dropped.video_frames, held ? video.timecode : 0};
    jobjectArray bitmaps = nullptr;
    // If the bounded drain cannot catch up, do not display a known old frame.
    bool freshEnough = held && queue.video_frames == 0;
    if (held && !freshEnough) ++values[7];
    if (freshEnough && receivedAt >= deliveryNotBeforeMs && (wantPreview || wantPanel)) {
        const int64_t conversionStart = elapsedMicros();
        NdiRgbSource source{};
        if (rgbSource(video, &source)) {
            jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
            if (bitmapClass && !env->ExceptionCheck()) bitmaps = env->NewObjectArray(2, bitmapClass, nullptr);
            if (bitmapClass) env->DeleteLocalRef(bitmapClass);
            if (bitmaps && !env->ExceptionCheck() && wantPreview) {
                int width, height; ndiPreviewSize(source.width, source.height, &width, &height);
                jobject preview = makeBitmap(env, source, {0, 0, source.width, source.height}, width, height);
                if (preview && !env->ExceptionCheck()) env->SetObjectArrayElement(bitmaps, 0, preview);
                if (preview) env->DeleteLocalRef(preview);
            }
            NdiRgbRegion panel{};
            if (bitmaps && !env->ExceptionCheck() && wantPanel && ndiTrialPanel(source.width, source.height, &panel)) {
                jobject roi = makeBitmap(env, source, panel, panel.width, panel.height);
                if (roi && !env->ExceptionCheck()) env->SetObjectArrayElement(bitmaps, 1, roi);
                if (roi) env->DeleteLocalRef(roi);
            }
        } else values[10] = 0;
        times[1] = elapsedMicros() - conversionStart;
    }
    if (held) NDIlib_recv_free_video_v2(s->receiver, &video);
    // Always release the SDK frame, including allocation exceptions; do not call JNI
    // with an exception pending. Temporary Java bitmap allocations then become GC eligible.
    if (env->ExceptionCheck()) return nullptr;
    env->SetIntArrayRegion(result, 0, 11, values);
    env->SetLongArrayRegion(timing, 0, 5, times);
    return bitmaps;
}

extern "C" JNIEXPORT void JNICALL
Java_io_yingshu_framekey_NdiReceiver_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    Session* s = session(handle);
    if (!s) return;
    if (s->receiver) NDIlib_recv_destroy(s->receiver);
    if (s->finder) NDIlib_find_destroy(s->finder);
    free(s);
}
