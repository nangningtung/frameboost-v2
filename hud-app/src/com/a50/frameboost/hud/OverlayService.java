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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-game overlay: a draggable floating bubble that opens an edge side panel.
 * The bubble snaps to the nearest screen edge; tapping (or flicking inward)
 * expands the GameSpace-style panel, swiping it back to the edge collapses it.
 */
public class OverlayService extends Service {
    private static final String CHANNEL_ID = "frameboost_hud_channel";
    private static final String PREFS = "hud_prefs";

    private static final int COLOR_DIM = 0xFF94A3B8;
    private static final int COLOR_ACCENT = 0xFF00E5FF;
    private static final int COLOR_ACTIVE_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFF1F5F9;
    private static final int COLOR_GREEN = 0xFF00E676;
    private static final int COLOR_AMBER = 0xFFFF9100;
    private static final int COLOR_RED = 0xFFFF1744;

    private static final int BUBBLE_SIZE_DP = 56;
    private static final int HANDLE_WIDTH_DP = 40;
    private static final int HANDLE_HEIGHT_DP = 40;
    private static final int HANDLE_SMALL_WIDTH_DP = 14;
    private static final int HANDLE_SMALL_HEIGHT_DP = 48;
    private static final int EDGE_MARGIN_DP = 0;
    private static final int PANEL_MARGIN_DP = 6;

    private WindowManager windowManager;
    private View overlayRoot;
    private View viewBubble;
    private View viewSidePanel;
    private View dotState;
    private View dotPanelState;
    private View panelHeader;

    private TextView txtBubbleFps;
    private TextView txtPanelGame;
    private TextView txtMFps;
    private TextView txtMFpsStatus;
    private TextView txtMGpu;
    private TextView txtMCpu;
    private TextView txtMNet;
    private TextView txtMBat;
    private TextView txtTelemetryState;

    private Button btnTypeOff;
    private Button btnTypeCompact;
    private Button btnTypeDetail;
    private Button btnTypeFull;
    private Button btnPresetBat;
    private Button btnPresetBal;
    private Button btnPresetPerf;
    private Button btnPresetEsp;
    private Button btnQuickRam;
    private Button btnQuickDnd;
    private Button btnQuickBypass;
    private Button btnQuickGos;

    private WindowManager.LayoutParams params;
    private Handler handler;
    private Runnable updateRunnable;
    private java.util.concurrent.ExecutorService ioExecutor;
    private java.util.concurrent.ExecutorService actionExecutor;
    private android.animation.ValueAnimator snapAnimator;

    private boolean panelOpen = false;
    private boolean autoHide = true;
    private boolean fpsOverlayEnabled = true;
    private boolean dndEnabled = true;
    private boolean bypassEnabled = false;
    private boolean gosDisabled = false;
    private boolean hasGos = false;
    private boolean dockedRight = false;
    private boolean touchMoved = false;
    private boolean yAutoCentered = false;
    private int missedReads = 0;
    /** Bubble's own Y while the panel borrows the window (panel Y is separate). */
    private int bubbleY = -1;

    private String hudType = "COMPACT";
    private String activePreset = "BALANCED";
    private String currentGamePkg = "";

    // Last rendered strings, to skip redundant layout passes at 2 Hz.
    private String lastBubbleText = "";
    private SnapshotReader.SnapshotData lastData;
    private String lastMFps = "";
    private String lastMFpsStatus = "";
    private String lastMGpu = "";
    private String lastMCpu = "";
    private String lastMNet = "";
    private String lastMBat = "";
    private String lastGame = "";

    private int lastScreenW = 0;
    private int lastScreenH = 0;

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
        fpsOverlayEnabled = prefs.getBoolean("fps_overlay_enabled", true);
        dndEnabled = prefs.getBoolean("gaming_dnd", true);
        hudType = prefs.getString("hud_type", prefs.getString("fps_overlay_type", "COMPACT"));
        activePreset = prefs.getString("game_preset", "BALANCED");
        dockedRight = prefs.getBoolean("hud_right", false);
        int savedX = prefs.getInt("hud_x", EDGE_MARGIN_DP);
        int savedY = prefs.getInt("hud_y", -1);
        yAutoCentered = savedY < 0;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayRoot = inflater.inflate(R.layout.overlay_layout, null);

