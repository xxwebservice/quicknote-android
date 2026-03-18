#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── initContext ─────────────────────────────────────────────────────────
 * Load a ggml model file and return the context pointer as a jlong.
 * Returns 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_quicknote_app_WhisperCpp_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (!path) {
        LOGE("initContext: model_path is null");
        return 0;
    }

    LOGI("initContext: loading model from %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   /* CPU-only on Android for maximum compat */

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);

    if (!ctx) {
        LOGE("initContext: whisper_init_from_file_with_params failed");
        return 0;
    }

    LOGI("initContext: model loaded, ctx=%p", (void *)ctx);
    return (jlong)(intptr_t)ctx;
}

/* ── freeContext ──────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_com_quicknote_app_WhisperCpp_freeContext(JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (ctx) {
        LOGI("freeContext: releasing ctx=%p", (void *)ctx);
        whisper_free(ctx);
    }
}

/* ── transcribe ──────────────────────────────────────────────────────────
 * Run full whisper inference on float PCM audio (16 kHz mono).
 * Returns the concatenated text of all segments.
 *
 * language: ISO 639-1 code (e.g. "zh", "en") or empty string for auto-detect.
 */
JNIEXPORT jstring JNICALL
Java_com_quicknote_app_WhisperCpp_transcribe(JNIEnv *env, jobject thiz,
                                              jlong context_ptr,
                                              jfloatArray audio_data,
                                              jint num_threads,
                                              jstring language) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) {
        LOGE("transcribe: null context");
        return (*env)->NewStringUTF(env, "error: null whisper context");
    }

    /* Get audio samples */
    jsize n_samples = (*env)->GetArrayLength(env, audio_data);
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (!samples) {
        LOGE("transcribe: failed to get audio array");
        return (*env)->NewStringUTF(env, "error: failed to read audio data");
    }

    LOGI("transcribe: %d samples, %d threads", (int)n_samples, (int)num_threads);

    /* Get language string */
    const char *lang_str = NULL;
    if (language) {
        lang_str = (*env)->GetStringUTFChars(env, language, NULL);
    }

    /* Configure parameters */
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = (int)num_threads;
    params.no_context       = true;
    params.single_segment   = false;
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_timestamps    = true;
    params.token_timestamps = false;

    /* Language: if empty or null, enable auto-detect */
    if (lang_str && strlen(lang_str) > 0) {
        params.language       = lang_str;
        params.detect_language = false;
        LOGI("transcribe: language=%s", lang_str);
    } else {
        params.language        = NULL;
        params.detect_language = true;
        LOGI("transcribe: auto-detect language");
    }

    /* Run inference */
    int ret = whisper_full(ctx, params, samples, (int)n_samples);

    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    if (lang_str) {
        (*env)->ReleaseStringUTFChars(env, language, lang_str);
    }

    if (ret != 0) {
        LOGE("transcribe: whisper_full returned %d", ret);
        return (*env)->NewStringUTF(env, "error: whisper_full failed");
    }

    /* Collect segments into a single string */
    int n_segments = whisper_full_n_segments(ctx);
    LOGI("transcribe: %d segments", n_segments);

    /* Calculate total length for buffer */
    size_t total_len = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *seg_text = whisper_full_get_segment_text(ctx, i);
        if (seg_text) {
            total_len += strlen(seg_text);
        }
    }

    /* Build result string */
    char *result = (char *)malloc(total_len + 1);
    if (!result) {
        LOGE("transcribe: malloc failed");
        return (*env)->NewStringUTF(env, "error: out of memory");
    }
    result[0] = '\0';

    size_t offset = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *seg_text = whisper_full_get_segment_text(ctx, i);
        if (seg_text) {
            size_t seg_len = strlen(seg_text);
            memcpy(result + offset, seg_text, seg_len);
            offset += seg_len;
        }
    }
    result[offset] = '\0';

    LOGI("transcribe: result length=%zu chars", offset);

    jstring jresult = (*env)->NewStringUTF(env, result);
    free(result);

    return jresult;
}

/* ── getSystemInfo ───────────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_quicknote_app_WhisperCpp_getSystemInfo(JNIEnv *env, jobject thiz) {
    const char *info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, info);
}
