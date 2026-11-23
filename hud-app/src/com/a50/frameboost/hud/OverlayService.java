package com.a50.frameboost.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * In-game overlay: a draggable floating bubble that opens an edge side panel.
 * The bubble snaps to the nearest screen edge; tapping (or flicking inward)
 * expands the GameSpace-style panel, swiping it back to the edge collapses it.
 */
public class OverlayService extends Service {
    private static final String CHANNEL_ID = "frameboost_hud_channel";
    private static final String PREFS = "hud_prefs";

    private static final int COLOR_TEXT = 0xFFE5E1E6;
    private static final int COLOR_DIM = 0xFFC9C5CA;
    private static final int COLOR_ACCENT = 0xFFD0BCFF;
    private static final int COLOR_ACTIVE_TEXT = 0xFFE8DEF8;
    private static final int COLOR_GREEN = 0xFF7DDCA5;
    private static final int COLOR_AMBER = 0xFFF4B77A;
    private static final int COLOR_RED = 0xFFFFB4AB;

    private static final int BUBBLE_SIZE_DP = 56;
    private static final int EDGE_MARGIN_DP = 10;
    private static final int PANEL_MARGIN_DP = 6;

    private WindowManager windowManager;
    private View overlayRoot;
    private View viewBubble;
    private View viewSidePanel;
    private View bubbleContent;
    private View dotState;
    private View dotPanelState;
    private View panelHeader;

    private TextView txtBubbleFps;
    private TextView txtBubbleExtra;
    private TextView txtPanelGame;
    private TextView txtMFps;
    private TextView txtMGpu;
    private TextView txtMCpu;
    private TextView txtMNet;
    private TextView txtMBat;
    private TextView txtTelemetryState;

    private Button btnTypeCompact;
    private Button btnTypeDetail;
    private Button btnTypeFull;
    private Button btnPresetBat;
    private Button btnPresetBal;
    private Button btnPresetPerf;
    private Button btnPresetEsp;

    private WindowManager.LayoutParams params;
    private Handler handler;
    private Runnable updateRunnable;
    private java.util.concurrent.ExecutorService ioExecutor;

    private boolean panelOpen = false;
    private boolean autoHide = true;
    private boolean bubbleTelemetry = true;
    private boolean dockedRight = false;
    private boolean touchMoved = false;

    private String hudType = "COMPACT";
    private String activePreset = "BALANCED";
    private String currentGamePkg = "";

    // Last rendered strings, to skip redundant layout passes at 2 Hz.
    private String lastBubbleFps = "";
    private String lastBubbleExtra = "";
    private String lastMFps = "";
    private String lastMGpu = "";
    private String lastMCpu = "";
    private String lastMNet = "";
    private String lastMBat = "";
    private String lastGame = "";

    private int lastScreenW = 0;
    private int lastScreenH = 0;
    private int insetLeft = 0;
    private int insetRight = 0;
    private int insetTop = 0;
    private int insetBottom = 0;

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

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

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        autoHide = prefs.getBoolean("auto_hide", true);
        bubbleTelemetry = prefs.getBoolean("bubble_telemetry", true);
        hudType = prefs.getString("hud_type", prefs.getString("fps_overlay_type", "COMPACT"));
        activePreset = prefs.getString("game_preset", "BALANCED");
        dockedRight = prefs.getBoolean("hud_right", false);
        int savedX = prefs.getInt("hud_x", EDGE_MARGIN_DP);
        int savedY = prefs.getInt("hud_y", 140);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayRoot = inflater.inflate(R.layout.overlay_layout, null);

        viewBubble = overlayRoot.findViewById(R.id.view_bubble);
        viewSidePanel = overlayRoot.findViewById(R.id.view_sidepanel);
        bubbleContent = overlayRoot.findViewById(R.id.bubble_content);
        dotState = overlayRoot.findViewById(R.id.dot_state);
        dotPanelState = overlayRoot.findViewById(R.id.dot_panel_state);
        panelHeader = overlayRoot.findViewById(R.id.panel_header);

        txtBubbleFps = overlayRoot.findViewById(R.id.txt_bubble_fps);
        txtBubbleExtra = overlayRoot.findViewById(R.id.txt_bubble_extra);
        txtPanelGame = overlayRoot.findViewById(R.id.txt_panel_game);
        txtMFps = overlayRoot.findViewById(R.id.txt_m_fps);
        txtMGpu = overlayRoot.findViewById(R.id.txt_m_gpu);
        txtMCpu = overlayRoot.findViewById(R.id.txt_m_cpu);
        txtMNet = overlayRoot.findViewById(R.id.txt_m_net);
        txtMBat = overlayRoot.findViewById(R.id.txt_m_bat);
        txtTelemetryState = overlayRoot.findViewById(R.id.txt_telemetry_state);

