package com.secreto.space;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Cross-process recorder status. The recorder runs in its own process
 * (:recorder) so a recording OOM can never kill the overlay/WebUI process.
 * Both sides share this file under the app data dir (same UID).
 */
public final class RecorderStatus {
    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "rec_status.json");
    }

    public static void start(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("started_at", System.currentTimeMillis());
            o.put("result", "");
            write(ctx, o.toString());
        } catch (Throwable ignored) {}
    }

    public static void finish(Context ctx, String result) {
        try {
            JSONObject o = new JSONObject();
            o.put("started_at", 0L);
            o.put("result", result == null ? "" : result);
            write(ctx, o.toString());
        } catch (Throwable ignored) {}
    }

    public static boolean recording(Context ctx) {
        try {
            JSONObject o = new JSONObject(read(ctx));
            return o.optLong("started_at", 0) != 0;
        } catch (Throwable ignored) { return false; }
    }

    public static long elapsedSecs(Context ctx) {
        try {
            JSONObject o = new JSONObject(read(ctx));
            long s = o.optLong("started_at", 0);
            if (s == 0) return 0;
            return (System.currentTimeMillis() - s) / 1000;
        } catch (Throwable ignored) { return 0; }
    }

    public static String result(Context ctx) {
        try {
            JSONObject o = new JSONObject(read(ctx));
            return o.optString("result", "");
        } catch (Throwable ignored) { return ""; }
    }

    private static String read(Context ctx) {
        try (FileInputStream fi = new FileInputStream(file(ctx))) {
            byte[] b = new byte[Math.max(1, fi.available())];
            int n = fi.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "{}";
        } catch (Throwable e) { return "{}"; }
    }

    private static void write(Context ctx, String s) {
        try (FileOutputStream fo = new FileOutputStream(file(ctx))) {
            fo.write(s.getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }
}