        viewBubble = overlayRoot.findViewById(R.id.view_bubble);
        viewSidePanel = overlayRoot.findViewById(R.id.view_sidepanel);
        dotState = overlayRoot.findViewById(R.id.dot_state);
        dotPanelState = overlayRoot.findViewById(R.id.dot_panel_state);
        panelHeader = overlayRoot.findViewById(R.id.panel_header);

        txtBubbleFps = overlayRoot.findViewById(R.id.txt_bubble_fps);
        txtPanelGame = overlayRoot.findViewById(R.id.txt_panel_game);
        txtMFps = overlayRoot.findViewById(R.id.txt_m_fps);
        txtMFpsStatus = overlayRoot.findViewById(R.id.txt_m_fps_status);
        txtMGpu = overlayRoot.findViewById(R.id.txt_m_gpu);
        txtMCpu = overlayRoot.findViewById(R.id.txt_m_cpu);
        txtMNet = overlayRoot.findViewById(R.id.txt_m_net);
        txtMBat = overlayRoot.findViewById(R.id.txt_m_bat);
        txtTelemetryState = overlayRoot.findViewById(R.id.txt_telemetry_state);

        btnTypeOff = overlayRoot.findViewById(R.id.btn_type_off);
        btnTypeCompact = overlayRoot.findViewById(R.id.btn_type_compact);
        btnTypeDetail = overlayRoot.findViewById(R.id.btn_type_detail);
        btnTypeFull = overlayRoot.findViewById(R.id.btn_type_full);
        btnPresetBat = overlayRoot.findViewById(R.id.btn_preset_battery);
        btnPresetBal = overlayRoot.findViewById(R.id.btn_preset_balanced);
        btnPresetPerf = overlayRoot.findViewById(R.id.btn_preset_perf);
        btnPresetEsp = overlayRoot.findViewById(R.id.btn_preset_esports);
        btnQuickRam = overlayRoot.findViewById(R.id.btn_quick_ram);
        btnQuickDnd = overlayRoot.findViewById(R.id.btn_quick_dnd);
        btnQuickBypass = overlayRoot.findViewById(R.id.btn_quick_bypass);
        btnQuickGos = overlayRoot.findViewById(R.id.btn_quick_gos);

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        params.gravity = Gravity.TOP | Gravity.START;
        if (savedY >= 0) params.y = savedY;

        windowManager.addView(overlayRoot, params);

        applyBubbleMode();
        applyFpsOverlayState();
        highlightHudType();
        highlightPreset();
        updateDndButton();
        updateBypassButton();

        Handler main = new Handler(Looper.getMainLooper());
        viewBubble.post(() -> {
            if (yAutoCentered) {
                centerBubbleY();
            }
            params.x = dockedX(bubbleWidth());
            clampY();
            updateLayout();
        });

        setupBubbleTouch();
        setupPanelActions();

        handler = main;
        ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        actionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                ioExecutor.execute(() -> {
                    final SnapshotReader.SnapshotData data = SnapshotReader.read();
                    // Sequential telemetry: schedule the next read only after this one
                    // has finished, so slow IPC cannot pile up in the executor queue.
                    handler.post(() -> {
                        applyHudData(data);
                        if (running) handler.postDelayed(updateRunnable, 200);
                    });
                });
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
     * The overlay window parent spans the full display [0, 0, width, height],
     * so params.x/y are already in display coordinates. Do not subtract system
     * bar insets here or the right dock ends up short of the screen edge.
     */
    private int usableWidth() {
        return Math.max(dp(BUBBLE_SIZE_DP), getScreenWidth());
    }

    private int usableHeight() {
        return Math.max(dp(BUBBLE_SIZE_DP), getScreenHeight());
    }

    private int dockedX(int contentWidth) {
        return dockedRight
                ? Math.max(0, usableWidth() - contentWidth)
                : 0;
    }

