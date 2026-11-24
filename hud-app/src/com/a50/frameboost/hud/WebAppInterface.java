package com.a50.frameboost.hud;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

public class WebAppInterface {
    private final Context context;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public WebAppInterface(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public String exec(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";

        // Fast path 1: Reading snapshot JSON
        if (cmd.contains("frameboost-snapshot.json")) {
            File f = new File("/data/local/tmp/frameboost-snapshot.json");
            if (f.exists() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    return sb.toString();
                } catch (Exception ignored) {}
            }
            String snap = IpcClient.sendCommand("snapshot");
            return snap != null ? snap : "";
        }

        // Fast path 2: Socket command
        if (cmd.contains("frameboost.sock")) {
            String snap = IpcClient.sendCommand("snapshot");
            return snap != null ? snap : "";
        }

        // General shell execution via su for full root capability
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception ignored) {}

        return "";
    }

    @JavascriptInterface
    public String sendSocket(String sockCmd) {
        if (sockCmd == null || sockCmd.isEmpty()) return "";
        String res = IpcClient.sendCommand(sockCmd);
        return res != null ? res : "";
    }

    @JavascriptInterface
    public void sendSocketAsync(String sockCmd, String callbackName) {
        if (sockCmd == null || sockCmd.isEmpty() || callbackName == null || callbackName.isEmpty()) return;
        new Thread(() -> {
            String res = IpcClient.sendCommand(sockCmd);
            String json = org.json.JSONObject.quote(res != null ? res : "");
            mainHandler.post(() -> {
                if (context instanceof android.app.Activity) {
                    android.webkit.WebView webView = ((android.app.Activity) context).findViewById(R.id.webview);
                    if (webView != null) webView.evaluateJavascript(callbackName + "(" + json + ");", null);
                }
            });
        }, "frameboost-ipc").start();
    }

    @JavascriptInterface
    public void triggerHaptic() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(25, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(25);
                }
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public boolean launchGame(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @JavascriptInterface
    public boolean isHudRunning() {
        return OverlayService.isRunning();
    }

    @JavascriptInterface
    public boolean canDrawOverlays() {
        return Settings.canDrawOverlays(context);
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void setHudEnabled(boolean enable) {
        Intent intent = new Intent(context, OverlayService.class);
        if (enable) {
            if (Settings.canDrawOverlays(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else {
                requestOverlayPermission();
            }
        } else {
            context.stopService(intent);
        }
    }
}