        btnTypeCompact = overlayRoot.findViewById(R.id.btn_type_compact);
        btnTypeDetail = overlayRoot.findViewById(R.id.btn_type_detail);
        btnTypeFull = overlayRoot.findViewById(R.id.btn_type_full);
        btnPresetBat = overlayRoot.findViewById(R.id.btn_preset_battery);
        btnPresetBal = overlayRoot.findViewById(R.id.btn_preset_balanced);
        btnPresetPerf = overlayRoot.findViewById(R.id.btn_preset_perf);
        btnPresetEsp = overlayRoot.findViewById(R.id.btn_preset_esports);

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
        params.y = Math.max(0, savedY);

        windowManager.addView(overlayRoot, params);

        applyBubbleMode();
        applyTelemetryState();
        highlightHudType();
        highlightPreset();

        Handler main = new Handler(Looper.getMainLooper());
        viewBubble.post(() -> {
            refreshInsets();
            if (dockedRight) {
                params.x = Math.max(0, usableWidth() - viewBubble.getWidth() - dp(EDGE_MARGIN_DP));
            } else {
                params.x = Math.max(0, Math.min(savedX, usableWidth() - bubbleWidth()));
            }
            clampY();
            updateLayout();
        });

        setupBubbleTouch();
        setupPanelActions();

