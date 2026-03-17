package com.quicknote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Foreground Service that handles audio recording.
 * Survives screen lock, app switching, and low-memory situations.
 * Writes audio directly to getExternalFilesDir("QuickNote") as .m4a (AAC).
 */
public class RecordingService extends Service {

    static final String ACTION_START = "com.quicknote.app.START_RECORDING";
    static final String ACTION_STOP  = "com.quicknote.app.STOP_RECORDING";
    static final String EXTRA_FILE   = "filename";
    static final String CHANNEL_ID   = "qn_recording";
    static final int    NOTIF_ID     = 101;
    private static final String TAG  = "RecordingService";

    private MediaRecorder recorder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startRecording(intent.getStringExtra(EXTRA_FILE));
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
        }
        return START_STICKY;
    }

    private void startRecording(String filename) {
        if (filename == null || filename.isEmpty()) { stopSelf(); return; }

        File dir = getExternalFilesDir("QuickNote");
        if (dir != null) dir.mkdirs();
        String outputPath = new File(dir, filename).getAbsolutePath();

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(128000);
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

        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        Log.i(TAG, "Recording started → " + outputPath);
    }

    private void stopRecording() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop error", e); }
            recorder.release();
            recorder = null;
        }
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "Recording stopped");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("QuickNote 后台录音");
            ch.setSound(null, null);
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
        // Safety net: if service killed unexpectedly, stop recorder cleanly
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) {}
            recorder.release();
            recorder = null;
        }
        super.onDestroy();
    }
}