    private int clampPanelY(int panelHeight) {
        int margin = dp(PANEL_MARGIN_DP);
        int maxY = Math.max(0, usableHeight() - panelHeight - margin);
        return Math.max(0, Math.min(params.y, maxY));
    }

    private int targetBubbleWidth() {
        if (!fpsOverlayEnabled) return dp(HANDLE_SMALL_WIDTH_DP);
        if ("FULL".equals(hudType)) return dp(48);
        if ("DETAIL".equals(hudType)) return dp(46);
        return dp(44);
    }

    private int targetBubbleHeight() {
        if (!fpsOverlayEnabled) return dp(HANDLE_SMALL_HEIGHT_DP);
        if ("FULL".equals(hudType)) return dp(62);
        if ("DETAIL".equals(hudType)) return dp(48);
        return dp(44);
    }

    private int bubbleWidth() {
        int w = viewBubble != null ? viewBubble.getWidth() : 0;
        if (w > 0) return w;
        return targetBubbleWidth();
    }

    private int bubbleHeight() {
        int h = viewBubble != null ? viewBubble.getHeight() : 0;
        if (h > 0) return h;
        return targetBubbleHeight();
    }

    private void centerBubbleY() {
        if (!yAutoCentered) return;
        params.y = Math.max(0, (getScreenHeight() - targetBubbleHeight()) / 2);
    }

    private void updateBubbleOrientation() {
        if (viewBubble == null) return;
        viewBubble.setBackgroundResource(dockedRight ? R.drawable.bg_edge_handle : R.drawable.bg_edge_handle_left);
    }

    private int panelWidth() {
        int w = viewSidePanel != null ? viewSidePanel.getWidth() : 0;
        return w > 0 ? w : dp(306);
    }

    private void clampY() {
        int contentHeight = panelOpen ? viewSidePanel.getHeight() : bubbleHeight();
        if (contentHeight <= 0) contentHeight = dp(BUBBLE_SIZE_DP);
        int maxY = Math.max(0, usableHeight() - contentHeight);
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
        if (bubbleY >= 0) params.y = bubbleY;
        clampY();
        params.x = dockedX(bubbleWidth());
        updateLayout();
        savePosition();
    }

    /** Collapse the panel without animation, e.g. when the game closes. */
    private void resetPanel() {
        if (!panelOpen) return;
        panelOpen = false;
        viewSidePanel.animate().cancel();
        viewSidePanel.setAlpha(1f);
        viewSidePanel.setScaleX(1f);
        viewSidePanel.setScaleY(1f);
        viewSidePanel.setTranslationX(0f);
        viewSidePanel.setVisibility(View.GONE);
        viewBubble.setVisibility(View.VISIBLE);
        if (yAutoCentered) centerBubbleY();
        else if (bubbleY >= 0) params.y = bubbleY;
        clampY();
        updateLayout();
    }

