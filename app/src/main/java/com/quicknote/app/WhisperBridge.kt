package com.quicknote.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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

        val MODELS = listOf(
            ModelDef(
                "tiny", "Tiny · 86MB · 最快",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tokens.txt",
                86,
            ),
            ModelDef(
                "small", "Small · 488MB · 推荐",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/tokens.txt",
                488,
            ),
            ModelDef(
                "large-v3-turbo", "Large-v3-turbo · 809MB · 最准",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3-turbo/resolve/main/encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3-turbo/resolve/main/decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3-turbo/resolve/main/tokens.txt",
                809,
            ),
        )
    }

    private fun modelDir(modelId: String): File =
        File(File(context.getExternalFilesDir("QuickNote"), "models"), modelId)

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

    /** Returns null on success, error message on failure. Blocking — run on background thread. */
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

    private fun downloadFile(urlStr: String, dest: File) {
        if (dest.exists() && dest.length() > 100) return

        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 180_000
        conn.instanceFollowRedirects = true

        // HuggingFace chains multiple redirects
        repeat(8) {
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: return@repeat
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout    = 180_000
            }
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
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) throw Exception("Failed to save ${dest.name}")
    }

    /** Blocking transcription — run on background thread. Returns text or "error: ..." */
    fun transcribeAudio(audioFilename: String, modelId: String, language: String): String {
        if (!isModelDownloaded(modelId)) return "error: model not downloaded"

        val audioFile = File(context.getExternalFilesDir("QuickNote"), audioFilename)
        if (!audioFile.exists()) return "error: audio file not found ($audioFilename)"

        val dir = modelDir(modelId)
        val encoderPath = File(dir, "encoder.int8.onnx").absolutePath
        val decoderPath = File(dir, "decoder.int8.onnx").absolutePath
        val tokensPath  = File(dir, "tokens.txt").absolutePath

        return try {
            Log.i(TAG, "Decoding M4A: ${audioFile.length()} bytes")
            val samples = decodeAudioToFloat(audioFile.absolutePath)
                ?: return "error: audio decode failed"
            Log.i(TAG, "Decoded ${samples.size} samples at 16 kHz — running Whisper")

            val whisperConfig = OfflineWhisperModelConfig(
                encoder      = encoderPath,
                decoder      = decoderPath,
                language     = language.ifEmpty { "" }, // "" = auto-detect
                task         = "transcribe",
                tailPaddings = -1,
            )
            val modelConfig = OfflineModelConfig(
                whisper    = whisperConfig,
                tokens     = tokensPath,
                numThreads = 4,
            )
            val config = OfflineRecognizerConfig(modelConfig = modelConfig)

            val recognizer = OfflineRecognizer(config = config)
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, sampleRate = 16_000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            recognizer.release()

            Log.i(TAG, "Transcription complete: ${text.length} chars")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            "error: ${e.message}"
        }
    }

    fun deleteModel(modelId: String) {
        val dir = modelDir(modelId)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
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

        // Downmix to mono float
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
