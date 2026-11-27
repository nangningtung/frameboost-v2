package com.secreto.space;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Foreground shell (mediaProjection type) required while MediaProjection
 * captures. The actual work runs inside ScreenRecorder.
 */
public class RecorderService extends Service {
    private static final String CHANNEL = "secreto_recording";

    // Cached MediaProjection consent token. Obtained once (consent is
    // pre-granted via appops so no dialog), then reused for every recording.
    // This is what makes RECORD instant in-game: no activity is launched.
    private static int sConsentCode = 0;
    private static Intent sConsentData = null;

    public static boolean hasConsent() { return sConsentData != null; }

    public static void saveConsent(int code, Intent data) {
        sConsentCode = code;
        sConsentData = data;
    }

    public static void clearConsent() {
        sConsentCode = 0;
        sConsentData = null;
    }

    public static void requestStart(Context ctx, int resultCode, Intent data,
                                    String preset, String audioMode) {
        Intent i = new Intent(ctx, RecorderService.class);
        i.setAction("start");
        i.putExtra("code", resultCode);
        i.putExtra("data", data);
        i.putExtra("preset", preset);
        i.putExtra("audio", audioMode);
        ctx.startService(i);
    }

    public static void requestStop(Context ctx) {
        ctx.startService(new Intent(ctx, RecorderService.class).setAction("stop"));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "start";
        if ("stop".equals(action)) {
            ScreenRecorder.stop();
            return START_NOT_STICKY;
        }
        startAsForeground();
        String preset = intent.getStringExtra("preset");
        String audio = intent.getStringExtra("audio");
        Intent data = intent.getParcelableExtra("data");
        int code = intent.getIntExtra("code", 0);
        if (data != null) {
            saveConsent(code, data);
        }
        ScreenRecorder.start(this, sConsentCode, sConsentData, preset, audio);
        new Thread(() -> {
            while (ScreenRecorder.isRecording()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            stopSelf();
        }, "SecretoRecWatch").start();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Secreto Space")
                .setContentText("Recording screen...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(0x5EC2, n);
    }
}