    private void snapToEdge(int fromX) {
        // A drag can end past the bottom edge; bring the bubble back in bounds
        // before the edge animation so it always docks fully visible.
        clampY();
        int width = bubbleWidth();
        boolean right = (fromX + width / 2) >= usableWidth() / 2;
        dockedRight = right;
        updateBubbleOrientation();
        int targetX = dockedX(width);

        if (snapAnimator != null) snapAnimator.cancel();
        snapAnimator = android.animation.ValueAnimator.ofInt(fromX, targetX);
        snapAnimator.setDuration(190);
        snapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        snapAnimator.addUpdateListener(a -> {
            params.x = (int) a.getAnimatedValue();
            updateLayout();
        });
        snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                savePosition();
            }
        });
        snapAnimator.start();
    }

    private void savePosition() {
        try {
            android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("hud_right", dockedRight)
                    .putInt("hud_x", params.x);
            if (yAutoCentered) editor.remove("hud_y");
            else editor.putInt("hud_y", params.y);
            editor.apply();
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
            private boolean isDrag = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        isDrag = false;
                        touchMoved = false;
                        viewBubble.setAlpha(0.72f);
                        viewBubble.setScaleX(0.95f);
                        viewBubble.setScaleY(0.95f);
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        float dist = (float) Math.hypot(dx, dy);
                        int slop = Math.max(dp(12), android.view.ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop());
                        if (dist >= slop) {
                            isDrag = true;
                            touchMoved = true;
                        }
                        if (isDrag) {
                            yAutoCentered = false;
                            int usable = usableWidth();
                            int bw = bubbleWidth();
                            int maxY = Math.max(0, usableHeight() - bubbleHeight());
                            params.x = Math.max(0, Math.min(initialX + (int) dx, usable - bw));
                            params.y = Math.max(0, Math.min(initialY + (int) dy, maxY));
                            updateLayout();
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP: {
                        viewBubble.setAlpha(1.0f);
                        viewBubble.setScaleX(1.0f);
                        viewBubble.setScaleY(1.0f);

                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        long elapsed = System.currentTimeMillis() - downTime;
                        float dist = (float) Math.hypot(dx, dy);

                        boolean isTap = !isDrag || (elapsed < 320 && dist < dp(22));
                        boolean flingInward = elapsed < 320
                                && Math.abs(dx) > dp(36)
                                && (dockedRight ? dx < 0 : dx > 0);

                        if (isTap || flingInward) {
                            triggerHaptic();
                            if (isDrag) {
                                placeBubble();
                            }
                            openPanel();
                        } else {
                            snapToEdge(params.x);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_CANCEL:
                        viewBubble.setAlpha(1.0f);
                        viewBubble.setScaleX(1.0f);
                        viewBubble.setScaleY(1.0f);
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
        bubbleY = params.y;
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
            int maxAllowedH = usableHeight() - dp(PANEL_MARGIN_DP * 2);
            if (ph > maxAllowedH && maxAllowedH > dp(180)) {
                ViewGroup.LayoutParams lp = viewSidePanel.getLayoutParams();
                if (lp != null) {
                    lp.height = maxAllowedH;
                    viewSidePanel.setLayoutParams(lp);
                }
                View scroll = viewSidePanel.findViewById(R.id.sidepanel_scroll);
                if (scroll != null) {
                    LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) scroll.getLayoutParams();
                    slp.height = 0;
                    slp.weight = 1f;
                    scroll.setLayoutParams(slp);
                }
                ph = maxAllowedH;
            }
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
                    ViewGroup.LayoutParams lp = viewSidePanel.getLayoutParams();
                    if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        viewSidePanel.setLayoutParams(lp);
                    }
                    View scroll = viewSidePanel.findViewById(R.id.sidepanel_scroll);
                    if (scroll != null) {
                        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) scroll.getLayoutParams();
                        slp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        slp.weight = 0f;
                        scroll.setLayoutParams(slp);
                    }
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

        if (btnTypeOff != null) {
            btnTypeOff.setOnClickListener(v -> {
                triggerHaptic();
                fpsOverlayEnabled = false;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("fps_overlay_enabled", false).apply();
                applyFpsOverlayState();
                highlightHudType();
            });
        }
        if (btnTypeCompact != null) {
            btnTypeCompact.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("fps_overlay_enabled", true).apply();
                setHudType("COMPACT");
                applyFpsOverlayState();
            });
        }
        if (btnTypeDetail != null) {
            btnTypeDetail.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("fps_overlay_enabled", true).apply();
                setHudType("DETAIL");
                applyFpsOverlayState();
            });
        }
        if (btnTypeFull != null) {
            btnTypeFull.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("fps_overlay_enabled", true).apply();
                setHudType("FULL");
                applyFpsOverlayState();
            });
        }

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

        View fpsToggle = overlayRoot.findViewById(R.id.btn_fps_overlay);
        if (fpsToggle != null) {
            fpsToggle.setOnClickListener(v -> {
                triggerHaptic();
                fpsOverlayEnabled = !fpsOverlayEnabled;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("fps_overlay_enabled", fpsOverlayEnabled).apply();
                applyFpsOverlayState();
            });
        }

        if (btnQuickRam != null) {
            btnQuickRam.setOnClickListener(v -> {
                triggerHaptic();
                btnQuickRam.setText("FREED");
                btnQuickRam.setTextColor(COLOR_GREEN);
                tintQuickIcon(btnQuickRam, COLOR_GREEN);
                if (actionExecutor != null) {
                    actionExecutor.execute(() -> IpcClient.sendCommand("clean_ram"));
                }
                if (handler != null) {
                    handler.postDelayed(() -> {
                        if (btnQuickRam != null) {
                            btnQuickRam.setText("BOOST RAM");
                            btnQuickRam.setTextColor(COLOR_TEXT);
                            tintQuickIcon(btnQuickRam, COLOR_DIM);
                        }
                    }, 1200);
                }
            });
        }

        if (btnQuickDnd != null) {
            btnQuickDnd.setOnClickListener(v -> {
                triggerHaptic();
                dndEnabled = !dndEnabled;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("gaming_dnd", dndEnabled).apply();
                updateDndButton();
                if (actionExecutor != null) {
                    actionExecutor.execute(() -> IpcClient.sendCommand("set_heads_up " + (dndEnabled ? "0" : "1")));
                }
            });
        }

        if (btnQuickBypass != null) {
            btnQuickBypass.setOnClickListener(v -> {
                triggerHaptic();
                bypassEnabled = !bypassEnabled;
                updateBypassButton();
                if (actionExecutor != null) {
                    actionExecutor.execute(() -> IpcClient.sendCommand("set_bypass_charge " + (bypassEnabled ? "1" : "0")));
                }
            });
        }

        if (btnQuickGos != null) {
            btnQuickGos.setOnClickListener(v -> {
                triggerHaptic();
                if (!hasGos) {
                    android.widget.Toast.makeText(this, "ROM AOSP/GSI: Samsung GOS tidak ada (Bebas throttling)", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                gosDisabled = !gosDisabled;
                updateGosButton();
                if (actionExecutor != null) {
                    actionExecutor.execute(() -> IpcClient.sendCommand(gosDisabled ? "gos_disable" : "gos_restore"));
                }
            });
        }
    }

    private void updateDndButton() {
        if (btnQuickDnd == null) return;
        btnQuickDnd.setBackgroundResource(dndEnabled ? R.drawable.bg_segment_active : R.drawable.bg_segment_track);
        btnQuickDnd.setText(dndEnabled ? "FOCUS DND" : "NOTIFS ON");
        btnQuickDnd.setTextColor(dndEnabled ? COLOR_ACTIVE_TEXT : COLOR_DIM);
        tintQuickIcon(btnQuickDnd, dndEnabled ? COLOR_ACCENT : COLOR_DIM);
    }

    private void updateBypassButton() {
        if (btnQuickBypass == null) return;
        btnQuickBypass.setBackgroundResource(bypassEnabled ? R.drawable.bg_segment_active : R.drawable.bg_segment_track);
        btnQuickBypass.setText(bypassEnabled ? "PASSTHRU" : "BYPASS CHG");
        btnQuickBypass.setTextColor(bypassEnabled ? COLOR_ACTIVE_TEXT : COLOR_DIM);
        tintQuickIcon(btnQuickBypass, bypassEnabled ? COLOR_ACCENT : COLOR_DIM);
    }

    private void updateGosButton() {
        if (btnQuickGos == null) return;
        if (!hasGos) {
            btnQuickGos.setBackgroundResource(R.drawable.bg_segment_track);
            btnQuickGos.setText("NO GOS");
            btnQuickGos.setTextColor(COLOR_DIM);
            tintQuickIcon(btnQuickGos, COLOR_DIM);
        } else {
            btnQuickGos.setBackgroundResource(gosDisabled ? R.drawable.bg_segment_active : R.drawable.bg_segment_track);
            btnQuickGos.setText(gosDisabled ? "GOS KILL" : "GOS ON");
            btnQuickGos.setTextColor(gosDisabled ? COLOR_GREEN : COLOR_DIM);
            tintQuickIcon(btnQuickGos, gosDisabled ? COLOR_GREEN : COLOR_DIM);
        }
    }

    private void tintQuickIcon(Button btn, int color) {
        if (btn == null) return;
        btn.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private void setHudType(String type) {
        triggerHaptic();
        hudType = type;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("hud_type", type).apply();
        highlightHudType();
        applyBubbleMode();
        lastBubbleText = "";
        if (lastData != null) {
            refreshBubbleText(lastData);
        }
    }

    private void applyPreset(String preset) {
        triggerHaptic();
        activePreset = preset;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("game_preset", preset).apply();
        highlightPreset();
        final String pkg = currentGamePkg;
        if (actionExecutor != null) {
            actionExecutor.execute(() -> {
                if (pkg != null && !pkg.isEmpty()) {
                    IpcClient.sendCommand("set_game_profile " + pkg + " " + preset);
                } else {
                    IpcClient.sendCommand("apply_preset " + preset);
                }
            });
        }
    }

    private void highlightHudType() {
        boolean off = !fpsOverlayEnabled;
        boolean compact = fpsOverlayEnabled && "COMPACT".equals(hudType);
        boolean detail = fpsOverlayEnabled && "DETAIL".equals(hudType);
        boolean full = fpsOverlayEnabled && "FULL".equals(hudType);
        styleSegment(btnTypeOff, off);
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
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewBubble.getLayoutParams();
        lp.width = targetBubbleWidth();
        lp.height = targetBubbleHeight();
        viewBubble.setLayoutParams(lp);
        viewBubble.setMinimumWidth(0);
        viewBubble.setMinimumHeight(0);
        updateBubbleOrientation();

        if (!panelOpen && viewBubble.getVisibility() == View.VISIBLE) {
            viewBubble.post(() -> {
                if (!panelOpen && viewBubble != null) {
                    params.x = dockedX(targetBubbleWidth());
                    centerBubbleY();
                    clampY();
                    updateLayout();
                }
            });
        }
    }

    private void applyFpsOverlayState() {
        applyBubbleMode();
        if (dotState != null) {
            dotState.setVisibility(fpsOverlayEnabled ? View.GONE : View.VISIBLE);
        }
        if (txtBubbleFps != null) {
            txtBubbleFps.setVisibility(fpsOverlayEnabled ? View.VISIBLE : View.GONE);
        }
        if (txtTelemetryState != null) {
            txtTelemetryState.setText(fpsOverlayEnabled ? "ON" : "OFF");
            txtTelemetryState.setTextColor(fpsOverlayEnabled ? COLOR_ACCENT : COLOR_DIM);
        }
    }

    // ------------------------------------------------------------- telemetry

    private void applyHudData(SnapshotReader.SnapshotData data) {
        if (overlayRoot == null) return;
        if (data == null) {
            // Ride out a single hiccup, but never leave a stale overlay floating
            // over a non-game app when the daemon has really gone away.
            if (++missedReads >= 4) {
                resetPanel();
                overlayRoot.setVisibility(View.GONE);
            }
            return;
        }
        missedReads = 0;
        lastData = data;

        if (data.gamePreset != null && !data.gamePreset.isEmpty()
                && !data.gamePreset.equals(activePreset)) {
            activePreset = data.gamePreset;
            highlightPreset();
        }

        // Re-anchor if the screen metrics changed (rotation, split screen).
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        if (sw != lastScreenW || sh != lastScreenH) {
            lastScreenW = sw;
            lastScreenH = sh;
            if (panelOpen) {
                int pw = panelWidth();
                int maxAllowedH = usableHeight() - dp(PANEL_MARGIN_DP * 2);
                int ph = viewSidePanel.getHeight();
                if (ph > maxAllowedH && maxAllowedH > dp(180)) {
                    ViewGroup.LayoutParams lp = viewSidePanel.getLayoutParams();
                    if (lp != null) {
                        lp.height = maxAllowedH;
                        viewSidePanel.setLayoutParams(lp);
                    }
                    View scroll = viewSidePanel.findViewById(R.id.sidepanel_scroll);
                    if (scroll != null) {
                        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) scroll.getLayoutParams();
                        slp.height = 0;
                        slp.weight = 1f;
                        scroll.setLayoutParams(slp);
                    }
                    ph = maxAllowedH;
                }
                params.x = dockedRight
                        ? Math.max(0, usableWidth() - pw - dp(PANEL_MARGIN_DP))
                        : dp(PANEL_MARGIN_DP);
                params.y = clampPanelY(ph);
                updateLayout();
            } else {
                placeBubble();
            }
        }

        currentGamePkg = data.gamePkg == null ? "" : data.gamePkg;

        if (autoHide && !data.isGame) {
            resetPanel();
            overlayRoot.setVisibility(View.GONE);
            return;
        }
        overlayRoot.setVisibility(View.VISIBLE);

        // Thermal status dot on bubble (and panel header uses the same drawable).
        int dotColor;
        if (data.tempBigC >= 90) dotColor = COLOR_RED;
        else if (data.tempBigC >= 85) dotColor = COLOR_AMBER;
        else dotColor = COLOR_ACCENT;
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
        refreshBubbleText(lastData);
    }

    private void refreshBubbleText(SnapshotReader.SnapshotData data) {
        if (txtBubbleFps == null) return;
        if (data == null) {
            txtBubbleFps.setText(lastBubbleText.isEmpty() ? "--" : lastBubbleText);
            return;
        }

        String fps = data.fps > 0
                ? String.format(java.util.Locale.US, "%.0f", data.fps)
                : "--";

        int fpsInt = (int) Math.round(data.fps);
        int fpsColor;
        if (fpsInt >= 55) fpsColor = COLOR_ACCENT;
        else if (fpsInt >= 40) fpsColor = COLOR_GREEN;
        else if (fpsInt >= 25) fpsColor = COLOR_AMBER;
        else if (fpsInt > 0) fpsColor = COLOR_RED;
        else fpsColor = 0xFF8798AC;

        if ("DETAIL".equals(hudType)) {
            String temp = data.tempBigC > 0 ? (data.tempBigC + "°") : "--°";
            int tempColor = data.tempBigC >= 85 ? COLOR_RED : (data.tempBigC >= 78 ? COLOR_AMBER : 0xFF94A3B8);

            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(fps);
            sb.setSpan(new android.text.style.ForegroundColorSpan(fpsColor),
                    0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int startTemp = sb.length();
            sb.append("\n").append(temp);
            sb.setSpan(new android.text.style.ForegroundColorSpan(tempColor),
                    startTemp + 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            txtBubbleFps.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
            txtBubbleFps.setLineSpacing(0f, 1.05f);
            txtBubbleFps.setText(sb);
            lastBubbleText = fps + "\n" + temp;
        } else if ("FULL".equals(hudType)) {
            String temp = data.tempBigC > 0 ? (data.tempBigC + "°") : "--°";
            int tempColor = data.tempBigC >= 85 ? COLOR_RED : (data.tempBigC >= 78 ? COLOR_AMBER : 0xFF94A3B8);
            String gpu = data.gpuClockMhz > 0 ? (data.gpuClockMhz + "M") : "--";

            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(fps);
            sb.setSpan(new android.text.style.ForegroundColorSpan(fpsColor),
                    0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int startTemp = sb.length();
            sb.append("\n").append(temp);
            sb.setSpan(new android.text.style.ForegroundColorSpan(tempColor),
                    startTemp + 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int startGpu = sb.length();
            sb.append("\n").append(gpu);
            sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF8798AC),
                    startGpu + 1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            txtBubbleFps.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
            txtBubbleFps.setLineSpacing(0f, 1.05f);
            txtBubbleFps.setText(sb);
            lastBubbleText = fps + "\n" + temp + "\n" + gpu;
        } else {
            // COMPACT
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(fps);
            sb.setSpan(new android.text.style.ForegroundColorSpan(fpsColor),
                    0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            txtBubbleFps.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            txtBubbleFps.setLineSpacing(0f, 1.0f);
            txtBubbleFps.setText(sb);
            lastBubbleText = fps;
        }
    }

    private final java.util.Map<String, String> appNameCache = new java.util.HashMap<>();

    private String shortGameName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Game Space";
        if (appNameCache.containsKey(pkg)) {
            return appNameCache.get(pkg);
        }
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                String name = label.toString();
                appNameCache.put(pkg, name);
                return name;
            }
        } catch (Exception ignored) {}

        int dot = pkg.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        String pretty = tail.isEmpty() ? pkg : Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
        appNameCache.put(pkg, pretty);
        return pretty;
    }

    private void refreshPanel(SnapshotReader.SnapshotData data) {
        if (txtPanelGame == null) return;

        String baseGame = data.gamePkg.isEmpty() ? "Game Space" : shortGameName(data.gamePkg);
        String gameWithGos = baseGame + (data.hasGos ? (data.gosDisabled ? " · GOS OFF" : " · GOS ON") : " · AOSP");
        if (!gameWithGos.equals(lastGame)) {
            txtPanelGame.setText(gameWithGos);
            lastGame = gameWithGos;
        }

        String fps = data.fps > 0
                ? String.format(java.util.Locale.US, "%.1f FPS", data.fps)
                : "-- FPS";
        if (!fps.equals(lastMFps)) { txtMFps.setText(fps); lastMFps = fps; }

        String frameMs;
        int frameMsColor;
        if (data.fps > 0) {
            frameMs = String.format(java.util.Locale.US, "%.1f MS", 1000.0 / data.fps);
            frameMsColor = data.fps >= 50 ? COLOR_GREEN : data.fps >= 30 ? COLOR_AMBER : COLOR_RED;
        } else {
            frameMs = "NO SIGNAL";
            frameMsColor = COLOR_DIM;
        }
        if (txtMFpsStatus != null && !frameMs.equals(lastMFpsStatus)) {
            txtMFpsStatus.setText(frameMs);
            txtMFpsStatus.setTextColor(frameMsColor);
            lastMFpsStatus = frameMs;
        }

        String gpu = data.gpuClockMhz > 0
                ? String.format(java.util.Locale.US, "%d MHz · %.0f%%", data.gpuClockMhz, data.gpuBusyPct)
                : "Idle";
        if (!gpu.equals(lastMGpu)) { txtMGpu.setText(gpu); lastMGpu = gpu; }

        String cpu = data.cpuBigMhz > 0
                ? String.format(java.util.Locale.US, "%.2f GHz · %d°C", data.cpuBigMhz / 1000.0, data.tempBigC)
                : "Idle";
        if (!cpu.equals(lastMCpu)) { txtMCpu.setText(cpu); lastMCpu = cpu; }

        String net = data.tcpCc == null || data.tcpCc.isEmpty()
                ? "wlan0"
                : (data.tcpCc.equalsIgnoreCase("bbr") ? "BBR ACCEL" : data.tcpCc.toUpperCase(java.util.Locale.US));
        if (!net.equals(lastMNet)) { txtMNet.setText(net); lastMNet = net; }

        String bat = data.battCapacity > 0
                ? String.format(java.util.Locale.US, "%d%% · %.1fW%s",
                        data.battCapacity, data.battPowerW,
                        data.battBypass ? " · BYPASS" : (data.battStatus.equalsIgnoreCase("Charging") ? " · CHG" : ""))
                : "Battery";
        if (!bat.equals(lastMBat)) { txtMBat.setText(bat); lastMBat = bat; }

        if (data.battBypass != bypassEnabled) {
            bypassEnabled = data.battBypass;
            updateBypassButton();
        }

        if (data.gosDisabled != gosDisabled || data.hasGos != hasGos) {
            gosDisabled = data.gosDisabled;
            hasGos = data.hasGos;
            updateGosButton();
        }
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
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayRoot == null || windowManager == null) return;
        overlayRoot.post(() -> {
            if (panelOpen) {
                int pw = panelWidth();
                params.x = dockedRight
                        ? Math.max(0, usableWidth() - pw - dp(PANEL_MARGIN_DP))
                        : dp(PANEL_MARGIN_DP);
                params.y = clampPanelY(viewSidePanel.getHeight());
            } else {
                params.x = dockedX(targetBubbleWidth());
                centerBubbleY();
                clampY();
            }
            updateLayout();
            savePosition();
        });
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
