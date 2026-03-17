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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int MIC_PERMISSION_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide status bar for cleaner look
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }

        // Keep screen on during use
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Request mic permission upfront
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO
                }, MIC_PERMISSION_REQUEST);
            }
        }

        // Setup WebView
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // JS bridge for native file saving
        webView.addJavascriptInterface(new NativeBridge(), "NativeBridge");

        // Handle mic permission in WebView
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.setWebViewClient(new WebViewClient());

        // Load app from assets
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // Native bridge for file operations
    private class NativeBridge {
        @JavascriptInterface
        public String getStoragePath() {
            File dir = getExternalFilesDir("QuickNote");
            if (dir != null && !dir.exists()) dir.mkdirs();
            return dir != null ? dir.getAbsolutePath() : "";
        }

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

        // ── Native Recording (Foreground Service) ──────────────────────────

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

        // ── ZIP builder (streams audio from disk — no base64 overhead) ────

        @JavascriptInterface
        public void buildZipAndSave(String notesMd, String claudeJson,
                                     String audioFilename, String zipFilename,
                                     String callbackFn) {
            new Thread(() -> {
                try {
                    File dir = getExternalFilesDir("QuickNote");
                    if (dir != null) dir.mkdirs();
                    File zipFile = new File(dir, zipFilename);

                    // Derive prefix (strip "_quicknote.zip")
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

        // ── Share file ─────────────────────────────────────────────────────

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
