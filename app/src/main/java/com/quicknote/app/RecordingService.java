package com.quicknote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Foreground Service that handles audio recording.
 * Survives screen lock, app switching, and low-memory situations.
 * Writes audio directly to getExternalFilesDir("QuickNote") as .m4a (AAC).
 *
 * Background survival strategy:
 * - START_STICKY: Android restarts us if killed
 * - PARTIAL_WAKE_LOCK: prevents CPU sleep during recording
 * - IMPORTANCE_DEFAULT notification: system treats service as important
 * - Foreground service type "microphone": granted special protection on Android 10+
 */
public class RecordingService extends Service {

    static final String ACTION_START    = "com.quicknote.app.START_RECORDING";
    static final String ACTION_STOP     = "com.quicknote.app.STOP_RECORDING";
    static final String EXTRA_FILE      = "filename";
    static final String EXTRA_QUALITY   = "quality"; // "low" | "standard" | "high"
    static final String CHANNEL_ID   = "qn_recording";
    static final int    NOTIF_ID     = 101;
    private static final String TAG  = "RecordingService";

    private MediaRecorder       recorder;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startRecording(intent.getStringExtra(EXTRA_FILE), intent.getStringExtra(EXTRA_QUALITY));
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
        }
        return START_STICKY;
    }

    private void startRecording(String filename) {
        startRecording(filename, "standard");
    }

    private void startRecording(String filename, String quality) {
        if (filename == null || filename.isEmpty()) { stopSelf(); return; }

        File dir = getExternalFilesDir("QuickNote");
        if (dir != null) dir.mkdirs();
        String outputPath = new File(dir, filename).getAbsolutePath();

        // Quality presets:
        // low:      16kHz 48kbps  ~0.35MB/min (~21MB/hr) — minimum for speech
        // standard: 16kHz 96kbps  ~0.7MB/min  (~42MB/hr) — good balance
        // high:     44.1kHz 192kbps ~1.4MB/min (~84MB/hr) — best clarity
        int sampleRate, bitRate;
        switch (quality != null ? quality : "standard") {
            case "low":      sampleRate = 16000; bitRate = 48000;  break;
            case "high":     sampleRate = 44100; bitRate = 192000; break;
            default:         sampleRate = 16000; bitRate = 96000;  break; // standard
        }
        Log.i(TAG, "Recording quality=" + quality + " rate=" + sampleRate + " bitrate=" + bitRate);

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(sampleRate);
        recorder.setAudioEncodingBitRate(bitRate);
        recorder.setAudioChannels(1);
        recorder.setOutputFile(outputPath);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            Log.e(TAG, "recorder prepare failed", e);
            recorder.release();
            recorder = null;
            stopSelf();
            return;
        }

        // PARTIAL_WAKE_LOCK: keeps CPU awake even with screen off
        // Critical for reliable recording on aggressive OEM ROMs (Honor/EMUI, MIUI, etc.)
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickNote::Recording");
            wakeLock.acquire(4 * 60 * 60 * 1000L); // max 4 hours
        }

        createChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        Log.i(TAG, "Recording started → " + outputPath);
    }

    private void stopRecording() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop error", e); }
            recorder.release();
            recorder = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "Recording stopped");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT (not LOW): system less likely to deprioritize/kill this service
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("QuickNote 后台录音");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle("QuickNote 录音中")
                .setContentText("后台录音运行中，点击返回应用")
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) {}
            recorder.release();
            recorder = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        super.onDestroy();
    }
}
