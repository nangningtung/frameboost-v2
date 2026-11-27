package com.secreto.space;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Transparent gateway: asks for mic permission when needed, then triggers the
 * MediaProjection consent. The daemon pre-grants PROJECT_MEDIA via appops, so
 * the consent dialog is skipped and this activity returns instantly — the user
 * never really leaves the game.
 */
public class RecordConsentActivity extends Activity {
    private static final int REQ_PERMISSION = 41;
    private static final int REQ_PROJECTION = 42;
    private boolean micNeeded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String audioMode = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                .getString("record_audio_mode", "game");
        micNeeded = "game_mic".equals(audioMode);
        if (micNeeded && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSION);
            return;
        }
        askProjection();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                askProjection();
            } else {
                // Fall back to game audio only; keep going.
                getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit()
                        .putString("record_audio_mode", "game").apply();
                micNeeded = false;
                askProjection();
            }
        }
    }

    private void askProjection() {
        MediaProjectionManager pm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (Build.VERSION.SDK_INT >= 35) {
            android.media.projection.MediaProjectionConfig cfg =
                    android.media.projection.MediaProjectionConfig
                            .createConfigForDefaultDisplay();
            startActivityForResult(pm.createScreenCaptureIntent(cfg), REQ_PROJECTION);
        } else {
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION);
        }
    }

    @Override
    public void onActivityResult(int code, int res, Intent data) {
        if (code == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                String preset = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                        .getString("record_preset", "std");
                String audioMode = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                        .getString("record_audio_mode", "game");
                RecorderService.requestStart(this, res, data, preset, audioMode);
            }
            finish();
        }
    }
}
