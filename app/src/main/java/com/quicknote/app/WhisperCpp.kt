package com.quicknote.app

/**
 * JNI bridge to whisper.cpp native library.
 *
 * This is a singleton object so the native library is loaded once.
 * All methods are called from WhisperBridge on a background thread.
 */
object WhisperCpp {
    init {
        System.loadLibrary("whisper_jni")
    }

    /**
     * Load a ggml model file and return an opaque context pointer.
     * Returns 0 on failure.
     */
    external fun initContext(modelPath: String): Long

    /**
     * Release a previously loaded context. Safe to call with 0.
     */
    external fun freeContext(contextPtr: Long)

    /**
     * Run full transcription on 16 kHz mono float PCM.
     *
     * @param contextPtr  Context from [initContext]
     * @param audioData   16 kHz mono float samples in [-1, 1]
     * @param numThreads  Number of CPU threads for inference
     * @param language    ISO 639-1 code ("en", "zh", ...) or empty string for auto-detect
     * @return Transcribed text, or "error: ..." on failure
     */
    external fun transcribe(contextPtr: Long, audioData: FloatArray, numThreads: Int, language: String): String

    /**
     * Return whisper.cpp system info (SIMD features, etc.).
     */
    external fun getSystemInfo(): String
}
