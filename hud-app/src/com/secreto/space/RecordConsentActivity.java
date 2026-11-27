package com.secreto.space;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible trampoline: asks for mic permission when needed, then shows the
 * system MediaProjection consent dialog (required every recording session),
 * then hands the result to RecorderService and finishes.
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
                Toast.makeText(this, "Mic permission denied, recording game audio only",
                        Toast.LENGTH_LONG).show();
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
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION);
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
            } else {
                Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }
}
