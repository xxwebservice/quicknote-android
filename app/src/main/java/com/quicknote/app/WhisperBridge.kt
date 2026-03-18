package com.quicknote.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder

class WhisperBridge(private val context: Context) {

    // Java-callable functional interface for file-level download progress
    fun interface ProgressCallback {
        fun onProgress(fileIdx: Int, totalFiles: Int, fileName: String)
    }

    // Java-callable functional interface for byte-level download progress
    fun interface ByteProgressCallback {
        fun onByteProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    data class ModelDef(
        val id: String,
        val name: String,
        val modelUrl: String,
        val sizeMb: Int,
    )

    companion object {
        private const val TAG = "WhisperBridge"

        // ── Whisper models (ggml quantized, single file each) ────────────
        val MODELS = listOf(
            ModelDef(
                "tiny", "Tiny \u00b7 32MB \u00b7 \u6700\u5feb",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
                32,
            ),
            ModelDef(
                "base", "Base \u00b7 60MB \u00b7 \u63a8\u8350",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
                60,
            ),
            ModelDef(
                "small", "Small \u00b7 190MB \u00b7 \u6700\u51c6",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
                190,
            ),
        )

        // Model filename inside the model directory
        private const val MODEL_FILENAME = "model.bin"

        // ── Diarization model (~37MB total, stored in models/diarization/) ─
        // Segmentation: pyannote segmentation-3.0 converted to ONNX (~11.5MB)
        // Embedding:    3D-Speaker multilingual speaker embedding (~25MB)
        const val DIARIZE_SEG_URL  = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx"
        const val DIARIZE_EMB_URL  = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        const val DIARIZE_SIZE_MB  = 37
    }

    // ── Paths ──────────────────────────────────────────────────────────────

    private fun modelDir(modelId: String): File =
        File(File(context.getExternalFilesDir("QuickNote"), "models"), modelId)

    private fun modelFile(modelId: String): File =
        File(modelDir(modelId), MODEL_FILENAME)

    private fun diarizationDir(): File =
        File(File(context.getExternalFilesDir("QuickNote"), "models"), "diarization")

    // ── Whisper model management ─────────────────────────────────────────

    fun isModelDownloaded(modelId: String): Boolean {
        val file = modelFile(modelId)
        return file.exists() && file.length() > 1000
    }

    fun getModelsJson(): String {
        val arr = JSONArray()
        for (m in MODELS) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("sizeMb", m.sizeMb)
                put("downloaded", isModelDownloaded(m.id))
            })
        }
        return arr.toString()
    }

    /** Returns null on success, error message on failure. Blocking. */
    fun downloadModel(modelId: String, progress: ProgressCallback, byteProgress: ByteProgressCallback? = null): String? {
        val model = MODELS.find { it.id == modelId } ?: return "unknown model: $modelId"
        val dir = modelDir(modelId)
        dir.mkdirs()

        return try {
            progress.onProgress(1, 1, MODEL_FILENAME)
            downloadFile(model.modelUrl, File(dir, MODEL_FILENAME), byteProgress)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelId", e)
            dir.listFiles()?.forEach { it.delete() }
            e.message ?: "download failed"
        }
    }

    fun deleteModel(modelId: String) {
        val dir = modelDir(modelId)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ── Diarization model management ─────────────────────────────────────

    fun isDiarizationModelDownloaded(): Boolean {
        val dir = diarizationDir()
        return File(dir, "segmentation.onnx").exists() &&
               File(dir, "embedding.onnx").exists()
    }

    fun getDiarizationModelJson(): String = JSONObject().apply {
        put("downloaded", isDiarizationModelDownloaded())
        put("sizeMb", DIARIZE_SIZE_MB)
    }.toString()

    /** Returns null on success, error message on failure. Blocking. */
    fun downloadDiarizationModel(progress: ProgressCallback, byteProgress: ByteProgressCallback? = null): String? {
        val dir = diarizationDir()
        dir.mkdirs()
        val files = listOf(
            "segmentation.onnx" to DIARIZE_SEG_URL,
            "embedding.onnx"    to DIARIZE_EMB_URL,
        )
        return try {
            files.forEachIndexed { idx, (name, url) ->
                progress.onProgress(idx + 1, files.size, name)
                downloadFile(url, File(dir, name), byteProgress)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Diarization model download failed", e)
            dir.listFiles()?.forEach { it.delete() }
            e.message ?: "download failed"
        }
    }

    fun deleteDiarizationModel() {
        val dir = diarizationDir()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ── Transcription ────────────────────────────────────────────────────

    /** Plain transcription without speaker labels. Blocking. */
    fun transcribeAudio(audioFilename: String, modelId: String, language: String): String {
        if (!isModelDownloaded(modelId)) return "error: model not downloaded"
        val audioFile = File(context.getExternalFilesDir("QuickNote"), audioFilename)
        if (!audioFile.exists()) return "error: audio file not found ($audioFilename)"
        return try {
            Log.i(TAG, "Decoding M4A: ${audioFile.length()} bytes")
            val samples = decodeAudioToFloat(audioFile.absolutePath)
                ?: return "error: audio decode failed"
            Log.i(TAG, "Decoded ${samples.size} samples \u2014 running Whisper")
            runWhisper(samples, modelId, language)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            "error: ${e.message}"
        }
    }

    /**
     * Transcription with speaker diarization.
     * Output format (one line per speaker segment):
     *   [说话人A 0:00-0:35] text...
     *   [说话人B 0:35-0:47] text...
     * Falls back to plain transcription when diarization fails.
     */
    fun transcribeWithDiarization(audioFilename: String, modelId: String, language: String): String {
        if (!isModelDownloaded(modelId))       return "error: whisper model not downloaded"
        if (!isDiarizationModelDownloaded())   return "error: diarization model not downloaded"
        val audioFile = File(context.getExternalFilesDir("QuickNote"), audioFilename)
        if (!audioFile.exists())               return "error: audio file not found ($audioFilename)"

        val diarDir = diarizationDir()

        return try {
            // 1. Decode full audio to 16 kHz mono float[]
            Log.i(TAG, "Diarization: decoding audio")
            val samples = decodeAudioToFloat(audioFile.absolutePath)
                ?: return "error: audio decode failed"
            Log.i(TAG, "Diarization: ${samples.size} samples decoded")

            // 2. Run speaker diarization (sherpa-onnx)
            val sdConfig = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote   = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model  = File(diarDir, "segmentation.onnx").absolutePath
                    ),
                    numThreads = 2,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model      = File(diarDir, "embedding.onnx").absolutePath,
                    numThreads = 2,
                ),
                clustering     = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn  = 0.3f,
                minDurationOff = 0.5f,
            )
            val sd = OfflineSpeakerDiarization(null, sdConfig)
            Log.i(TAG, "Diarization: running segmentation + clustering")
            val segments = sd.process(samples)
            sd.release()
            Log.i(TAG, "Diarization: ${segments.size} segments")

            if (segments.isEmpty()) {
                Log.w(TAG, "Zero diarization segments \u2014 falling back to plain transcription")
                return runWhisper(samples, modelId, language)
            }

            // 3. Load whisper.cpp context once for all segments
            val modelPath = modelFile(modelId).absolutePath
            val ctxPtr = WhisperCpp.initContext(modelPath)
            if (ctxPtr == 0L) {
                Log.e(TAG, "Failed to init whisper context for diarization")
                return "error: failed to load whisper model"
            }

            try {
                val speakerChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                val sb = StringBuilder()
                val numThreads = 4

                segments.forEachIndexed { i, seg ->
                    val startSample = (seg.start * 16_000).toInt().coerceIn(0, samples.size)
                    val endSample   = (seg.end   * 16_000).toInt().coerceIn(0, samples.size)
                    if (endSample - startSample < 3_200) return@forEachIndexed // skip < 0.2 s

                    val segSamples = samples.copyOfRange(startSample, endSample)
                    val text = WhisperCpp.transcribe(ctxPtr, segSamples, numThreads, language).trim()

                    if (text.isNotEmpty() && !text.startsWith("error:")) {
                        val label = if (seg.speaker < speakerChars.length)
                            speakerChars[seg.speaker].toString() else "${seg.speaker + 1}"
                        val startStr = fmtSecs(seg.start)
                        val endStr   = fmtSecs(seg.end)
                        sb.append("[\u8bf4\u8bdd\u4eba$label $startStr-$endStr] $text\n")
                    }
                    Log.i(TAG, "Segment ${i+1}/${segments.size} transcribed")
                }

                val result = sb.toString().trim()
                if (result.isEmpty()) runWhisper(samples, modelId, language) else result
            } finally {
                WhisperCpp.freeContext(ctxPtr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Diarization transcription error", e)
            "error: ${e.message}"
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Run whisper.cpp transcription on full audio.
     * Loads and frees the context within this call.
     */
    private fun runWhisper(samples: FloatArray, modelId: String, language: String): String {
        val modelPath = modelFile(modelId).absolutePath
        val ctxPtr = WhisperCpp.initContext(modelPath)
        if (ctxPtr == 0L) {
            Log.e(TAG, "runWhisper: failed to init context")
            return "error: failed to load whisper model"
        }
        return try {
            val numThreads = 4
            val text = WhisperCpp.transcribe(ctxPtr, samples, numThreads, language).trim()
            Log.i(TAG, "Whisper complete: ${text.length} chars")
            text
        } finally {
            WhisperCpp.freeContext(ctxPtr)
        }
    }

    private fun fmtSecs(secs: Float): String {
        val s = secs.toInt()
        return "${s / 60}:${String.format("%02d", s % 60)}"
    }

    private fun downloadFile(urlStr: String, dest: File, byteProgress: ByteProgressCallback? = null) {
        if (dest.exists() && dest.length() > 100) return
        Log.i(TAG, "Downloading: $urlStr \u2192 ${dest.name}")

        // Manually follow redirects — HttpURLConnection does NOT follow cross-host
        // redirects (e.g. huggingface.co -> cas-bridge.xethub.hf.co) even with
        // instanceFollowRedirects = true.
        var currentUrl = urlStr
        var finalCode = -1
        var conn: HttpURLConnection? = null

        for (hop in 0 until 10) {
            val c = URL(currentUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false  // we handle redirects ourselves
            c.connectTimeout = 30_000
            c.readTimeout    = 300_000
            c.setRequestProperty("User-Agent", "QuickNote/1.0 Android")

            val code = c.responseCode
            Log.i(TAG, "  hop $hop: HTTP $code  url=${currentUrl.take(120)}")

            if (code in 301..308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw Exception("Redirect $code without Location header at hop $hop")
                // Handle both absolute and relative redirect URLs
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                Log.i(TAG, "  hop $hop: redirecting to ${currentUrl.take(120)}")
            } else {
                // Terminal response (200, 4xx, 5xx, etc.)
                finalCode = code
                conn = c
                break
            }
        }

        if (conn == null) {
            throw Exception("Too many redirects (10 hops) for ${dest.name}")
        }
        if (finalCode != 200) {
            conn.disconnect()
            throw Exception("HTTP $finalCode for ${dest.name} at ${currentUrl.take(120)}")
        }

        val contentLength = conn.contentLength.toLong()  // -1 if unknown
        Log.i(TAG, "  Content-Length: $contentLength bytes for ${dest.name}")

        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(65_536)
                    var totalRead = 0L
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        totalRead += n
                        byteProgress?.onByteProgress(totalRead, contentLength)
                    }
                }
            }
            Log.i(TAG, "  saved ${tmp.length()} bytes \u2192 ${dest.name}")
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) throw Exception("Failed to rename tmp \u2192 ${dest.name}")
    }

    // ── Audio decoding: M4A/AAC -> 16 kHz mono float[] ──────────────────
    private fun decodeAudioToFloat(filePath: String): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        var audioTrack = -1
        var fmt: MediaFormat? = null
        var mime: String? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) { audioTrack = i; fmt = f; mime = m; break }
        }

        if (audioTrack < 0 || fmt == null || mime == null) { extractor.release(); return null }

        val srcRate  = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        extractor.selectTrack(audioTrack)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val pcm  = ArrayList<Short>(8_000_000)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false

        while (!outDone) {
            if (!inDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val sz  = extractor.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                val outBuf = codec.getOutputBuffer(outIdx)!!
                val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.hasRemaining()) pcm.add(sb.get())
                codec.releaseOutputBuffer(outIdx, false)
            }
        }

        codec.stop(); codec.release(); extractor.release()

        val mono = FloatArray(pcm.size / channels) { i -> pcm[i * channels].toFloat() / 32768f }
        return if (srcRate != 16_000) resample(mono, srcRate, 16_000) else mono
    }

    private fun resample(src: FloatArray, srcRate: Int, targetRate: Int): FloatArray {
        val len   = (src.size.toLong() * targetRate / srcRate).toInt()
        val ratio = srcRate.toFloat() / targetRate
        return FloatArray(len) { i ->
            val pos = i * ratio
            val j   = pos.toInt()
            val f   = pos - j
            if (j + 1 < src.size) src[j] * (1 - f) + src[j + 1] * f
            else src.getOrElse(j) { 0f }
        }
    }
}
