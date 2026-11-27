package com.secreto.space;

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
    private static final String CHANNEL_ID = "secreto_hud_channel";
    static final String PREFS = "hud_prefs";

    private static final int COLOR_DIM = 0xFF94A3B8;
    private static final int COLOR_ACCENT = 0xFF00E5FF;
    private static final int COLOR_ACTIVE_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFF1F5F9;
    private static final int COLOR_GREEN = 0xFF00E676;
    private static final int COLOR_AMBER = 0xFFFF9100;
    private static final int COLOR_RED = 0xFFFF1744;

    private static final int BUBBLE_SIZE_DP = 56;
    private static final int BUBBLE_VISIBLE_WIDTH_DP = 36;
    private static final int HANDLE_WIDTH_DP = 40;
    private static final int HANDLE_HEIGHT_DP = 40;
    private static final int HANDLE_SMALL_WIDTH_DP = 14;
    private static final int HANDLE_SMALL_HEIGHT_DP = 48;
    private static final int EDGE_MARGIN_DP = 0;
    private static final int PANEL_MARGIN_DP = 6;

    private WindowManager windowManager;
    private View overlayRoot;
    private View viewBubble;
    private View bubbleSurface;
    private View bubbleContent;
    private View edgeAccent;
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
    private TextView txtSessTime;
    private TextView txtSessJank;
    private TextView txtSessApps;
    private TextView txtSessThermal;

    private Button btnTypeOff;
    private Button btnTypeCompact;
    private Button btnTypeDetail;
    private Button btnTypeFull;
    private Button btnPresetBat;
    private Button btnPresetBal;
    private Button btnPresetPerf;
    private Button btnPresetEsp;
    private Button btnQuickDnd;
    private Button btnQuickBypass;
    private Button btnQuickGos;
    private Button btnCaptureScreenshot;
    private Button btnCaptureRecord;
    private Button btnRecLite;
    private Button btnRecStd;
    private Button btnRecHigh;
    private Button btnRecAudio;
    private String recordPreset = "std";
    private String audioMode = "game"; // off | game | game_mic
    private int recStatusTick = 0;
    private boolean captureHiding = false;
    private boolean recordUiState = false;
    private boolean recordBusy = false;

    private WindowManager.LayoutParams params;
    private Handler handler;
    private Runnable updateRunnable;
    private java.util.concurrent.ExecutorService ioExecutor;
    // Telemetry loop runs on its own HandlerThread: background work can
    // never stall the main thread, and the tick reposts unconditionally.
    private android.os.HandlerThread teleThread;
    private android.os.Handler teleHandler;
    private volatile boolean teleChecking = false;
    private int tickCount = 0;
    private java.util.concurrent.ExecutorService actionExecutor;
    private android.animation.ValueAnimator snapAnimator;

    private boolean autoHide = true;
    private boolean fpsOverlayEnabled = true;

    /**
     * Single source of truth for the overlay lifecycle.
     *
     * <pre>
     *   HIDDEN      overlay root gone (no game, daemon away)
     *     ↕ autoHide / applyHudData
     *   DOCKED      bubble snapped to an edge
     *     ↕ drag start / end
     *   DRAGGING    bubble follows the finger, panel is closed
     *     ↕ release
     *   DOCKED      (snap or place)
     *     ↕ tap / fling inward
     *   PANEL_OPEN  side panel visible, bubble hidden
     *     ↕ close / reset
     *   DOCKED
     * </pre>
     *
     * Keeping this explicit means every visibility and position decision can
     * branch on a single field instead of guessing from scattered booleans.
     */
    enum OverlayState { HIDDEN, DOCKED, DRAGGING, PANEL_OPEN }

    private OverlayState state = OverlayState.HIDDEN;
    private boolean panelMerged = false;
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
    /** Package whose saved layout is currently applied, to avoid re-applying every tick. */
    private String appliedLayoutPkg = "";

    // Last rendered strings, to skip redundant layout passes at 2 Hz.
    private String lastBubbleText = "";
    private SnapshotReader.SnapshotData lastData;

    // Instant foreground detection (community GameSpace pattern): the overlay
    // asks the daemon for the fresh foreground package every ~400 ms, the
    // same source the session engine uses, so the panel can never disagree
    // with the daemon. Preset and session keep their own debounce there.
    private volatile String daemonFgPkg = "";
    private volatile java.util.HashSet<String> gamePkgSet = new java.util.HashSet<>();
    private volatile long gameSetFetchedAt = 0;
    private String lastMFps = "";
    private String lastMFpsStatus = "";
    private String lastMGpu = "";
    private String lastMCpu = "";
    private String lastMNet = "";
    private String lastMBat = "";
    private String lastGame = "";
    private String lastSessTime = "";
    private String lastSessJank = "";
    private String lastSessApps = "";
    private String lastSessThermal = "";

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
        recordPreset = prefs.getString("record_preset", "std");
        audioMode = prefs.getString("record_audio_mode", "game");
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
        bubbleSurface = overlayRoot.findViewById(R.id.bubble_surface);
        bubbleContent = overlayRoot.findViewById(R.id.bubble_content);
        edgeAccent = overlayRoot.findViewById(R.id.edge_accent);
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
        txtSessTime = overlayRoot.findViewById(R.id.txt_sess_time);
        txtSessJank = overlayRoot.findViewById(R.id.txt_sess_jank);
        txtSessApps = overlayRoot.findViewById(R.id.txt_sess_apps);
        txtSessThermal = overlayRoot.findViewById(R.id.txt_sess_thermal);

        btnTypeOff = overlayRoot.findViewById(R.id.btn_type_off);
        btnTypeCompact = overlayRoot.findViewById(R.id.btn_type_compact);
        btnTypeDetail = overlayRoot.findViewById(R.id.btn_type_detail);
        btnTypeFull = overlayRoot.findViewById(R.id.btn_type_full);
        btnPresetBat = overlayRoot.findViewById(R.id.btn_preset_battery);
        btnPresetBal = overlayRoot.findViewById(R.id.btn_preset_balanced);
        btnPresetPerf = overlayRoot.findViewById(R.id.btn_preset_perf);
        btnPresetEsp = overlayRoot.findViewById(R.id.btn_preset_esports);
        btnQuickDnd = overlayRoot.findViewById(R.id.btn_quick_dnd);
        btnQuickBypass = overlayRoot.findViewById(R.id.btn_quick_bypass);
        btnQuickGos = overlayRoot.findViewById(R.id.btn_quick_gos);
        btnCaptureScreenshot = overlayRoot.findViewById(R.id.btn_capture_screenshot);
        btnCaptureRecord = overlayRoot.findViewById(R.id.btn_capture_record);
        btnRecLite = overlayRoot.findViewById(R.id.btn_rec_lite);
        btnRecStd = overlayRoot.findViewById(R.id.btn_rec_std);
        btnRecHigh = overlayRoot.findViewById(R.id.btn_rec_high);
        btnRecAudio = overlayRoot.findViewById(R.id.btn_rec_audio);

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
        highlightPreset(); applyAccent();
        updateDndButton();
        updateBypassButton();
        updateRecordButton(false);
        highlightRecPreset();
        android.view.View.OnClickListener recPresetListener = v -> {
            if (recordUiState) {
                android.widget.Toast.makeText(this, "Stop recording to change quality", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            triggerHaptic();
            int id = v.getId();
            recordPreset = id == R.id.btn_rec_lite ? "lite"
                    : id == R.id.btn_rec_high ? "high" : "std";
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("record_preset", recordPreset).apply();
            highlightRecPreset();
        };
        if (btnRecLite != null) btnRecLite.setOnClickListener(recPresetListener);
        if (btnRecStd != null) btnRecStd.setOnClickListener(recPresetListener);
        if (btnRecHigh != null) btnRecHigh.setOnClickListener(recPresetListener);
        highlightAudioMode();
        if (btnRecAudio != null) {
            btnRecAudio.setOnClickListener(v -> {
                if (ScreenRecorder.isRecording()) {
                    android.widget.Toast.makeText(this, "Stop recording to change audio source",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                triggerHaptic();
                audioMode = "off".equals(audioMode) ? "game"
                        : "game".equals(audioMode) ? "game_mic" : "off";
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString("record_audio_mode", audioMode).apply();
                highlightAudioMode();
            });
        }
        // Recorder result callback: reset UI state and toast the outcome.
        ScreenRecorder.setCallback((ok, path, message) -> {
            recordBusy = false;
            if (btnCaptureRecord != null) btnCaptureRecord.setEnabled(true);
            recordUiState = false;
            updateRecordButton(false);
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        });

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
        startTelemetryLoop();
    }

    private void startTelemetryLoop() {
        teleThread = new android.os.HandlerThread("SecretoTelemetry");
        teleThread.start();
        teleHandler = new android.os.Handler(teleThread.getLooper());
        teleHandler.post(telemetryTick);
    }

    /**
     * Telemetry loop on its own HandlerThread (community GameSpace pattern).
     * Background work can never stall the main thread, and the tick reposts
     * unconditionally so the chain cannot die from a single throw or a slow
     * IPC. Game-set prefetch lives here too, never on the main thread.
     */
    private final Runnable telemetryTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!teleChecking) {
                teleChecking = true;
                SnapshotReader.SnapshotData data = null;
                try {
                    ensureGameSet();
                    // Every tick (~200 ms), ask the daemon for the fresh
                    // package. On some ROMs UsageStats events flush late, so
                    // this is the authoritative correction (same source the
                    // session engine uses; ROR does the same with dumpsys).
                    recStatusTick++;
                    if (isPanelOpen() && recStatusTick % 5 == 0) {
                        // In-app MediaProjection recorder state (replaces the
                        // daemon record_status poll; screenrecord has no audio).
                        boolean rec = ScreenRecorder.isRecording();
                        if (rec != recordUiState) {
                            recordUiState = rec;
                            handler.post(() -> updateRecordButton(rec));
                        }
                        if (rec) {
                            final long el = ScreenRecorder.elapsedSecs();
                            handler.post(() -> {
                                if (btnCaptureRecord != null) {
                                    btnCaptureRecord.setText(String.format("STOP REC %d:%02d", el / 60, el % 60));
                                }
                            });
                        }
                    }
                    {
                        String res = IpcClient.sendCommand("fg");
                        if (res != null) {
                            String pkg = new org.json.JSONObject(res).optString("pkg", "");
                            if (!pkg.equals(daemonFgPkg)) {
                                daemonFgPkg = pkg;
                                android.util.Log.d("SecretoOverlay", "daemon fg: " + pkg);
                            }
                        }
                    }
                    data = SnapshotReader.read();
                } catch (Throwable t) {
                    android.util.Log.d("SecretoOverlay", "tick error: " + t);
                } finally {
                    teleChecking = false;
                }
                final SnapshotReader.SnapshotData d = data;
                handler.post(() -> {
                    try {
                        applyHudData(d);
                    } catch (Throwable t) {
                        android.util.Log.d("SecretoOverlay", "apply error: " + t);
                    }
                });
            }
            // Unconditional repost: no exception or hiccup can break the chain.
            teleHandler.postDelayed(this, 200);
        }
    };

    private void ensureGameSet() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - gameSetFetchedAt < 60_000) return;
        gameSetFetchedAt = now;
        try {
            String res = IpcClient.sendCommand("list_games");
            if (res == null || res.isEmpty()) return;
            org.json.JSONArray arr = new org.json.JSONArray(res);
            java.util.HashSet<String> set = new java.util.HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.getJSONObject(i).optString("pkg", "");
                if (!pkg.isEmpty()) set.add(pkg);
            }
            if (!set.isEmpty()) gamePkgSet = set;
        } catch (Throwable ignored) {
        }
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
        // 44dp is the minimum comfortable tap target; the visual weight comes
        // from the slim rail and the type scale, not from a smaller window.
        return dp(44);
    }

    private int targetBubbleHeight() {
        if (!fpsOverlayEnabled) return dp(HANDLE_SMALL_HEIGHT_DP);
        if ("FULL".equals(hudType)) return dp(54);
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

        // The visible surface hugs the bezel; the inner strip stays transparent
        // so the window can keep a 44dp tap target without covering the game.
        if (bubbleSurface != null) {
            bubbleSurface.setBackgroundResource(dockedRight ? R.drawable.bg_edge_handle : R.drawable.bg_edge_handle_left);
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) bubbleSurface.getLayoutParams();
            slp.gravity = (dockedRight ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
            bubbleSurface.setLayoutParams(slp);
        }

        // The status rail always faces the screen, never the bezel.
        if (edgeAccent != null) {
            FrameLayout.LayoutParams rlp = (FrameLayout.LayoutParams) edgeAccent.getLayoutParams();
            rlp.gravity = (dockedRight ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
            rlp.setMarginStart(dockedRight ? dp(2) : 0);
            rlp.setMarginEnd(dockedRight ? 0 : dp(2));
            edgeAccent.setLayoutParams(rlp);
        }
        if (bubbleContent != null) {
            if (fpsOverlayEnabled) {
                bubbleContent.setPaddingRelative(
                        dockedRight ? dp(6) : dp(3), 0,
                        dockedRight ? dp(3) : dp(6), 0);
            } else {
                // OFF mode: keep the grip centered in the narrow handle.
                bubbleContent.setPaddingRelative(dp(3), 0, dp(3), 0);
            }
        }
    }

    private int panelWidth() {
        int w = viewSidePanel != null ? viewSidePanel.getWidth() : 0;
        return w > 0 ? w : dp(306);
    }

    private void clampY() {
        int contentHeight = isPanelOpen() ? viewSidePanel.getHeight() : bubbleHeight();
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
        enterState(OverlayState.DOCKED);
        if (bubbleY >= 0) params.y = bubbleY;
        clampY();
        bubbleY = params.y;
        params.x = dockedX(bubbleWidth());
        updateLayout();
        savePosition();
    }

    /** Convenience predicate kept for readability at call sites. */
    private boolean isPanelOpen() {
        return state == OverlayState.PANEL_OPEN;
    }

    /**
     * Centralised state transition. Every visibility change goes through here
     * so that logcat can reveal the exact lifecycle and no code path can leave
     * the views in a half-open state.
     */
    private void enterState(OverlayState next) {
        if (state == next) return;
        android.util.Log.d("SecretoOverlay",
                state + " -> " + next);
        state = next;
    }

    /** Collapse the panel without animation, e.g. when the game closes. */
    private void resetPanel() {
        if (!isPanelOpen()) return;
        enterState(OverlayState.DOCKED);
        viewSidePanel.animate().cancel();
        viewSidePanel.setAlpha(1f);
        viewSidePanel.setScaleX(1f);
        viewSidePanel.setScaleY(1f);
        viewSidePanel.setTranslationX(0f);
        viewSidePanel.setVisibility(View.GONE);
        restorePanelSize();
        viewBubble.setVisibility(View.VISIBLE);
        if (yAutoCentered) centerBubbleY();
        else if (bubbleY >= 0) params.y = bubbleY;
        clampY();
        bubbleY = params.y;
        updateLayout();
    }

    /**
     * On a short display (720p render scale, split screen) the fixed cockpit is
     * taller than the panel. Merge it into the scroll content so every control
     * stays reachable instead of being clipped below the screen edge.
     */
    private void setPanelMerged(boolean merged) {
        if (merged == panelMerged) return;
        View fixedTop = viewSidePanel.findViewById(R.id.sidepanel_fixed_top);
        View scrollContent = viewSidePanel.findViewById(R.id.sidepanel_scroll_content);
        if (fixedTop == null || scrollContent == null) return;
        ViewGroup parent = (ViewGroup) fixedTop.getParent();
        if (parent == null) return;
        parent.removeView(fixedTop);
        ViewGroup target = (ViewGroup) (merged ? scrollContent : viewSidePanel);
        target.addView(fixedTop, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panelMerged = merged;
    }

    /** Returns the panel to its natural wrap_content size. */
    private void restorePanelSize() {
        setPanelMerged(false);
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
    }

    private void snapToEdge(int fromX) {
        // A drag can end past the bottom edge; bring the bubble back in bounds
        // before the edge animation so it always docks fully visible.
        clampY();
        bubbleY = params.y;
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
                enterState(OverlayState.DOCKED);
                savePosition();
            }
        });
        snapAnimator.start();
    }

    private void savePosition() {
        try {
            if (!isPanelOpen()) bubbleY = params.y;
            android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("hud_right", dockedRight)
                    .putInt("hud_x", params.x);
            if (yAutoCentered) editor.remove("hud_y");
            else editor.putInt("hud_y", params.y);
            editor.apply();
            saveLayoutForCurrentGame();
        } catch (Exception ignored) {}
    }

    // --------------------------------------------------------- per-game layout

    private String layoutKey(String pkg, String suffix) {
        return "layout_" + pkg + "_" + suffix;
    }

    /**
     * Saves the current edge, Y and HUD mode for the running game. The global
     * keys stay in sync as a fallback for a game that has never been seen.
     */
    private void saveLayoutForCurrentGame() {
        if (currentGamePkg == null || currentGamePkg.isEmpty()) return;
        try {
            String pkg = currentGamePkg;
            // While the panel borrows the window, params.y is the panel's top.
            // The bubble's own Y is kept in bubbleY, so never overwrite the
            // saved bubble position with the panel position.
            int layoutY = (isPanelOpen() && bubbleY >= 0) ? bubbleY : params.y;
            android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(layoutKey(pkg, "right"), dockedRight)
                    .putBoolean(layoutKey(pkg, "auto"), yAutoCentered)
                    .putString(layoutKey(pkg, "type"), hudType)
                    .putBoolean(layoutKey(pkg, "fps"), fpsOverlayEnabled);
            if (yAutoCentered) editor.remove(layoutKey(pkg, "y"));
            else editor.putInt(layoutKey(pkg, "y"), layoutY);
            editor.apply();
        } catch (Exception ignored) {}
    }

    /**
     * Restores a game's saved layout. A game without saved keys inherits the
     * current global layout, so existing installs keep working unchanged.
     */
    private void applyLayoutFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        appliedLayoutPkg = pkg;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String rightKey = layoutKey(pkg, "right");
            // The panel borrows the window, so params.y is the panel's top while
            // it is open. Collapse it first to get the bubble's own position back.
            if (isPanelOpen()) resetPanel();
            if (!prefs.contains(rightKey)) {
                bubbleY = params.y;
                return;
            }

            dockedRight = prefs.getBoolean(rightKey, dockedRight);
            yAutoCentered = prefs.getBoolean(layoutKey(pkg, "auto"), yAutoCentered);
            if (yAutoCentered) {
                bubbleY = -1;
            } else {
                int savedY = prefs.getInt(layoutKey(pkg, "y"), params.y);
                bubbleY = savedY;
                params.y = savedY;
            }
            hudType = prefs.getString(layoutKey(pkg, "type"), hudType);
            fpsOverlayEnabled = prefs.getBoolean(layoutKey(pkg, "fps"), fpsOverlayEnabled);

            highlightHudType();
            applyFpsOverlayState();
            viewBubble.post(() -> {
                if (yAutoCentered) centerBubbleY();
                else if (bubbleY >= 0) params.y = bubbleY;
                clampY();
                params.x = dockedX(bubbleWidth());
                updateLayout();
            });
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
                            enterState(OverlayState.DRAGGING);
                        }
                        if (isDrag) {
                            yAutoCentered = false;
                            int usable = usableWidth();
                            int bw = bubbleWidth();
                            int maxY = Math.max(0, usableHeight() - bubbleHeight());
                            params.x = Math.max(0, Math.min(initialX + (int) dx, usable - bw));
                            params.y = Math.max(0, Math.min(initialY + (int) dy, maxY));
                            bubbleY = params.y;
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
                        enterState(OverlayState.DOCKED);
                        snapToEdge(params.x);
                        return true;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------- side panel

    private void openPanel() {
        if (isPanelOpen()) return;
        enterState(OverlayState.PANEL_OPEN);
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
                setPanelMerged(true);
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
        if (!isPanelOpen()) return;
        triggerHaptic();
        enterState(OverlayState.DOCKED);

        viewSidePanel.animate()
                .alpha(0f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .translationX(dockedRight ? dp(28) : -dp(28))
                .setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    viewSidePanel.setVisibility(View.GONE);
                    restorePanelSize();
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
                setFpsOverlayEverywhere(false);
                applyFpsOverlayState();
                highlightHudType();
                saveLayoutForCurrentGame();
            });
        }
        if (btnTypeCompact != null) {
            btnTypeCompact.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                setFpsOverlayEverywhere(true);
                setHudType("COMPACT");
                applyFpsOverlayState();
            });
        }
        if (btnTypeDetail != null) {
            btnTypeDetail.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                setFpsOverlayEverywhere(true);
                setHudType("DETAIL");
                applyFpsOverlayState();
            });
        }
        if (btnTypeFull != null) {
            btnTypeFull.setOnClickListener(v -> {
                fpsOverlayEnabled = true;
                setFpsOverlayEverywhere(true);
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
                setFpsOverlayEverywhere(fpsOverlayEnabled);
                applyFpsOverlayState();
                saveLayoutForCurrentGame();
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

        if (btnCaptureScreenshot != null) {
            btnCaptureScreenshot.setOnClickListener(v -> takeScreenshot());
        }

        if (btnCaptureRecord != null) {
            btnCaptureRecord.setOnClickListener(v -> toggleRecording());
        }
    }

    private void setAccentActiveBg(Button btn) {
        int a = accentColor();
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(10));
        gd.setColor((a & 0x00FFFFFF) | 0x1F000000);
        gd.setStroke(dp(1), a);
        btn.setBackground(gd);
    }

    private void updateDndButton() {
        if (btnQuickDnd == null) return;
        if (dndEnabled) setAccentActiveBg(btnQuickDnd);
        else btnQuickDnd.setBackgroundResource(R.drawable.bg_segment_track);
        btnQuickDnd.setText(dndEnabled ? "FOCUS DND" : "NOTIFS ON");
        btnQuickDnd.setTextColor(dndEnabled ? COLOR_ACTIVE_TEXT : COLOR_DIM);
        tintQuickIcon(btnQuickDnd, dndEnabled ? accentColor() : COLOR_DIM);
    }

    private void updateBypassButton() {
        if (btnQuickBypass == null) return;
        if (bypassEnabled) setAccentActiveBg(btnQuickBypass);
        else btnQuickBypass.setBackgroundResource(R.drawable.bg_segment_track);
        btnQuickBypass.setText(bypassEnabled ? "PASSTHRU" : "BYPASS CHG");
        btnQuickBypass.setTextColor(bypassEnabled ? COLOR_ACTIVE_TEXT : COLOR_DIM);
        tintQuickIcon(btnQuickBypass, bypassEnabled ? accentColor() : COLOR_DIM);
    }

    private void updateGosButton() {
        if (btnQuickGos == null) return;
        if (!hasGos) {
            btnQuickGos.setBackgroundResource(R.drawable.bg_segment_track);
            btnQuickGos.setText("NO GOS");
            btnQuickGos.setTextColor(COLOR_DIM);
            tintQuickIcon(btnQuickGos, COLOR_DIM);
        } else {
            if (gosDisabled) setAccentActiveBg(btnQuickGos);
            else btnQuickGos.setBackgroundResource(R.drawable.bg_segment_track);
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
        saveLayoutForCurrentGame();
    }

    private void applyPreset(String preset) {
        triggerHaptic();
        activePreset = preset;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("game_preset", preset).apply();
        highlightPreset(); applyAccent();
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

    private void takeScreenshot() {
        if (captureHiding || actionExecutor == null || viewBubble == null || viewSidePanel == null) {
            return;
        }
        captureHiding = true;
        triggerHaptic();
        final int bubbleVisibility = viewBubble.getVisibility();
        final int panelVisibility = viewSidePanel.getVisibility();
        // The capture button lives inside the panel, so both windows have to
        // leave the frame before screencap runs.
        viewBubble.setVisibility(View.INVISIBLE);
        viewSidePanel.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> actionExecutor.execute(() -> {
            String res = IpcClient.sendCommand("screenshot");
            boolean ok = res != null && res.contains("\"status\":\"ok\"");
            String message = ok ? "Screenshot saved" : "Screenshot failed";
            try {
                message = new org.json.JSONObject(res).optString("message", message);
            } catch (Exception ignored) {
            }
            final String toast = message;
            // Give SurfaceFlinger one more frame after the command returns so
            // the restored overlay is not baked into the bitmap.
            handler.postDelayed(() -> finishScreenshot(bubbleVisibility, panelVisibility, toast), 300);
        }), 300);
    }

    private void finishScreenshot(int bubbleVisibility, int panelVisibility, String message) {
        captureHiding = false;
        if (viewBubble != null) viewBubble.setVisibility(bubbleVisibility);
        if (viewSidePanel != null) viewSidePanel.setVisibility(panelVisibility);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toggleRecording() {
        if (recordBusy) return;
        triggerHaptic();
        if (ScreenRecorder.isRecording()) {
            recordBusy = true;
            if (btnCaptureRecord != null) btnCaptureRecord.setEnabled(false);
            ScreenRecorder.stop(); // result arrives via ScreenRecorder callback
        } else if (RecorderService.hasConsent()) {
            // Consent already granted: start instantly, no dialog, no
            // leaving the game.
            RecorderService.requestStart(this, 0, null, recordPreset, audioMode);
        } else {
            // First ever recording (per app process): one consent dialog.
            startActivity(new Intent(this, RecordConsentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void updateRecordButton(boolean recording) {
        if (btnCaptureRecord == null) return;
        recordUiState = recording;
        btnCaptureRecord.setText(recording ? "STOP REC 0:00" : "RECORD");
        btnCaptureRecord.setBackgroundResource(
                recording ? R.drawable.bg_segment_record : R.drawable.bg_segment_track);
        btnCaptureRecord.setTextColor(recording ? 0xFFFF8A9A : COLOR_DIM);
    }

    private void highlightAudioMode() {
        if (btnRecAudio == null) return;
        String label;
        int color;
        switch (audioMode) {
            case "off": label = "AUDIO: OFF"; color = COLOR_DIM; break;
            case "game_mic": label = "AUDIO: GAME + MIC"; color = 0xFF81C995; break;
            default: label = "AUDIO: GAME"; color = 0xFF8AB4F8; break;
        }
        btnRecAudio.setText(label);
        btnRecAudio.setTextColor(color);
    }

    /** Panel accent follows the active game preset, matching the WebUI palette. */
    private int accentColor() {
        if ("BATTERY".equals(activePreset)) return 0xFF81C995;
        if ("PERFORMANCE".equals(activePreset)) return 0xFFFFB95E;
        if ("ESPORTS".equals(activePreset)) return 0xFFC6FF00;
        return 0xFFA8C7FA; // BALANCED
    }

    private void applyAccent() {
        int a = accentColor();
        if (dotState != null && dotState.getBackground() != null) {
            android.graphics.drawable.Drawable grip = dotState.getBackground().mutate();
            grip.setColorFilter(a, android.graphics.PorterDuff.Mode.SRC_IN);
            dotState.setBackground(grip);
        }
        if (btnQuickDnd != null) tintQuickIcon(btnQuickDnd, dndEnabled ? a : COLOR_DIM);
        if (btnQuickBypass != null) tintQuickIcon(btnQuickBypass, bypassEnabled ? a : COLOR_DIM);
        if (txtTelemetryState != null) {
            txtTelemetryState.setTextColor(fpsOverlayEnabled ? a : COLOR_DIM);
        }
        highlightRecPreset();
        highlightHudType();
        highlightPresetSegments();
    }

    private void highlightRecPreset() {
        styleSegment(btnRecLite, "lite".equals(recordPreset));
        styleSegment(btnRecStd, "std".equals(recordPreset));
        styleSegment(btnRecHigh, "high".equals(recordPreset));
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
        highlightPresetSegments();
        applyAccent();
    }

    private void highlightPresetSegments() {
        styleSegment(btnPresetBat, "BATTERY".equals(activePreset));
        styleSegment(btnPresetBal, "BALANCED".equals(activePreset));
        styleSegment(btnPresetPerf, "PERFORMANCE".equals(activePreset));
        styleSegment(btnPresetEsp, "ESPORTS".equals(activePreset));
    }

    private void styleSegment(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            int a = accentColor();
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(10));
            gd.setColor((a & 0x00FFFFFF) | 0x1F000000);
            gd.setStroke(dp(1), a);
            btn.setBackground(gd);
            btn.setTextColor(COLOR_ACTIVE_TEXT);
        } else {
            btn.setBackgroundResource(R.drawable.bg_segment_track);
            btn.setTextColor(COLOR_DIM);
        }
        btn.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
    }

    private void applyBubbleMode() {
        if (viewBubble == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewBubble.getLayoutParams();
        lp.width = targetBubbleWidth();
        lp.height = targetBubbleHeight();
        viewBubble.setLayoutParams(lp);
        viewBubble.setMinimumWidth(0);
        viewBubble.setMinimumHeight(0);

        if (bubbleSurface != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) bubbleSurface.getLayoutParams();
            slp.width = fpsOverlayEnabled
                    ? dp(BUBBLE_VISIBLE_WIDTH_DP)
                    : FrameLayout.LayoutParams.MATCH_PARENT;
            slp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            bubbleSurface.setLayoutParams(slp);
        }

        updateBubbleOrientation();

        if (state != OverlayState.PANEL_OPEN && viewBubble.getVisibility() == View.VISIBLE) {
            viewBubble.post(() -> {
                if (state != OverlayState.PANEL_OPEN && viewBubble != null) {
                    params.x = dockedX(targetBubbleWidth());
                    centerBubbleY();
                    clampY();
                    updateLayout();
                }
            });
        }
    }

    /**
     * HUD on/off is a master choice: write the global key AND every stored
     * per-game fps key, otherwise a stale per-game true would turn the HUD
     * back on when the next game applies its saved layout.
     */
    private void setFpsOverlayEverywhere(boolean enabled) {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = p.edit();
        ed.putBoolean("fps_overlay_enabled", enabled);
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getKey().startsWith("layout_") && e.getKey().endsWith("_fps")) {
                ed.putBoolean(e.getKey(), enabled);
            }
        }
        ed.apply();
    }

    private void applyFpsOverlayState() {
        applyBubbleMode();
        if (dotState != null) {
            dotState.setVisibility(fpsOverlayEnabled ? View.GONE : View.VISIBLE);
        }
        if (txtBubbleFps != null) {
            txtBubbleFps.setVisibility(fpsOverlayEnabled ? View.VISIBLE : View.GONE);
        }
        if (edgeAccent != null) {
            edgeAccent.setVisibility(fpsOverlayEnabled ? View.VISIBLE : View.GONE);
        }
        if (txtTelemetryState != null) {
            txtTelemetryState.setText(fpsOverlayEnabled ? "ON" : "OFF");
            txtTelemetryState.setTextColor(fpsOverlayEnabled ? accentColor() : COLOR_DIM);
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
                enterState(OverlayState.HIDDEN);
                overlayRoot.setVisibility(View.GONE);
            }
            return;
        }
        missedReads = 0;
        lastData = data;
        updateRecordButton(data.recording);

        if (data.gamePreset != null && !data.gamePreset.isEmpty()
                && !data.gamePreset.equals(activePreset)) {
            activePreset = data.gamePreset;
            highlightPreset(); applyAccent();
        }

        // Re-anchor if the screen metrics changed (rotation, split screen).
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        if (sw != lastScreenW || sh != lastScreenH) {
            lastScreenW = sw;
            lastScreenH = sh;
            if (isPanelOpen()) {
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

        String pkg = data.gamePkg == null ? "" : data.gamePkg;
        if (!pkg.equals(currentGamePkg)) {
            currentGamePkg = pkg;
            if (pkg.isEmpty()) {
                appliedLayoutPkg = "";
            } else if (!pkg.equals(appliedLayoutPkg)) {
                applyLayoutFor(pkg);
            }
        }

        if (autoHide) {
            // Daemon fg is the authority: it decides visibility without
            // waiting for the snapshot chain. Fallback to the snapshot
            // when the daemon has not answered yet.
            boolean away;
            if (!daemonFgPkg.isEmpty()) {
                away = !gamePkgSet.contains(daemonFgPkg);
            } else {
                away = !data.isGame;
            }
            if (away) {
                if (state != OverlayState.HIDDEN) {
                    android.util.Log.d("SecretoOverlay", "instant hide (fg=" + daemonFgPkg + ")");
                }
                resetPanel();
                enterState(OverlayState.HIDDEN);
                overlayRoot.setVisibility(View.GONE);
                return;
            }
        }
        if (state == OverlayState.HIDDEN) enterState(OverlayState.DOCKED);
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
        else fpsColor = 0xFF64748B;

        // The rail is the only loud element, so the bubble stays readable
        // without a neon frame around the whole card.
        if (edgeAccent != null) {
            edgeAccent.setBackgroundTintList(ColorStateList.valueOf(fpsColor));
        }

        String temp = data.tempBigC > 0 ? (data.tempBigC + "°") : "--°";
        int tempColor = data.tempBigC >= 85 ? COLOR_RED : (data.tempBigC >= 78 ? COLOR_AMBER : 0xFF94A3B8);

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        appendSpan(sb, fps, fpsColor, 1f);
        float size;

        if ("DETAIL".equals(hudType)) {
            sb.append("\n");
            appendSpan(sb, temp, tempColor, 0.78f);
            size = 12f;
            lastBubbleText = fps + "\n" + temp;
        } else if ("FULL".equals(hudType)) {
            String gpu = data.gpuClockMhz > 0 ? (data.gpuClockMhz + "M") : "--";
            sb.append("\n");
            appendSpan(sb, temp, tempColor, 0.8f);
            sb.append("\n");
            appendSpan(sb, gpu, 0xFF8798AC, 0.72f);
            size = 10.5f;
            lastBubbleText = fps + "\n" + temp + "\n" + gpu;
        } else {
            // COMPACT: a small FPS caption makes the number self-explanatory.
            sb.append("\n");
            appendSpan(sb, "FPS", 0xFF64748B, 0.46f);
            size = 14f;
            lastBubbleText = fps;
        }

        txtBubbleFps.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
        txtBubbleFps.setLineSpacing(0f, 0.94f);
        txtBubbleFps.setText(sb);
    }

    /** Append a colored run, optionally scaled against the bubble's base size. */
    private static void appendSpan(android.text.SpannableStringBuilder sb, String text,
                                   int color, float relativeSize) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (relativeSize != 1f) {
            sb.setSpan(new android.text.style.RelativeSizeSpan(relativeSize),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

        if (txtSessTime != null) {
            String time = data.sessionActive ? formatSessionTime(data.sessionElapsed) : "--:--";
            if (!time.equals(lastSessTime)) { txtSessTime.setText(time); lastSessTime = time; }

            String jank = data.sessionJank >= 0
                    ? String.format(java.util.Locale.US, "%.0f%%", data.sessionJank)
                    : "--";
            if (!jank.equals(lastSessJank)) {
                txtSessJank.setText(jank);
                txtSessJank.setTextColor(data.sessionJank >= 30 ? COLOR_RED
                        : data.sessionJank >= 15 ? COLOR_AMBER : COLOR_GREEN);
                lastSessJank = jank;
            }

            String apps = data.sessionActive ? String.valueOf(data.sessionSlept) : "0";
            if (!apps.equals(lastSessApps)) { txtSessApps.setText(apps); lastSessApps = apps; }

            String thermal;
            int thermalColor;
            if ("CRIT".equals(data.daemonState)) {
                thermal = "LIMIT";
                thermalColor = COLOR_RED;
            } else if ("WARN".equals(data.daemonState)) {
                thermal = "HOT";
                thermalColor = COLOR_AMBER;
            } else {
                thermal = "OK";
                thermalColor = COLOR_GREEN;
            }
            if (!thermal.equals(lastSessThermal)) {
                txtSessThermal.setText(thermal);
                txtSessThermal.setTextColor(thermalColor);
                lastSessThermal = thermal;
            }
        }

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

    private String formatSessionTime(long seconds) {
        if (seconds < 3600) {
            return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(java.util.Locale.US, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
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
                    "Secreto HUD Service",
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
                .setContentTitle("Secreto HUD")
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
            if (isPanelOpen()) {
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
        if (teleHandler != null) {
            teleHandler.removeCallbacks(telemetryTick);
        }
        if (teleThread != null) {
            teleThread.quitSafely();
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
