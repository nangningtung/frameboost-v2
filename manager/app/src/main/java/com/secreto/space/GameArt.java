package com.secreto.space;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves library artwork for one package.
 *
 * Community game launchers (Samsung Gaming Hub, Pixel Game Space) get banner art
 * from the store listing keyed by package name. We do the same with the Play
 * Store details page, which embeds the 512x512 icon, the 1024x500 feature
 * graphic and the graphic dominant colour in its initial data payload.
 *
 * Everything is cached on disk. Without network the installed launcher icon is
 * used, so a card always renders real artwork instead of a placeholder glyph.
 */
final class GameArt {
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Icon-only fallback is retried sooner, so a failed store fetch self-heals. */
    private static final long LOCAL_TTL_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_ICON_PX = 160;
    private static final int MAX_COVER_PX = 512;
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    // [null,2,[w,h],[null,null,"url"],null,[r,g,b,a]]
    private static final Pattern IMG_ENTRY = Pattern.compile(
            "\\[null,2,\\[(\\d+),(\\d+)\\],\\[null,null,\"(https://play-lh\\.googleusercontent\\.com/[A-Za-z0-9_\\-]+)\"\\],null,\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]");

    private GameArt() {}

    static String load(Context ctx, String pkg) {
        JSONObject out = baseResult(pkg);
        try {
            File dir = new File(ctx.getCacheDir(), "art");
            if (!dir.exists()) dir.mkdirs();
            File coverFile = new File(dir, pkg + ".cover.jpg");
            File iconFile = new File(dir, pkg + ".icon.png");
            File metaFile = new File(dir, pkg + ".json");
            File pokeFile = new File(dir, pkg + ".poke");

            JSONObject cached = metaFile.exists() ? readMeta(metaFile) : null;
            boolean fresh = false;
            if (cached != null && pokeFile.exists()) {
                long ttl = "local".equals(cached.optString("source")) ? LOCAL_TTL_MS : CACHE_TTL_MS;
                fresh = System.currentTimeMillis() - pokeFile.lastModified() < ttl;
            }
            if (fresh) {
                attachFiles(cached, coverFile, iconFile);
                return cached.toString();
            }

            String[] meta = fetchStoreMeta(pkg);

            // Offline: stale local artwork still beats a placeholder card.
            if (meta == null && (coverFile.exists() || iconFile.exists())) {
                JSONObject stale = readMeta(metaFile);
                if (stale != null) {
                    attachFiles(stale, coverFile, iconFile);
                    return stale.toString();
                }
            }

            Bitmap icon = null;
            Bitmap cover = null;
            String color = meta != null ? meta[2] : null;

            if (meta != null) {
                if (meta[0] != null) icon = downloadBitmap(meta[0] + "=s" + MAX_ICON_PX);
                if (meta[1] != null) cover = downloadBitmap(meta[1] + "=w" + MAX_COVER_PX);
            }
            if (icon == null) icon = launcherIcon(ctx, pkg);
            if (icon == null) return out.toString();

            if (cover != null) {
                out.put("kind", "cover");
                String coverUri = saveImage(coverFile, cover, false);
                if (coverUri != null) out.put("cover", coverUri);
            }
            String iconUri = saveImage(iconFile, icon, true);
            if (iconUri != null) out.put("icon", iconUri);
            if (color == null || color.isEmpty()) color = averageColorHex(icon);
            out.put("color", color);
            String source = cover != null ? "store" : (meta != null ? "store-icon" : "local");
            out.put("source", source);

            JSONObject metaOut = new JSONObject();
            metaOut.put("pkg", pkg);
            metaOut.put("kind", cover != null ? "cover" : "icon");
            metaOut.put("color", color);
            metaOut.put("source", source);
            writeAll(metaFile, metaOut.toString());
            writeAll(pokeFile, String.valueOf(System.currentTimeMillis()));
            return out.toString();
        } catch (Exception e) {
            return out.toString();
        }
    }

