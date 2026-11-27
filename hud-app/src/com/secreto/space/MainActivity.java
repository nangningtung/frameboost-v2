package com.secreto.space;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private WebAppInterface bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Stock GSI light system bars
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#101114"));
        window.setNavigationBarColor(Color.parseColor("#101114"));
        window.getDecorView().setSystemUiVisibility(0);

        setContentView(R.layout.activity_main);

        // Pre-warm the MediaProjection consent token while the user is in the
        // app (not in-game). Once cached, in-game RECORD starts instantly
        // without any activity launch. Consent is pre-granted via appops, so
        // this shows no dialog.
        if (!RecorderBridge.hasConsent()) {
            startActivity(new Intent(this, RecordConsentActivity.class));
        }

        webView = findViewById(R.id.webview);
        bridge = new WebAppInterface(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        webView.setBackgroundColor(Color.parseColor("#101114"));
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.d("WebConsole", cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        startActivity(i);
                        return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });

        webView.addJavascriptInterface(bridge, "_nativeBridge");
        webView.loadUrl("file:///android_asset/webroot/index.html");

        // Auto-start HUD background service if overlay permission already granted
        if (Settings.canDrawOverlays(this)
                && getSharedPreferences("hud_prefs", MODE_PRIVATE)
                        .getBoolean("hud_master_enabled", true)) {
            if (!OverlayService.isRunning()) {
                Intent hudIntent = new Intent(this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(hudIntent);
                } else {
                    startService(hudIntent);
                }
            }
        }

        // Proactively request Superuser from KernelSU/Magisk so the app is recognized as root
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                p.waitFor();
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("if (typeof updateHudToggleState === 'function') updateHudToggleState();", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // Keep app alive in background when returning to launcher
            moveTaskToBack(true);
        }
    }
}
