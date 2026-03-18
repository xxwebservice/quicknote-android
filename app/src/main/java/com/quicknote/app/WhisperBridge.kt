package com.quicknote.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder

class WhisperBridge(private val context: Context) {

    // Java-callable functional interface for download progress
    fun interface ProgressCallback {
        fun onProgress(fileIdx: Int, totalFiles: Int, fileName: String)
    }

    data class ModelDef(
        val id: String,
        val name: String,
        val encoderUrl: String,
        val decoderUrl: String,
        val tokensUrl: String,
        val sizeMb: Int,
    )

    companion object {
        private const val TAG = "WhisperBridge"

        // ── Whisper models ─────────────────────────────────────────────────
        val MODELS = listOf(
            ModelDef(
                "tiny", "Tiny · 86MB · 最快",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                86,
            ),
            ModelDef(
                "small", "Small · 488MB · 推荐",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                488,
            ),
            ModelDef(
                "large-v3-turbo", "Large-v3-turbo · 809MB · 最准",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt",
                809,
            ),
        )

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

    private fun diarizationDir(): File =
        File(File(context.getExternalFilesDir("QuickNote"), "models"), "diarization")

    // ── Whisper model management ───────────────────────────────────────────

    fun isModelDownloaded(modelId: String): Boolean {
        val dir = modelDir(modelId)
        return File(dir, "encoder.int8.onnx").exists() &&
               File(dir, "decoder.int8.onnx").exists() &&
               File(dir, "tokens.txt").exists()
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
    fun downloadModel(modelId: String, progress: ProgressCallback): String? {
        val model = MODELS.find { it.id == modelId } ?: return "unknown model: $modelId"
        val dir = modelDir(modelId)
        dir.mkdirs()

        val files = listOf(
            "encoder.int8.onnx" to model.encoderUrl,
            "decoder.int8.onnx" to model.decoderUrl,
            "tokens.txt"        to model.tokensUrl,
        )

        return try {
            files.forEachIndexed { idx, (name, url) ->
                progress.onProgress(idx + 1, files.size, name)
                downloadFile(url, File(dir, name))
            }
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

    // ── Diarization model management ───────────────────────────────────────

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
    fun downloadDiarizationModel(progress: ProgressCallback): String? {
        val dir = diarizationDir()
        dir.mkdirs()
        val files = listOf(
            "segmentation.onnx" to DIARIZE_SEG_URL,
            "embedding.onnx"    to DIARIZE_EMB_URL,
        )
        return try {
            files.forEachIndexed { idx, (name, url) ->
                progress.onProgress(idx + 1, files.size, name)
                downloadFile(url, File(dir, name))
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

    // ── Transcription ──────────────────────────────────────────────────────

    /** Plain transcription without speaker labels. Blocking. */
    fun transcribeAudio(audioFilename: String, modelId: String, language: String): String {
        if (!isModelDownloaded(modelId)) return "error: model not downloaded"
        val audioFile = File(context.getExternalFilesDir("QuickNote"), audioFilename)
        if (!audioFile.exists()) return "error: audio file not found ($audioFilename)"
        return try {
            Log.i(TAG, "Decoding M4A: ${audioFile.length()} bytes")
            val samples = decodeAudioToFloat(audioFile.absolutePath)
                ?: return "error: audio decode failed"
            Log.i(TAG, "Decoded ${samples.size} samples — running Whisper")
            runWhisper(samples, modelDir(modelId), language)
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

        val whisperDir = modelDir(modelId)
        val diarDir    = diarizationDir()

        return try {
            // 1. Decode full audio to 16 kHz mono float[]
            Log.i(TAG, "Diarization: decoding audio")
            val samples = decodeAudioToFloat(audioFile.absolutePath)
                ?: return "error: audio decode failed"
            Log.i(TAG, "Diarization: ${samples.size} samples decoded")

            // 2. Run speaker diarization
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
                Log.w(TAG, "Zero diarization segments — falling back to plain transcription")
                return runWhisper(samples, whisperDir, language)
            }

            // 3. Transcribe each speaker segment with Whisper
            val recognizer   = buildRecognizer(whisperDir, language)
            val speakerChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val sb           = StringBuilder()

            segments.forEachIndexed { i, seg ->
                val startSample = (seg.start * 16_000).toInt().coerceIn(0, samples.size)
                val endSample   = (seg.end   * 16_000).toInt().coerceIn(0, samples.size)
                if (endSample - startSample < 3_200) return@forEachIndexed // skip < 0.2 s

                val segSamples = samples.copyOfRange(startSample, endSample)
                val stream     = recognizer.createStream()
                stream.acceptWaveform(segSamples, sampleRate = 16_000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()

                if (text.isNotEmpty()) {
                    val label    = if (seg.speaker < speakerChars.length)
                        speakerChars[seg.speaker].toString() else "${seg.speaker + 1}"
                    val startStr = fmtSecs(seg.start)
                    val endStr   = fmtSecs(seg.end)
                    sb.append("[说话人$label $startStr-$endStr] $text\n")
                }
                Log.i(TAG, "Segment ${i+1}/${segments.size} transcribed")
            }
            recognizer.release()

            val result = sb.toString().trim()
            if (result.isEmpty()) runWhisper(samples, whisperDir, language) else result
        } catch (e: Exception) {
            Log.e(TAG, "Diarization transcription error", e)
            "error: ${e.message}"
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun runWhisper(samples: FloatArray, dir: File, language: String): String {
        val recognizer = buildRecognizer(dir, language)
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate = 16_000)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        recognizer.release()
        Log.i(TAG, "Whisper complete: ${text.length} chars")
        return text
    }

    private fun buildRecognizer(dir: File, language: String): OfflineRecognizer {
        val whisperConfig = OfflineWhisperModelConfig(
            encoder      = File(dir, "encoder.int8.onnx").absolutePath,
            decoder      = File(dir, "decoder.int8.onnx").absolutePath,
            language     = language.ifEmpty { "" },
            task         = "transcribe",
            tailPaddings = -1,
        )
        val modelConfig = OfflineModelConfig(
            whisper    = whisperConfig,
            tokens     = File(dir, "tokens.txt").absolutePath,
            numThreads = 4,
        )
        return OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig))
    }

    private fun fmtSecs(secs: Float): String {
        val s = secs.toInt()
        return "${s / 60}:${String.format("%02d", s % 60)}"
    }

    private fun downloadFile(urlStr: String, dest: File) {
        if (dest.exists() && dest.length() > 100) return
        Log.i(TAG, "Downloading: $urlStr")

        // Manually follow redirects (HttpURLConnection does NOT follow cross-host redirects)
        var currentUrl = urlStr
        var conn: HttpURLConnection? = null
        for (hop in 0 until 10) {
            conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false   // we handle redirects ourselves
            conn.connectTimeout = 30_000
            conn.readTimeout    = 300_000
            conn.setRequestProperty("User-Agent", "QuickNote/1.0 Android")
            val code = conn.responseCode
            Log.i(TAG, "  hop $hop → $code  url=${currentUrl.take(80)}")
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw Exception("Redirect without Location header")
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
            } else if (code == 200) {
                break
            } else {
                conn.disconnect()
                throw Exception("HTTP $code for ${dest.name}")
            }
        }

        if (conn == null || conn.responseCode != 200) {
            throw Exception("Failed to connect after redirects for ${dest.name}")
        }

        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(65_536)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
            Log.i(TAG, "  saved ${tmp.length()} bytes → ${dest.name}")
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) throw Exception("Failed to rename tmp → ${dest.name}")
    }

    // ── Audio decoding: M4A/AAC → 16 kHz mono float[] ─────────────────────
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
