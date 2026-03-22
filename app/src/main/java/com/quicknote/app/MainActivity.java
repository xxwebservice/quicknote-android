package com.quicknote.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import android.content.ContentValues;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private WhisperBridge whisperBridge;
    private static final int MIC_PERMISSION_REQUEST = 1001;
    private static final int FILE_CHOOSER_REQUEST = 1002;
    private static final int CAMERA_CAPTURE_REQUEST = 1003;
    private static final int EDIT_IMAGE_REQUEST = 1004;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String cameraCallbackFn;
    private String editingImageFilename;
    private String editingImageCallbackFn;
    private long editingFileLastModified; // detect in-place edits
    private Uri cameraPhotoUri;

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
            startNativeRecordingWithQuality(filename, "standard");
        }

        @JavascriptInterface
        public void startNativeRecordingWithQuality(String filename, String quality) {
            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            intent.setAction(RecordingService.ACTION_START);
            intent.putExtra(RecordingService.EXTRA_FILE, filename);
            intent.putExtra(RecordingService.EXTRA_QUALITY, quality);
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

        // ── Image viewer / editor ─────────────────────────────────────

        @JavascriptInterface
        public void openImageViewer(String filename) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                File file = new File(dir, filename);
                if (!file.exists()) return;
                Uri contentUri = FileProvider.getUriForFile(
                    MainActivity.this, "com.quicknote.app.fileprovider", file);
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(contentUri, "image/jpeg");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(viewIntent, "查看照片")));
            } catch (Exception e) { e.printStackTrace(); }
        }

        @JavascriptInterface
        public void openImageEditor(String filename, String callbackFn) {
            try {
                File dir = getExternalFilesDir("QuickNote");
                File file = new File(dir, filename);
                if (!file.exists()) return;
                editingImageFilename = filename;
                editingImageCallbackFn = callbackFn != null ? callbackFn : "";
                editingFileLastModified = file.lastModified(); // track for in-place edit detection
                Uri contentUri = FileProvider.getUriForFile(
                    MainActivity.this, "com.quicknote.app.fileprovider", file);
                Intent editIntent = new Intent(Intent.ACTION_EDIT);
                editIntent.setDataAndType(contentUri, "image/jpeg");
                editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        startActivityForResult(Intent.createChooser(editIntent, "编辑照片"), EDIT_IMAGE_REQUEST);
                    } catch (Exception e) {
                        // No editor — fall back to viewer
                        Intent fallback = new Intent(Intent.ACTION_VIEW);
                        fallback.setDataAndType(contentUri, "image/jpeg");
                        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(fallback);
                    }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }

        /** Overload for backward compat (no callback) */
        @JavascriptInterface
        public void openImageEditor(String filename) {
            openImageEditor(filename, "");
        }

        // ── Camera capture ──────────────────────────────────────────────

        @JavascriptInterface
        public void capturePhoto(String callbackFn) {
            cameraCallbackFn = callbackFn;
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // Create temp file for full-res photo
            File dir = getExternalFilesDir("QuickNote");
            if (dir != null && !dir.exists()) dir.mkdirs();
            File photoFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            cameraPhotoUri = FileProvider.getUriForFile(
                MainActivity.this, "com.quicknote.app.fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            // Hints for full-quality capture (disable quick-snap, enable stabilization)
            takePictureIntent.putExtra("android.intent.extra.quickCapture", false);
            takePictureIntent.putExtra("android.intent.extras.CAMERA_FACING", 0); // rear camera
            takePictureIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1); // hint: high quality
            runOnUiThread(() -> {
                try {
                    startActivityForResult(takePictureIntent, CAMERA_CAPTURE_REQUEST);
                } catch (Exception e) {
                    e.printStackTrace();
                    // Camera not available — notify JS
                    webView.evaluateJavascript(
                        "typeof window['" + callbackFn + "']==='function'&&window['" + callbackFn + "'](null)", null);
                }
            });
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
                    long MAX_PART = 95L * 1024 * 1024; // 95MB per part

                    if (size > MAX_PART) {
                        // Split into parts
                        int partCount = (int) Math.ceil((double) size / MAX_PART);
                        FileInputStream splitIn = new FileInputStream(zipFile);
                        byte[] buf2 = new byte[65536];
                        for (int part = 1; part <= partCount; part++) {
                            String partName = zipFilename.replace(".zip", "_part" + part + ".zip");
                            File partFile = new File(dir, partName);
                            FileOutputStream partOut = new FileOutputStream(partFile);
                            long written = 0;
                            int len2;
                            while (written < MAX_PART && (len2 = splitIn.read(buf2)) > 0) {
                                partOut.write(buf2, 0, len2);
                                written += len2;
                            }
                            partOut.close();
                        }
                        splitIn.close();
                        // Callback with negative size = number of parts
                        final int pc = partCount;
                        runOnUiThread(() -> webView.evaluateJavascript(
                            "window['" + callbackFn + "']({size:" + size + ",parts:" + pc + "})", null));
                    } else {
                        runOnUiThread(() -> webView.evaluateJavascript(
                            "window['" + callbackFn + "'](" + size + ")", null));
                    }
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

        // ── Backup & Restore ────────────────────────────────────────────

        /** Get public backup directory (survives app uninstall) */
        private File getBackupDir() {
            // Save to Documents/QuickNote/ (public, survives uninstall)
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "QuickNote");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }

        /** Save backup with timestamp filename to Documents/QuickNote/backups/ */
        @JavascriptInterface
        public String saveBackup(String jsonData) {
            try {
                File backupDir = new File(getBackupDir(), "backups");
                if (!backupDir.exists()) backupDir.mkdirs();
                // Timestamp filename: quicknote_backup_20260322_143000.json
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
                String filename = "quicknote_backup_" + ts + ".json";
                File backup = new File(backupDir, filename);
                FileOutputStream fos = new FileOutputStream(backup);
                fos.write(jsonData.getBytes("UTF-8"));
                fos.close();
                final String path = backup.getAbsolutePath();
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "备份已保存: " + path, Toast.LENGTH_LONG).show());
                return path; // return full path for confirmation
            } catch (Exception e) {
                e.printStackTrace();
                return "";
            }
        }

        /** List all backup files with name and size, sorted newest first */
        @JavascriptInterface
        public String listBackups() {
            try {
                JSONArray arr = new JSONArray();
                // Scan Documents/QuickNote/backups/
                File backupDir = new File(getBackupDir(), "backups");
                if (backupDir.exists()) {
                    File[] files = backupDir.listFiles((d, name) -> name.endsWith(".json"));
                    if (files != null) {
                        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (File f : files) {
                            JSONObject obj = new JSONObject();
                            obj.put("name", f.getName());
                            obj.put("path", f.getAbsolutePath());
                            obj.put("size", f.length());
                            obj.put("modified", f.lastModified());
                            arr.put(obj);
                        }
                    }
                }
                // Also check legacy locations
                File legacy1 = new File(getBackupDir(), "quicknote_backup.json");
                if (legacy1.exists()) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", "quicknote_backup.json (旧版)");
                    obj.put("path", legacy1.getAbsolutePath());
                    obj.put("size", legacy1.length());
                    obj.put("modified", legacy1.lastModified());
                    arr.put(obj);
                }
                File legacy2 = new File(getExternalFilesDir("QuickNote"), "quicknote_backup.json");
                if (legacy2.exists()) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", "quicknote_backup.json (应用内)");
                    obj.put("path", legacy2.getAbsolutePath());
                    obj.put("size", legacy2.length());
                    obj.put("modified", legacy2.lastModified());
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) { e.printStackTrace(); return "[]"; }
        }

        /** Load a specific backup file by path */
        @JavascriptInterface
        public String loadBackup(String filePath) {
            try {
                File backup = new File(filePath);
                if (!backup.exists()) return "";
                FileInputStream fis = new FileInputStream(backup);
                byte[] data = new byte[(int) backup.length()];
                fis.read(data);
                fis.close();
                return new String(data, "UTF-8");
            } catch (Exception e) { e.printStackTrace(); return ""; }
        }

        /** Legacy: load most recent backup (backward compat) */
        @JavascriptInterface
        public String loadLatestBackup() {
            try {
                String list = listBackups();
                JSONArray arr = new JSONArray(list);
                if (arr.length() == 0) return "";
                String path = arr.getJSONObject(0).getString("path");
                return loadBackup(path);
            } catch (Exception e) { return ""; }
        }

        // ── Debug Log ───────────────────────────────────────────────────

        @JavascriptInterface
        public void debugLog(String tag, String message) {
            android.util.Log.i("QN_" + tag, message);
        }

        @JavascriptInterface
        public String getDebugInfo() {
            try {
                JSONObject info = new JSONObject();
                info.put("versionName", "3.2");
                info.put("versionCode", 32);
                info.put("sdk", Build.VERSION.SDK_INT);
                info.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                info.put("abi", Build.SUPPORTED_ABIS[0]);
                File dir = getExternalFilesDir("QuickNote");
                if (dir != null) {
                    info.put("storagePath", dir.getAbsolutePath());
                    info.put("storageFree", dir.getFreeSpace() / 1024 / 1024 + "MB");
                }
                return info.toString();
            } catch (Exception e) { return "{}"; }
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
        } else if (requestCode == CAMERA_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && cameraPhotoUri != null && cameraCallbackFn != null) {
                final String cbFn = cameraCallbackFn;
                cameraCallbackFn = null;
                // Process on background thread to avoid blocking UI
                new Thread(() -> {
                    try {
                        // Read the full-res captured photo
                        InputStream is = getContentResolver().openInputStream(cameraPhotoUri);
                        if (is == null) throw new IOException("Cannot open photo URI");
                        Bitmap original = BitmapFactory.decodeStream(is);
                        is.close();
                        if (original == null) throw new IOException("Failed to decode photo");

                        // Save FULL resolution to QuickNote dir (JPEG 95% — no blur)
                        String filename = "photo_" + System.currentTimeMillis() + ".jpg";
                        File dir = getExternalFilesDir("QuickNote");
                        File outFile = new File(dir, filename);
                        FileOutputStream fos = new FileOutputStream(outFile);
                        original.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                        fos.close();

                        // Save to system gallery (Pictures/QuickNote)
                        saveToSystemGallery(outFile, filename);

                        // Make a THUMBNAIL for JS display (max 800px, lower quality for fast transfer)
                        int maxThumb = 800;
                        int tw = original.getWidth(), th = original.getHeight();
                        if (tw > maxThumb || th > maxThumb) {
                            if (tw > th) { th = th * maxThumb / tw; tw = maxThumb; }
                            else { tw = tw * maxThumb / th; th = maxThumb; }
                        }
                        Bitmap thumb = Bitmap.createScaledBitmap(original, tw, th, true);
                        original.recycle();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        thumb.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                        thumb.recycle();
                        String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                        String dataUrl = "data:image/jpeg;base64," + b64;

                        // Callback to JS
                        JSONObject result = new JSONObject();
                        result.put("filename", filename);
                        result.put("dataUrl", dataUrl);
                        String js = result.toString();
                        runOnUiThread(() -> webView.evaluateJavascript(
                            "typeof window['" + cbFn + "']==='function'&&window['" + cbFn + "'](" + js + ")", null));
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> webView.evaluateJavascript(
                            "typeof window['" + cbFn + "']==='function'&&window['" + cbFn + "'](null)", null));
                    }
                }).start();
            } else {
                // User cancelled or error
                if (cameraCallbackFn != null) {
                    final String cbFn = cameraCallbackFn;
                    cameraCallbackFn = null;
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "typeof window['" + cbFn + "']==='function'&&window['" + cbFn + "'](null)", null));
                }
            }
        } else if (requestCode == EDIT_IMAGE_REQUEST) {
            // Image editor returned — check if the edited image needs to be saved back
            final String filename = editingImageFilename;
            final String cbFn = editingImageCallbackFn;
            editingImageFilename = null;
            editingImageCallbackFn = null;

            if (filename != null) {
                final long prevModified = editingFileLastModified;
                new Thread(() -> {
                    try {
                        File dir = getExternalFilesDir("QuickNote");
                        File originalFile = new File(dir, filename);
                        boolean updated = false;

                        // Case 1: Editor returned a new URI in result data
                        if (data != null && data.getData() != null) {
                            try {
                                Uri editedUri = data.getData();
                                InputStream is = getContentResolver().openInputStream(editedUri);
                                if (is != null) {
                                    FileOutputStream fos = new FileOutputStream(originalFile);
                                    byte[] buf = new byte[65536];
                                    int len;
                                    while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                                    is.close();
                                    fos.close();
                                    updated = true;
                                }
                            } catch (Exception ignored) {}
                        }

                        // Case 2: Editor wrote back in-place — detect by timestamp or size change
                        if (!updated && originalFile.exists() && originalFile.lastModified() != prevModified) {
                            updated = true;
                        }

                        if (updated) {
                            saveToSystemGallery(originalFile, filename);
                        }

                        // Always send callback to JS to refresh thumbnail (re-read is always safe)
                        if (cbFn != null && !cbFn.isEmpty()) {
                            // Build new thumbnail
                            Bitmap bmp = BitmapFactory.decodeFile(originalFile.getAbsolutePath());
                            if (bmp != null) {
                                int maxThumb = 800;
                                int tw = bmp.getWidth(), th = bmp.getHeight();
                                if (tw > maxThumb || th > maxThumb) {
                                    if (tw > th) { th = th * maxThumb / tw; tw = maxThumb; }
                                    else { tw = tw * maxThumb / th; th = maxThumb; }
                                }
                                Bitmap thumb = Bitmap.createScaledBitmap(bmp, tw, th, true);
                                bmp.recycle();
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                thumb.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                                thumb.recycle();
                                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                                String dataUrl = "data:image/jpeg;base64," + b64;
                                JSONObject result = new JSONObject();
                                result.put("filename", filename);
                                result.put("dataUrl", dataUrl);
                                String js = result.toString();
                                runOnUiThread(() -> webView.evaluateJavascript(
                                    "typeof window['" + cbFn + "']==='function'&&window['" + cbFn + "'](" + js + ")", null));
                            }
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }).start();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void saveToSystemGallery(File imageFile, String displayName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuickNote");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    FileInputStream fis = new FileInputStream(imageFile);
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = fis.read(buf)) > 0) os.write(buf, 0, len);
                    fis.close();
                    os.close();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void onBackPressed() {
        // Let JS handle navigation (screen stack) instead of WebView history
        webView.evaluateJavascript(
            "(function(){" +
            "  if(typeof window.__qnHandleBack==='function'){" +
            "    window.__qnHandleBack();" +
            "  } else { return 'exit'; }" +
            "})()",
            result -> {
                if ("\"exit\"".equals(result)) {
                    // JS says no more screens to go back to — minimize app
                    moveTaskToBack(true);
                }
            }
        );
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