    /** Reads the compact metadata file, or null when it is missing/corrupt. */
    private static JSONObject readMeta(File metaFile) {
        String raw = readAll(metaFile);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject meta = new JSONObject(raw);
            if (meta.has("cover") || meta.has("icon")) return null;
            return meta;
        } catch (Exception e) {
            return null;
        }
    }

    private static void attachFiles(JSONObject meta, File coverFile, File iconFile) {
        try {
            String cover = coverFile.exists() ? dataUri(coverFile, "image/jpeg") : null;
            String icon = iconFile.exists() ? dataUri(iconFile, "image/png") : null;
            meta.put("cover", cover != null ? cover : JSONObject.NULL);
            meta.put("icon", icon != null ? icon : JSONObject.NULL);
            if (cover == null && meta.optString("kind").equals("cover")) meta.put("kind", "icon");
        } catch (Exception ignored) {}
    }

    private static JSONObject baseResult(String pkg) {
        JSONObject out = new JSONObject();
        try {
            out.put("pkg", pkg);
            out.put("kind", "icon");
            out.put("cover", JSONObject.NULL);
            out.put("icon", JSONObject.NULL);
            out.put("color", "#5A6478");
            out.put("source", "none");
        } catch (Exception ignored) {}
        return out;
    }

    /** Returns {iconUrl, coverUrl, "#RRGGBB"} or null when the listing is unreachable. */
    private static String[] fetchStoreMeta(String pkg) {
        for (String gl : new String[]{"ID", "US"}) {
            try {
                String page = "https://play.google.com/store/apps/details?id="
                        + URLEncoder.encode(pkg, "UTF-8") + "&hl=en&gl=" + gl;
                String html = fetchText(page);
                if (html == null || html.isEmpty()) continue;

                Matcher m = IMG_ENTRY.matcher(html);
                String iconUrl = null;
                String coverUrl = null;
                String color = null;
                while (m.find()) {
                    int a = Integer.parseInt(m.group(1));
                    int b = Integer.parseInt(m.group(2));
                    int w = Math.max(a, b);
                    int h = Math.min(a, b);
                    double aspect = h > 0 ? (double) w / h : 0;

                    if (iconUrl == null && w == h && w >= 256) {
                        iconUrl = m.group(3);
                    } else if (coverUrl == null && aspect >= 1.85 && aspect <= 2.25 && w >= 800) {
                        coverUrl = m.group(3);
                        color = String.format("#%02X%02X%02X",
                                Integer.parseInt(m.group(4)),
                                Integer.parseInt(m.group(5)),
                                Integer.parseInt(m.group(6)));
                    }
                }
                if (iconUrl != null || coverUrl != null) {
                    return new String[]{iconUrl, coverUrl, color};
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String fetchText(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return new String(readCapped(in), "UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Bitmap downloadBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] data = readCapped(in);
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b == null) return null;
                // Downscale to the store asset's own pixel budget, never upscale.
                int max = url.contains("=s") ? MAX_ICON_PX : MAX_COVER_PX;
                return scaleToWidth(b, max);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readCapped(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bos.size() + n > MAX_BYTES) break;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static Bitmap scaleToWidth(Bitmap src, int maxWidth) {
        if (src == null) return null;
        if (src.getWidth() <= maxWidth) return src;
        int h = Math.max(1, Math.round(src.getHeight() * (maxWidth / (float) src.getWidth())));
        return Bitmap.createScaledBitmap(src, maxWidth, h, true);
    }

    private static Bitmap launcherIcon(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b;
            if (d instanceof BitmapDrawable) {
                b = ((BitmapDrawable) d).getBitmap();
            } else {
                int w = Math.max(1, d.getIntrinsicWidth());
                int h = Math.max(1, d.getIntrinsicHeight());
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(b);
                d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                d.draw(canvas);
            }
            return scaleToWidth(b, MAX_ICON_PX);
        } catch (Exception e) {
            return null;
        }
    }

    private static String saveImage(File file, Bitmap bmp, boolean png) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : 72, fos);
            fos.flush();
        } catch (Exception e) {
            return null;
        }
        return dataUri(file, png ? "image/png" : "image/jpeg");
    }

    private static String dataUri(File file, String mime) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = readCapped(in);
            return "data:" + mime + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String averageColorHex(Bitmap bmp) {
        long r = 0, g = 0, b = 0, n = 0;
        int step = Math.max(1, bmp.getWidth() / 24);
        for (int y = 0; y < bmp.getHeight(); y += step) {
            for (int x = 0; x < bmp.getWidth(); x += step) {
                int px = bmp.getPixel(x, y);
                if ((px >>> 24) < 32) continue;
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
                n++;
            }
        }
        if (n == 0) return "#5A6478";
        return String.format("#%02X%02X%02X", r / n, g / n, b / n);
    }

    private static String readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readCapped(in), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeAll(File f, String value) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {}
    }
}