        handler = main;
        ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                ioExecutor.execute(() -> {
                    final SnapshotReader.SnapshotData data = SnapshotReader.read();
                    handler.post(() -> applyHudData(data));
                });
                // 2 Hz is enough for HUD telemetry; keeps IPC/read overhead low on the A50.
                handler.postDelayed(this, 500);
            }
        };
        handler.post(updateRunnable);
    }

    // ---------------------------------------------------------------- layout

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getScreenWidth() {
        if (windowManager == null) return 1080;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return bounds.width();
        }
        android.graphics.Point size = new android.graphics.Point();
        windowManager.getDefaultDisplay().getSize(size);
        return size.x;
    }

    private int getScreenHeight() {
        if (windowManager == null) return 1920;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.getCurrentWindowMetrics().getBounds().height();
        }
        android.graphics.Point size = new android.graphics.Point();
        windowManager.getDefaultDisplay().getSize(size);
        return size.y;
    }

    /**
     * The overlay window is fitted inside the system bars, so params.x/y are
     * relative to that inset frame, not the raw display. Cache the insets so
     * edge snapping and clamping use the same space the window lives in.
     */
    private void refreshInsets() {
        int left = 0, top = 0, right = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            try {
                android.view.WindowInsets insets = windowManager.getCurrentWindowMetrics()
                        .getWindowInsets();
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } catch (Exception ignored) {}
        }
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
    }

    private int usableWidth() {
        return Math.max(dp(BUBBLE_SIZE_DP), getScreenWidth() - insetLeft - insetRight);
    }

    private int usableHeight() {
        return Math.max(dp(BUBBLE_SIZE_DP), getScreenHeight() - insetTop - insetBottom);
    }

    private int dockedX(int contentWidth) {
        return dockedRight
                ? Math.max(0, usableWidth() - contentWidth - dp(EDGE_MARGIN_DP))
                : dp(EDGE_MARGIN_DP);
    }

    private int clampPanelY(int panelHeight) {
        int margin = dp(PANEL_MARGIN_DP);
        int maxY = Math.max(0, usableHeight() - panelHeight - margin);
        return Math.max(0, Math.min(params.y, maxY));
    }

    private int bubbleWidth() {
        int w = viewBubble != null ? viewBubble.getWidth() : 0;
        return w > 0 ? w : dp(BUBBLE_SIZE_DP);
    }

    private int panelWidth() {
        int w = viewSidePanel != null ? viewSidePanel.getWidth() : 0;
        return w > 0 ? w : dp(288);
    }

    private void clampY() {
        int contentHeight = panelOpen ? viewSidePanel.getHeight() : bubbleWidth();
        if (contentHeight <= 0) contentHeight = dp(BUBBLE_SIZE_DP);
        int maxY = Math.max(0, usableHeight() - contentHeight - dp(EDGE_MARGIN_DP));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    private void updateLayout() {
        if (overlayRoot == null || windowManager == null) return;
        try {
            windowManager.updateViewLayout(overlayRoot, params);
        } catch (Exception ignored) {}
    }

    private void placeBubble() {
        panelOpen = false;
        clampY();
        params.x = dockedX(bubbleWidth());
        updateLayout();
        savePosition();
    }

    private void snapToEdge(int fromX) {
        int width = bubbleWidth();
        boolean right = (fromX + width / 2) >= usableWidth() / 2;
        dockedRight = right;
        int targetX = dockedX(width);

        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(fromX, targetX);
        animator.setDuration(190);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            params.x = (int) a.getAnimatedValue();
            updateLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                savePosition();
            }
        });
        animator.start();
    }

    private void savePosition() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("hud_right", dockedRight)
                    .putInt("hud_x", params.x)
                    .putInt("hud_y", params.y)
                    .apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ bubble touch

    private void setupBubbleTouch() {
        viewBubble.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float downRawX;
            private float downRawY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        touchMoved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) >= dp(6) || Math.abs(dy) >= dp(6)) {
                            touchMoved = true;
                        }
                        if (touchMoved) {
                            int usable = usableWidth();
                            int bw = bubbleWidth();
                            params.x = Math.max(0, Math.min(initialX + (int) dx, usable - bw));
                            params.y = Math.max(0, initialY + (int) dy);
                            updateLayout();
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        long elapsed = System.currentTimeMillis() - downTime;
                        boolean flingInward = elapsed < 280
                                && Math.abs(dx) > dp(56)
                                && Math.abs(dy) < dp(44)
                                && (dockedRight ? dx < 0 : dx > 0);

                        if (!touchMoved || flingInward) {
                            if (touchMoved) {
                                placeBubble();
                            }
                            openPanel();
                        } else {
                            snapToEdge(params.x);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_CANCEL:
                        snapToEdge(params.x);
                        return true;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------- side panel

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        triggerHaptic();

        viewBubble.setVisibility(View.GONE);
        viewSidePanel.setVisibility(View.VISIBLE);
        viewSidePanel.setAlpha(0f);
        viewSidePanel.setScaleX(0.96f);
        viewSidePanel.setScaleY(0.96f);
        viewSidePanel.setTranslationX(dockedRight ? dp(28) : -dp(28));
        viewSidePanel.setPivotY(0f);

        viewSidePanel.post(() -> {
            int pw = viewSidePanel.getWidth();
            int ph = viewSidePanel.getHeight();
            viewSidePanel.setPivotX(dockedRight ? pw : 0f);
            params.x = dockedRight
                    ? Math.max(0, usableWidth() - pw - dp(PANEL_MARGIN_DP))
                    : dp(PANEL_MARGIN_DP);
            params.y = clampPanelY(ph);
            updateLayout();

            viewSidePanel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setDuration(210)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        });
    }

    private void closePanel() {
        if (!panelOpen) return;
        triggerHaptic();
        panelOpen = false;

        viewSidePanel.animate()
                .alpha(0f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .translationX(dockedRight ? dp(28) : -dp(28))
                .setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    viewSidePanel.setVisibility(View.GONE);
                    viewBubble.setVisibility(View.VISIBLE);
                    viewBubble.post(OverlayService.this::placeBubble);
                })
                .start();
    }

    private void setupPanelActions() {
        View close = overlayRoot.findViewById(R.id.btn_close_panel);
        if (close != null) close.setOnClickListener(v -> closePanel());

        // Horizontal swipe on the header collapses the panel back to the bubble.
        if (panelHeader != null) {
            panelHeader.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = event.getRawX() - downX;
                            float dy = event.getRawY() - downY;
                            boolean outward = dockedRight ? dx > dp(44) : dx < -dp(44);
                            if (outward && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                                closePanel();
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            return true;
                    }
                    return false;
                }
            });
        }

        if (btnTypeCompact != null) btnTypeCompact.setOnClickListener(v -> setHudType("COMPACT"));
        if (btnTypeDetail != null) btnTypeDetail.setOnClickListener(v -> setHudType("DETAIL"));
        if (btnTypeFull != null) btnTypeFull.setOnClickListener(v -> setHudType("FULL"));

        View.OnClickListener presetListener = v -> {
            String preset = "BALANCED";
            if (v.getId() == R.id.btn_preset_battery) preset = "BATTERY";
            else if (v.getId() == R.id.btn_preset_perf) preset = "PERFORMANCE";
            else if (v.getId() == R.id.btn_preset_esports) preset = "ESPORTS";
            applyPreset(preset);
        };
        if (btnPresetBat != null) btnPresetBat.setOnClickListener(presetListener);
        if (btnPresetBal != null) btnPresetBal.setOnClickListener(presetListener);
        if (btnPresetPerf != null) btnPresetPerf.setOnClickListener(presetListener);
        if (btnPresetEsp != null) btnPresetEsp.setOnClickListener(presetListener);

        View telemetry = overlayRoot.findViewById(R.id.btn_bubble_telemetry);
        if (telemetry != null) {
            telemetry.setOnClickListener(v -> {
                triggerHaptic();
                bubbleTelemetry = !bubbleTelemetry;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("bubble_telemetry", bubbleTelemetry).apply();
                applyTelemetryState();
                refreshBubbleText();
            });
        }
    }

    private void setHudType(String type) {
        triggerHaptic();
        hudType = type;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("hud_type", type).apply();
        highlightHudType();
        applyBubbleMode();
        refreshBubbleText();
        lastBubbleFps = "";
        lastBubbleExtra = "";
    }

    private void applyPreset(String preset) {
        triggerHaptic();
        activePreset = preset;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("game_preset", preset).apply();
        highlightPreset();
        final String pkg = currentGamePkg;
        if (pkg == null || pkg.isEmpty()) return;
        // No auto-BBR argument: the daemon keeps the game's existing choice.
        new Thread(() -> IpcClient.sendCommand("set_game_profile " + pkg + " " + preset)).start();
    }

    private void highlightHudType() {
        boolean compact = "COMPACT".equals(hudType);
        boolean detail = "DETAIL".equals(hudType);
        boolean full = "FULL".equals(hudType);
        styleSegment(btnTypeCompact, compact);
        styleSegment(btnTypeDetail, detail);
        styleSegment(btnTypeFull, full);
    }

    private void highlightPreset() {
        styleSegment(btnPresetBat, "BATTERY".equals(activePreset));
        styleSegment(btnPresetBal, "BALANCED".equals(activePreset));
        styleSegment(btnPresetPerf, "PERFORMANCE".equals(activePreset));
        styleSegment(btnPresetEsp, "ESPORTS".equals(activePreset));
    }

    private void styleSegment(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundResource(active ? R.drawable.bg_segment_active : android.R.color.transparent);
        btn.setTextColor(active ? COLOR_ACTIVE_TEXT : COLOR_DIM);
        btn.setTypeface(android.graphics.Typeface.create(
                "monospace", active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
    }

    private void applyBubbleMode() {
        if (viewBubble == null) return;
        boolean compact = "COMPACT".equals(hudType);
        if (txtBubbleExtra != null) {
            txtBubbleExtra.setVisibility(compact ? View.GONE : View.VISIBLE);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewBubble.getLayoutParams();
        lp.width = compact ? dp(BUBBLE_SIZE_DP) : FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.height = dp(BUBBLE_SIZE_DP);
        viewBubble.setLayoutParams(lp);
        viewBubble.setMinimumWidth(dp(BUBBLE_SIZE_DP));

        if (!panelOpen && viewBubble.getVisibility() == View.VISIBLE) {
            viewBubble.post(() -> {
                if (!panelOpen) {
                    params.x = dockedX(bubbleWidth());
                    updateLayout();
                }
            });
        }
    }

    private void applyTelemetryState() {
        if (bubbleContent != null) {
            bubbleContent.setVisibility(bubbleTelemetry ? View.VISIBLE : View.GONE);
        }
        if (dotState != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dotState.getLayoutParams();
            if (bubbleTelemetry) {
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.setMargins(0, dp(8), dp(10), 0);
            } else {
                lp.gravity = Gravity.CENTER;
                lp.setMargins(0, 0, 0, 0);
            }
            dotState.setLayoutParams(lp);
        }
        if (txtTelemetryState != null) {
            txtTelemetryState.setText(bubbleTelemetry ? "ON" : "OFF");
            txtTelemetryState.setTextColor(bubbleTelemetry ? COLOR_ACCENT : COLOR_DIM);
        }
    }

    // ------------------------------------------------------------- telemetry

    private void applyHudData(SnapshotReader.SnapshotData data) {
        if (data == null || overlayRoot == null) return;

        // Re-anchor if the screen metrics changed (rotation, split screen).
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        if (sw != lastScreenW || sh != lastScreenH) {
            lastScreenW = sw;
            lastScreenH = sh;
            refreshInsets();
            if (panelOpen) {
                int pw = panelWidth();
                params.x = dockedRight
                        ? Math.max(0, usableWidth() - pw - dp(PANEL_MARGIN_DP))
                        : dp(PANEL_MARGIN_DP);
                params.y = clampPanelY(viewSidePanel.getHeight());
                updateLayout();
            } else {
                placeBubble();
            }
        }

        currentGamePkg = data.gamePkg == null ? "" : data.gamePkg;

        if (autoHide && !data.isGame) {
            if (panelOpen) {
                panelOpen = false;
                viewSidePanel.animate().cancel();
                viewSidePanel.setVisibility(View.GONE);
                viewBubble.setVisibility(View.VISIBLE);
            }
            overlayRoot.setVisibility(View.GONE);
            return;
        }
        overlayRoot.setVisibility(View.VISIBLE);

        // Thermal status dot on bubble (and panel header uses the same drawable).
        int dotColor;
        if (data.tempBigC >= 90) dotColor = COLOR_RED;
        else if (data.tempBigC >= 85) dotColor = COLOR_AMBER;
        else dotColor = COLOR_GREEN;
        if (dotState != null) {
            dotState.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        }
        if (dotPanelState != null) {
            dotPanelState.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        }

        refreshBubbleText(data);
        refreshPanel(data);
    }

    private void refreshBubbleText() {
        refreshBubbleText(null);
    }

    private void refreshBubbleText(SnapshotReader.SnapshotData data) {
        if (txtBubbleFps == null) return;
        if (data == null) {
            // Re-render with the last known strings only for mode/telemetry changes.
            txtBubbleFps.setText(lastBubbleFps.isEmpty() ? "60" : lastBubbleFps);
            if (txtBubbleExtra != null) {
                txtBubbleExtra.setText(lastBubbleExtra.isEmpty() ? "--" : lastBubbleExtra);
            }
            return;
        }

        String fps = String.format(java.util.Locale.US, "%.0f",
                data.fps > 0 ? data.fps : 60.0);
        if (!fps.equals(lastBubbleFps)) {
            txtBubbleFps.setText(fps);
            lastBubbleFps = fps;
        }

        if (txtBubbleExtra != null && !"COMPACT".equals(hudType)) {
            String extra;
            if ("FULL".equals(hudType)) {
                extra = String.format(java.util.Locale.US, "%d°C · %dM", data.tempBigC, data.gpuClockMhz);
            } else {
                extra = String.format(java.util.Locale.US, "%d°C", data.tempBigC);
            }
            if (!extra.equals(lastBubbleExtra)) {
                txtBubbleExtra.setText(extra);
                lastBubbleExtra = extra;
            }
        }
    }

    private void refreshPanel(SnapshotReader.SnapshotData data) {
        if (txtPanelGame == null) return;

        String game = data.gamePkg.isEmpty() ? "No game" : shortGameName(data.gamePkg);
        if (!game.equals(lastGame)) {
            txtPanelGame.setText(game);
            lastGame = game;
        }

        String fps = String.format(java.util.Locale.US, "%.1f FPS", data.fps > 0 ? data.fps : 60.0);
        if (!fps.equals(lastMFps)) { txtMFps.setText(fps); lastMFps = fps; }

        String gpu = String.format(java.util.Locale.US, "%d MHz · %.0f%%", data.gpuClockMhz, data.gpuBusyPct);
        if (!gpu.equals(lastMGpu)) { txtMGpu.setText(gpu); lastMGpu = gpu; }

        String cpu = String.format(java.util.Locale.US, "%.2f GHz · %d°C", data.cpuBigMhz / 1000.0, data.tempBigC);
        if (!cpu.equals(lastMCpu)) { txtMCpu.setText(cpu); lastMCpu = cpu; }

        String net = data.tcpCc == null ? "--" : data.tcpCc.toUpperCase();
        if (!net.equals(lastMNet)) { txtMNet.setText(net); lastMNet = net; }

        String bat = String.format(java.util.Locale.US, "%d%% · %.1fW%s",
                data.battCapacity, data.battPowerW,
                data.battStatus.equalsIgnoreCase("Charging") ? " · CHG" : "");
        if (!bat.equals(lastMBat)) { txtMBat.setText(bat); lastMBat = bat; }
    }

    private String shortGameName(String pkg) {
        int dot = pkg.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        if (tail.isEmpty()) return pkg;
        return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }

    private void triggerHaptic() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(18, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(18);
                }
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FrameBoost HUD Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Running in-game overlay");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("FrameBoost HUD")
                .setContentText("In-game bubble and side panel are active")
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
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {}
        }
    }
}
