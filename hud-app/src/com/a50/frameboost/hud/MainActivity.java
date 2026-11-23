package com.a50.frameboost.hud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY_PERM = 101;
    private TextView txtStatus;
    private Button btnToggle;
    private Button btnPerm;
    private CheckBox chkAutoHide;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txt_status);
        btnToggle = findViewById(R.id.btn_toggle_hud);
        btnPerm = findViewById(R.id.btn_permission);
        chkAutoHide = findViewById(R.id.chk_auto_hide);

        SharedPreferences prefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE);
        chkAutoHide.setChecked(prefs.getBoolean("auto_hide", true));

        chkAutoHide.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_hide", isChecked).apply();
        });

        btnPerm.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQ_OVERLAY_PERM);
        });

        btnToggle.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                btnPerm.setVisibility(View.VISIBLE);
                return;
            }
            if (!isRunning) {
                startHudService();
            } else {
                stopHudService();
            }
        });

        checkOverlayPermission();
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            btnPerm.setVisibility(View.VISIBLE);
            txtStatus.setText("Overlay Permission Needed!");
            txtStatus.setTextColor(0xffffb800);
        } else {
            btnPerm.setVisibility(View.GONE);
            txtStatus.setText("Permission Granted. Ready.");
            txtStatus.setTextColor(0xff00ff9d);
        }
    }

    private void startHudService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        isRunning = true;
        btnToggle.setText("STOP FLOATING HUD");
        btnToggle.setBackgroundColor(0xffff3366);
        txtStatus.setText("HUD Service: Running (Auto-mode active)");
        txtStatus.setTextColor(0xff00ff9d);
    }

    private void stopHudService() {
        Intent intent = new Intent(this, OverlayService.class);
        stopService(intent);
        isRunning = false;
        btnToggle.setText("START FLOATING HUD");
        btnToggle.setBackgroundColor(0xff00f0ff);
        txtStatus.setText("HUD Service: Stopped");
        txtStatus.setTextColor(0xffffffff);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkOverlayPermission();
    }
}
