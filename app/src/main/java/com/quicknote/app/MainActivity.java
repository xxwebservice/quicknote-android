package com.quicknote.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private WhisperBridge whisperBridge;
    private static final int MIC_PERMISSION_REQUEST = 1001;
    private static final int FILE_CHOOSER_REQUEST = 1002;
    private ValueCallback<Uri[]> fileUploadCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, MIC_PERMISSION_REQUEST);
            }
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        whisperBridge = new WhisperBridge(this);

        webView.addJavascriptInterface(new NativeBridge(), "NativeBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                fileUploadCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    /** Helper: send a byte-progress JSON event to JS via the given callback function name. */
    private void sendByteProgress(String callbackFn, long downloaded, long total) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "bytes");
            json.put("downloaded", downloaded);
            json.put("total", total);
            String js = json.toString();
            runOnUiThread(() -> webView.evaluateJavascript(
                "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](" + js + ")", null));
        } catch (Exception ignored) {}
    }

    private class NativeBridge {

        // ── Storage path ──────────────────────────────────────────────────

        @JavascriptInterface
        public String getStoragePath() {
            File dir = getExternalFilesDir("QuickNote");
            if (dir != null && !dir.exists()) dir.mkdirs();
            return dir != null ? dir.getAbsolutePath() : "";
        }

        // ── File helpers ──────────────────────────────────────────────────

        @JavascriptInterface
        public boolean saveFile(String filename, String base64Data) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                if (dir != null && !dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "已保存: " + filename, Toast.LENGTH_SHORT).show()
                );
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        @JavascriptInterface
        public boolean saveText(String filename, String text) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                if (dir != null && !dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(text.getBytes("UTF-8"));
                fos.close();
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "已保存: " + filename, Toast.LENGTH_SHORT).show()
                );
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        // ── Read file as base64 (for image export) ─────────────────────────

        @JavascriptInterface
        public String readFileBase64(String filename) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                File file = new File(dir, filename);
                if (!file.exists()) return "";
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();
                return Base64.encodeToString(data, Base64.NO_WRAP);
            } catch (Exception e) {
                e.printStackTrace();
                return "";
            }
        }

        @JavascriptInterface
        public boolean fileExists(String filename) {
            File dir = getExternalFilesDir("QuickNote");
            return new File(dir, filename).exists();
        }

        // ── Native Recording (Foreground Service) ─────────────────────────

        @JavascriptInterface
        public void startNativeRecording(String filename) {
            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            intent.setAction(RecordingService.ACTION_START);
            intent.putExtra(RecordingService.EXTRA_FILE, filename);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }

        @JavascriptInterface
        public void stopNativeRecording() {
            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            intent.setAction(RecordingService.ACTION_STOP);
            startService(intent);
        }

        // ── ZIP builder ───────────────────────────────────────────────────
        // transcriptText: pass session.transcription or '' — included if non-empty

        @JavascriptInterface
        public void buildZipAndSave(String notesMd, String claudeJson,
                                     String audioFilename, String transcriptText,
                                     String zipFilename, String callbackFn) {
            buildZipAndSaveWithImages(notesMd, claudeJson, audioFilename, transcriptText, "", zipFilename, callbackFn);
        }

        @JavascriptInterface
        public void buildZipAndSaveWithImages(String notesMd, String claudeJson,
                                     String audioFilename, String transcriptText,
                                     String imageFilenames, String zipFilename, String callbackFn) {
            new Thread(() -> {
                try {
                    File dir = getExternalFilesDir("QuickNote");
                    if (dir != null) dir.mkdirs();
                    File zipFile = new File(dir, zipFilename);

                    String prefix = zipFilename.endsWith("_quicknote.zip")
                            ? zipFilename.substring(0, zipFilename.length() - "_quicknote.zip".length())
                            : zipFilename.replace(".zip", "");

                    ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));

                    zos.putNextEntry(new ZipEntry(prefix + "_notes.md"));
                    zos.write(notesMd.getBytes("UTF-8"));
                    zos.closeEntry();

                    zos.putNextEntry(new ZipEntry(prefix + "_for_claude.json"));
                    zos.write(claudeJson.getBytes("UTF-8"));
                    zos.closeEntry();

                    // Include local transcript when available (Case A)
                    if (transcriptText != null && !transcriptText.isEmpty()) {
                        zos.putNextEntry(new ZipEntry(prefix + "_transcript.txt"));
                        zos.write(transcriptText.getBytes("UTF-8"));
                        zos.closeEntry();
                    }

                    if (audioFilename != null && !audioFilename.isEmpty()) {
                        File audioFile = new File(dir, audioFilename);
                        if (audioFile.exists()) {
                            String ext = audioFilename.endsWith(".m4a") ? "m4a" : "webm";
                            zos.putNextEntry(new ZipEntry(prefix + "_recording." + ext));
                            FileInputStream fis = new FileInputStream(audioFile);
                            byte[] buf = new byte[65536];
                            int len;
                            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                            fis.close();
                            zos.closeEntry();
                        }
                    }

                    // Include images
                    if (imageFilenames != null && !imageFilenames.isEmpty()) {
                        String[] imgs = imageFilenames.split(",");
                        for (String imgName : imgs) {
                            imgName = imgName.trim();
                            if (imgName.isEmpty()) continue;
                            File imgFile = new File(dir, imgName);
                            if (imgFile.exists()) {
                                zos.putNextEntry(new ZipEntry("images/" + imgName));
                                FileInputStream fis = new FileInputStream(imgFile);
                                byte[] buf = new byte[65536];
                                int len;
                                while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                                fis.close();
                                zos.closeEntry();
                            }
                        }
                    }

                    zos.close();
                    long size = zipFile.length();
                    runOnUiThread(() -> webView.evaluateJavascript(
                            "window['" + callbackFn + "'](" + size + ")", null));
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> webView.evaluateJavascript(
                            "window['" + callbackFn + "'](-1)", null));
                }
            }).start();
        }

        // ── Share file ────────────────────────────────────────────────────

        @JavascriptInterface
        public boolean shareFile(String filename) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                File file = new File(dir, filename);
                if (!file.exists()) {
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "文件不存在: " + filename, Toast.LENGTH_SHORT).show()
                    );
                    return false;
                }
                Uri contentUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    "com.quicknote.app.fileprovider",
                    file
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/zip");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "分享文件");
                runOnUiThread(() -> startActivity(chooser));
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        // ── Whisper model management ──────────────────────────────────────

        @JavascriptInterface
        public String getWhisperModels() {
            return whisperBridge.getModelsJson();
        }

        @JavascriptInterface
        public boolean isWhisperModelDownloaded(String modelId) {
            return whisperBridge.isModelDownloaded(modelId);
        }

        @JavascriptInterface
        public void downloadWhisperModel(String modelId, String callbackFn) {
            new Thread(() -> {
                String error = whisperBridge.downloadModel(modelId,
                    (fileIdx, totalFiles, fileName) -> {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "progress");
                            json.put("file", fileIdx);
                            json.put("total", totalFiles);
                            json.put("name", fileName);
                            String js = json.toString();
                            runOnUiThread(() -> webView.evaluateJavascript(
                                "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](" + js + ")", null));
                        } catch (Exception ignored) {}
                    },
                    (downloaded, total) -> sendByteProgress(callbackFn, downloaded, total)
                );
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "done");
                    json.put("result", error == null ? "ok" : error);
                    String js = json.toString();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](" + js + ")", null));
                } catch (Exception ignored) {}
            }).start();
        }

        @JavascriptInterface
        public void deleteWhisperModel(String modelId) {
            whisperBridge.deleteModel(modelId);
        }

        // ── Transcription (Foreground Service) ────────────────────────────

        // ── Diarization model management ──────────────────────────────────

        @JavascriptInterface
        public String getDiarizationModelStatus() {
            return whisperBridge.getDiarizationModelJson();
        }

        @JavascriptInterface
        public void downloadDiarizationModel(String callbackFn) {
            new Thread(() -> {
                String error = whisperBridge.downloadDiarizationModel(
                    (fileIdx, totalFiles, fileName) -> {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "progress");
                            json.put("file", fileIdx);
                            json.put("total", totalFiles);
                            json.put("name", fileName);
                            String js = json.toString();
                            runOnUiThread(() -> webView.evaluateJavascript(
                                "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](" + js + ")", null));
                        } catch (Exception ignored) {}
                    },
                    (downloaded, total) -> sendByteProgress(callbackFn, downloaded, total)
                );
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "done");
                    json.put("result", error == null ? "ok" : error);
                    String js = json.toString();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](" + js + ")", null));
                } catch (Exception ignored) {}
            }).start();
        }

        @JavascriptInterface
        public void deleteDiarizationModel() {
            whisperBridge.deleteDiarizationModel();
        }

        // ── Share text/markdown file ───────────────────────────────────────

        @JavascriptInterface
        public boolean shareTextFile(String filename) {
            try {
                File dir  = getExternalFilesDir("QuickNote");
                File file = new File(dir, filename);
                if (!file.exists()) {
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "文件不存在: " + filename, Toast.LENGTH_SHORT).show()
                    );
                    return false;
                }
                Uri contentUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    "com.quicknote.app.fileprovider",
                    file
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, filename.replace("_", " ").replace(".md", ""));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "分享文档");
                runOnUiThread(() -> startActivity(chooser));
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        // ── Transcription (Foreground Service) ────────────────────────────

        @JavascriptInterface
        public void startTranscription(String audioFilename, String modelId,
                                        String language, String resultKey,
                                        int durationSecs, boolean diarize) {
            Intent intent = new Intent(MainActivity.this, TranscriptionService.class);
            intent.setAction(TranscriptionService.ACTION_START);
            intent.putExtra(TranscriptionService.EXTRA_AUDIO, audioFilename);
            intent.putExtra(TranscriptionService.EXTRA_MODEL, modelId);
            intent.putExtra(TranscriptionService.EXTRA_LANG, language);
            intent.putExtra(TranscriptionService.EXTRA_KEY, resultKey);
            intent.putExtra(TranscriptionService.EXTRA_DURATION_SECS, durationSecs);
            intent.putExtra(TranscriptionService.EXTRA_DIARIZE, diarize);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }

        /** Backwards-compatible overload without diarize flag */
        @JavascriptInterface
        public void startTranscriptionSimple(String audioFilename, String modelId,
                                              String language, String resultKey,
                                              int durationSecs) {
            startTranscription(audioFilename, modelId, language, resultKey, durationSecs, false);
        }

        @JavascriptInterface
        public void stopTranscription() {
            Intent intent = new Intent(MainActivity.this, TranscriptionService.class);
            intent.setAction(TranscriptionService.ACTION_STOP);
            startService(intent);
        }

        /** Returns "" if not ready yet, transcript text when done, "error: ..." on failure */
        @JavascriptInterface
        public String checkTranscriptionResult(String resultKey) {
            try {
                File dir = new File(getExternalFilesDir("QuickNote"), "transcripts");
                File f   = new File(dir, resultKey + ".txt");
                if (!f.exists()) return "";
                FileInputStream fis = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                fis.read(data);
                fis.close();
                return new String(data, "UTF-8");
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void clearTranscriptionResult(String resultKey) {
            try {
                File dir = new File(getExternalFilesDir("QuickNote"), "transcripts");
                new File(dir, resultKey + ".txt").delete();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) results = new Uri[]{ Uri.parse(dataString) };
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                webView.reload();
            }
        }
    }
}
