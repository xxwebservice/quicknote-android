package com.quicknote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Foreground Service for local Whisper transcription (with optional speaker diarization).
 * Survives screen lock / app switching. Result written to
 * getExternalFilesDir("QuickNote")/transcripts/{resultKey}.txt
 * so JS can poll with NativeBridge.checkTranscriptionResult().
 */
public class TranscriptionService extends Service {

    static final String ACTION_START        = "com.quicknote.app.START_TRANSCRIPTION";
    static final String ACTION_STOP         = "com.quicknote.app.STOP_TRANSCRIPTION";
    static final String EXTRA_AUDIO         = "audioFilename";
    static final String EXTRA_MODEL         = "modelId";
    static final String EXTRA_LANG          = "language";
    static final String EXTRA_KEY           = "resultKey";
    static final String EXTRA_DURATION_SECS = "durationSecs";
    static final String EXTRA_DIARIZE       = "diarize";     // boolean

    private static final String CHANNEL_ID = "qn_transcription";
    private static final int    NOTIF_ID   = 102;
    private static final String TAG        = "TranscriptionService";

    private Thread              transThread;
    private volatile boolean    stopped = false;
    private final Handler       handler = new Handler(Looper.getMainLooper());
    private Runnable            notifUpdater;
    private long                startTime;
    private int                 estimatedSecs = 120;
    private String              pendingResultKey;
    private boolean             diarizeMode   = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopped = true;
            if (pendingResultKey != null) writeResult(pendingResultKey, "error: cancelled");
            stopAll();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        String audioFilename = intent.getStringExtra(EXTRA_AUDIO);
        String modelId       = intent.getStringExtra(EXTRA_MODEL);
        String language      = intent.getStringExtra(EXTRA_LANG);
        String resultKey     = intent.getStringExtra(EXTRA_KEY);
        int    durationSecs  = intent.getIntExtra(EXTRA_DURATION_SECS, 0);
        diarizeMode          = intent.getBooleanExtra(EXTRA_DIARIZE, false);
        pendingResultKey     = resultKey;

        // Estimate wall-clock time
        // With diarization: ~2× longer (diarization pass + per-segment Whisper)
        if (durationSecs > 0) {
            int factor = "large-v3-turbo".equals(modelId) ? 5 : "small".equals(modelId) ? 10 : 20;
            estimatedSecs = Math.max(10, durationSecs / factor);
            if (diarizeMode) estimatedSecs = (int)(estimatedSecs * 2.5);
        }

        createChannel();
        startTime = System.currentTimeMillis();
        String initMsg = diarizeMode ? "正在分析说话人..." : "正在初始化模型...";
        startForeground(NOTIF_ID, buildNotif(diarizeMode ? "转录+说话人识别" : "转录中", initMsg));
        startNotifUpdates();

        final String af = audioFilename != null ? audioFilename : "";
        final String mi = modelId       != null ? modelId       : "";
        final String lg = language      != null ? language      : "";
        final String rk = resultKey     != null ? resultKey     : "";
        final boolean dz = diarizeMode;

        transThread = new Thread(() -> {
            String result;
            try {
                WhisperBridge bridge = new WhisperBridge(this);
                result = dz
                    ? bridge.transcribeWithDiarization(af, mi, lg)
                    : bridge.transcribeAudio(af, mi, lg);
            } catch (Exception e) {
                Log.e(TAG, "Transcription error", e);
                result = "error: " + e.getMessage();
            }
            if (!stopped) writeResult(rk, result);
            stopAll();
        });
        transThread.start();
        return START_NOT_STICKY;
    }

    private void writeResult(String key, String text) {
        try {
            File dir = new File(getExternalFilesDir("QuickNote"), "transcripts");
            dir.mkdirs();
            File f = new File(dir, key + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "Result written: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write result", e);
        }
    }

    private void startNotifUpdates() {
        notifUpdater = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                long remain  = Math.max(0, estimatedSecs - elapsed);
                // Show appropriate phase label for diarization mode
                String phase = diarizeMode && elapsed < estimatedSecs / 3
                    ? "分析说话人中"
                    : diarizeMode ? "转录中" : "转录中";
                String detail = elapsed < estimatedSecs
                    ? String.format("%s 已用时 %ds，约还需 %ds", phase, elapsed, remain)
                    : String.format("%s 已用时 %ds，仍处理中...", phase, elapsed);
                NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, buildNotif(diarizeMode ? "转录+说话人识别" : "转录中", detail));
                handler.postDelayed(this, 5000);
            }
        };
        handler.postDelayed(notifUpdater, 5000);
    }

    private void stopAll() {
        handler.removeCallbacks(notifUpdater);
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "语音转录", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("QuickNote 本地语音转录");
            ch.setSound(null, null);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, piFlags);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(notifUpdater);
        super.onDestroy();
    }
}
