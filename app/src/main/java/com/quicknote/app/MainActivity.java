package com.quicknote.app;

import android.Manifest;
import android.app.Activity;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
