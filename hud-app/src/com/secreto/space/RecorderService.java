package com.secreto.space;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Foreground shell (mediaProjection type) in its OWN process (:recorder).
 * Running the MediaProjection + encoders in a separate process means an OOM
 * while recording can never kill the overlay/WebUI process (the side panel
 * used to disappear mid-recording because of this).
 */
public class RecorderService extends Service {
    private static final String CHANNEL = "secreto_recording";

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
        ScreenRecorder.start(this, intent.getIntExtra("code", 0),
                intent.getParcelableExtra("data"), preset, audio);
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
