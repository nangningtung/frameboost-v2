package com.a50.frameboost.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "frameboost_hud_channel";
    private WindowManager windowManager;
    private View overlayRoot;
    private View viewPill;
    private View viewDrawer;

    private TextView txtPillFps;
    private TextView txtPillTemp;
    private TextView txtPillGpu;

    private TextView txtDrawerGame;
    private TextView txtDrawerFpsDetail;
    private TextView txtDrawerGpuDetail;
    private TextView txtDrawerCpuDetail;
    private TextView txtDrawerNetDetail;

    private WindowManager.LayoutParams params;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isDrawerOpen = false;
    private boolean autoHide = true;
    private String currentGamePkg = "";

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private Button btnBat;
    private Button btnBal;
    private Button btnPerf;
    private Button btnEsp;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(1, createNotification());

        SharedPreferences prefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE);
        autoHide = prefs.getBoolean("auto_hide", true);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayRoot = inflater.inflate(R.layout.overlay_layout, null);

        viewPill = overlayRoot.findViewById(R.id.view_pill);
        viewDrawer = overlayRoot.findViewById(R.id.view_drawer);

        txtPillFps = overlayRoot.findViewById(R.id.txt_pill_fps);
        txtPillTemp = overlayRoot.findViewById(R.id.txt_pill_temp);
        txtPillGpu = overlayRoot.findViewById(R.id.txt_pill_gpu);

        txtDrawerGame = overlayRoot.findViewById(R.id.txt_drawer_game);
        txtDrawerFpsDetail = overlayRoot.findViewById(R.id.txt_drawer_fps_detail);
        txtDrawerGpuDetail = overlayRoot.findViewById(R.id.txt_drawer_gpu_detail);
        txtDrawerCpuDetail = overlayRoot.findViewById(R.id.txt_drawer_cpu_detail);
        txtDrawerNetDetail = overlayRoot.findViewById(R.id.txt_drawer_net_detail);

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 120;

        windowManager.addView(overlayRoot, params);

        setupTouchListener();
        setupDrawerActions();

        handler = new Handler(Looper.getMainLooper());
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateHudData();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(updateRunnable);
    }

    private void setupTouchListener() {
        viewPill.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayRoot, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getRawX() - initialTouchX);
                        float dy = Math.abs(event.getRawY() - initialTouchY);
                        if (dx < 15 && dy < 15) {
                            toggleDrawer();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void triggerHaptic() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(20);
                }
            }
        } catch (Exception ignored) {}
    }

    private void toggleDrawer() {
        triggerHaptic();
        isDrawerOpen = !isDrawerOpen;
        if (isDrawerOpen) {
            viewPill.setVisibility(View.GONE);
            viewDrawer.setVisibility(View.VISIBLE);
        } else {
            viewDrawer.setVisibility(View.GONE);
            viewPill.setVisibility(View.VISIBLE);
        }
        windowManager.updateViewLayout(overlayRoot, params);
    }

    private void setupDrawerActions() {
        View btnClose = overlayRoot.findViewById(R.id.btn_collapse);
        btnClose.setClickable(true);
        btnClose.setOnClickListener(v -> toggleDrawer());

        // Allow dragging the drawer by touching its header
        View header = overlayRoot.findViewById(R.id.txt_drawer_game);
        if (header != null) {
            header.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.x = initialX + (int) (event.getRawX() - initialTouchX);
                            params.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(overlayRoot, params);
                            return true;
                    }
                    return false;
                }
            });
        }

        btnBat = overlayRoot.findViewById(R.id.btn_preset_battery);
        btnBal = overlayRoot.findViewById(R.id.btn_preset_balanced);
        btnPerf = overlayRoot.findViewById(R.id.btn_preset_perf);
        btnEsp = overlayRoot.findViewById(R.id.btn_preset_esports);

        View.OnClickListener listener = v -> {
            triggerHaptic();
            String preset = "BALANCED";
            if (v.getId() == R.id.btn_preset_battery) preset = "BATTERY";
            else if (v.getId() == R.id.btn_preset_perf) preset = "PERFORMANCE";
            else if (v.getId() == R.id.btn_preset_esports) preset = "ESPORTS";

            final String finalPreset = preset;
            new Thread(() -> {
                if (!currentGamePkg.isEmpty()) {
                    IpcClient.sendCommand("set_game_profile " + currentGamePkg + " " + finalPreset + " 1");
                }
            }).start();
        };

        btnBat.setOnClickListener(listener);
        btnBal.setOnClickListener(listener);
        btnPerf.setOnClickListener(listener);
        btnEsp.setOnClickListener(listener);
    }

    private void updateHudData() {
        SnapshotReader.SnapshotData data = SnapshotReader.read();
        if (data == null) return;

        currentGamePkg = data.gamePkg;

        // Auto-hide handling
        if (autoHide && !data.isGame) {
            overlayRoot.setVisibility(View.GONE);
            return;
        } else {
            overlayRoot.setVisibility(View.VISIBLE);
        }

        // Pill values
        txtPillFps.setText(String.format("%.0f FPS", data.fps > 0 ? data.fps : 60.0));
        txtPillTemp.setText(data.tempBigC + "°C");
        txtPillGpu.setText(data.gpuClockMhz + "M");

        // Drawer values if expanded
        if (isDrawerOpen) {
            txtDrawerGame.setText(data.gamePkg.isEmpty() ? "No Game" : data.gamePkg);
            txtDrawerFpsDetail.setText(String.format("FPS: %.1f (1%% Low: %.1f)", data.fps, data.p1LowFps));
            txtDrawerGpuDetail.setText(String.format("GPU: %dMHz (%.0f%%)", data.gpuClockMhz, data.gpuBusyPct));
            txtDrawerCpuDetail.setText(String.format("CPU: %.2fGHz • Temp: %d°C", data.cpuBigMhz / 1000.0, data.tempBigC));
            txtDrawerNetDetail.setText("NET: " + data.tcpCc.toUpperCase() + " Active");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FrameBoost HUD Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Running in-game overlay");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("FrameBoost HUD")
                .setContentText("In-game telemetry overlay is active")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
        if (overlayRoot != null && windowManager != null) {
            windowManager.removeView(overlayRoot);
        }
    }
}
