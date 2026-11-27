package com.secreto.space;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * In-app screen recorder with audio (replaces the daemon `screenrecord`
 * binary, which cannot capture sound). Uses MediaProjection:
 *  - video: MediaCodec AVC encoder fed by a VirtualDisplay
 *  - audio: AudioPlaybackCapture (Android 10+) captures in-game/internal
 *    audio; optional microphone mixed into the same AAC track.
 *
 * Audio modes: "off", "game" (internal audio only), "game_mic" (internal
 * audio mixed with microphone).
 */
public final class ScreenRecorder {
    private static final String TAG = "SecretoRecorder";
    private static final String MOV_DIR = "/sdcard/Movies/Secreto Space";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    /** Main-thread callback for stop results. */
    public interface Callback {
        void onFinished(boolean ok, String path, String message);
    }

    public interface Stoppable { void requestStop(); }

    private static volatile boolean sRecording = false;
    private static volatile long sStartedElapsed = 0;
    private static volatile Callback sCallback;
    private static volatile Stoppable sStopper;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public static void setCallback(Callback cb) { sCallback = cb; }

    public static boolean isRecording() { return sRecording; }

    public static long elapsedSecs() {
        return sRecording ? (SystemClock.elapsedRealtime() - sStartedElapsed) / 1000 : 0;
    }

    private static void notify(final boolean ok, final String path, final String message) {
        Callback cb = sCallback;
        if (cb != null) sMain.post(() -> cb.onFinished(ok, path, message));
    }

    /** Bitrate ladder ala ROR (same as daemon capture.rs). */
    public static int bitrateFor(String preset) {
        switch (preset == null ? "" : preset) {
            case "lite": return 6_000_000;
            case "high": return 16_000_000;
            default: return 8_000_000;
        }
    }

    /** Shorter-side target for downscale presets; 0 = native for high. */
    public static int targetShortSide(String preset) {
        switch (preset == null ? "" : preset) {
            case "lite": return 480;
            case "std": return 720;
            default: return 0;
        }
    }

    /** Requests graceful stop from any thread. */
    public static void stop() {
        Stoppable s = sStopper;
        if (s != null) s.requestStop();
    }

    public static void start(Context ctx, int resultCode, Intent projectionData,
                             String preset, String audioMode) {
        if (sRecording) {
            notify(false, null, "Recording already active");
            return;
        }
        sRecording = true;
        sStartedElapsed = SystemClock.elapsedRealtime();
        new Thread(() -> {
            String path = null;
            try {
                path = run(ctx, resultCode, projectionData, preset, audioMode);
                notify(true, path, "Recording saved");
            } catch (Throwable e) {
                Log.e(TAG, "record failed", e);
                if (path != null) new File(path).delete();
                notify(false, null, "Recording failed: " + e.getMessage());
            } finally {
                sRecording = false;
                sStopper = null;
            }
        }, "SecretoRecorder").start();
    }

    private static String run(Context ctx, int resultCode, Intent data,
                              String preset, String audioMode) throws Exception {
        MediaProjectionManager pm = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection;
        try {
            projection = pm.getMediaProjection(resultCode, data);
        } catch (Throwable e) {
            // Token revoked/expired: drop the cache so the next RECORD tap
            // asks for consent again.
            RecorderService.clearConsent();
            throw e;
        }
        if (projection == null) {
            RecorderService.clearConsent();
            throw new IllegalStateException("projection denied");
        }

        // Android requires the callback to be registered before any capture
        // starts (createVirtualDisplay / AudioPlaybackCapture).
        final MediaProjection.Callback stopCb = new MediaProjection.Callback() {
            @Override public void onStop() { stop(); }
        };
        projection.registerCallback(stopCb, sMain);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String path = MOV_DIR + "/Secreto_" + stamp + ".mp4";

        // ROR pattern: write directly to MediaStore (IS_PENDING), so the
        // file is properly indexed in the gallery without a manual scan.
        android.content.ContentResolver resolver = ctx.getContentResolver();
        android.net.Uri videoUri = null;
        android.os.ParcelFileDescriptor pfd = null;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.TITLE, "Secreto Recording " + stamp);
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, new File(path).getName());
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(android.provider.MediaStore.Video.Media.DATE_ADDED,
                    System.currentTimeMillis() / 1000);
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Secreto Space");
            cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
            videoUri = resolver.insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (videoUri != null) {
                pfd = resolver.openFileDescriptor(videoUri, "rw");
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore insert error: " + t.getMessage());
        }

        Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        display.getRealMetrics(dm);
        // ROR pattern: derive short/long edge from the aspect ratio so the
        // 480p/720p presets stay correct in both orientations, then force
        // even dimensions (H.264 requirement).
        int w = dm.widthPixels, h = dm.heightPixels;
        boolean portrait = h > w;
        int target = targetShortSide(preset);
        if (target > 0 && Math.min(w, h) > target) {
            int shortEdge = target;
            double aspect = (double) Math.max(w, h) / Math.min(w, h);
            int longEdge = (int) Math.round(shortEdge * aspect);
            w = portrait ? shortEdge : longEdge;
            h = portrait ? longEdge : shortEdge;
        }
        w = (w / 2) * 2;
        h = (h / 2) * 2;

        MediaMuxer muxer = pfd != null
                ? new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                : new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MuxerSync sync = new MuxerSync(muxer, "off".equals(audioMode) ? 1 : 2);
        long originNano = System.nanoTime();

        VideoEncoder video = new VideoEncoder(w, h, bitrateFor(preset), dm.densityDpi, projection, sync);
        final AudioEncoder audio;
        if (!"off".equals(audioMode)) {
            AudioEncoder a;
            try {
                a = new AudioEncoder(projection, "game_mic".equals(audioMode), sync, originNano);
            } catch (Throwable e) {
                Log.w(TAG, "audio unavailable, recording video only: " + e);
                a = null;
            }
            audio = a;
        } else {
            audio = null;
        }

        video.start();
        if (audio != null) audio.start();
        sStopper = () -> {
            video.requestStop();
            if (audio != null) audio.requestStop();
        };

        video.awaitEos(8000);
        if (audio != null) audio.awaitEos(4000);
        // Give drain threads time to exit their dequeue waits before we stop
        // the codecs underneath them.
        Thread.sleep(200);

        video.release();
        if (audio != null) audio.release();
        try { projection.unregisterCallback(stopCb); } catch (Exception ignored) {}
        projection.stop();
        synchronized (sync) {
            if (sync.started()) muxer.stop();
        }
        muxer.release();

        // Publish the MediaStore entry (clear IS_PENDING) and validate size.
        long outSize = 0;
        try {
            if (pfd != null) {
                outSize = pfd.getStatSize();
                pfd.close();
            }
        } catch (Throwable ignored) {}
        if (outSize == 0) {
            File f = new File(path);
            outSize = f.exists() ? f.length() : 0;
        }
        if (videoUri != null) {
            try {
                android.content.ContentValues done = new android.content.ContentValues();
                done.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(videoUri, done, null, null);
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore publish: " + t.getMessage());
            }
        }
        if (outSize < 4096) {
            if (videoUri != null) {
                try { resolver.delete(videoUri, null, null); } catch (Throwable ignored) {}
            }
            throw new IllegalStateException("output too small");
        }
        return path;
    }

    /**
     * Coordinates muxer start: MediaMuxer.start() needs every track's format
     * first, and writes must be serialized (MediaMuxer is not thread-safe).
     */
    private static final class MuxerSync {
        private final MediaMuxer muxer;
        private final int expected;
        private int added = 0;
        private boolean started = false;

        MuxerSync(MediaMuxer muxer, int expected) {
            this.muxer = muxer;
            this.expected = expected;
        }

        synchronized int addTrack(MediaFormat fmt) {
            int t = muxer.addTrack(fmt);
            added++;
            if (added >= expected && !started) muxer.start();
            started = added >= expected;
            return t;
        }

        synchronized boolean started() { return started; }

        synchronized void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
            if (started) {
                try {
                    muxer.writeSampleData(track, buf, info);
                } catch (Exception e) {
                    Log.w(TAG, "writeSampleData: " + e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ video

    private static final class VideoEncoder implements Stoppable {
        private MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final android.hardware.display.VirtualDisplay vd;
        private final MuxerSync sync;
        private volatile boolean eos = false;
        private int track = -1;

        VideoEncoder(int w, int h, int bitrate, int dpi, MediaProjection projection,
                     MuxerSync sync) throws Exception {
            this.sync = sync;
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // ROR records at 60 fps; fall back to 30 if the encoder refuses.
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e) {
                codec.release();
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            vd = projection.createVirtualDisplay("SecretoRec", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    codec.createInputSurface(), null, null);
        }

        void start() {
            codec.start();
            new Thread(this::drainLoop, "SecretoRecVideo").start();
        }

        @Override public void requestStop() {
            try { codec.signalEndOfInputStream(); } catch (Exception ignored) {}
        }

        private void drainLoop() {
            // Never let this thread die: an uncaught exception here kills the
            // whole app process (FATAL EXCEPTION) and leaves the mp4 without
            // its moov atom -> unplayable file + side panel crash.
            try {
                while (!eos) {
                    int idx;
                    try {
                        idx = codec.dequeueOutputBuffer(info, 100_000);
                    } catch (MediaCodec.CodecException e) {
                        Log.w(TAG, "video dequeue: " + e.getMessage());
                        return;
                    } catch (IllegalStateException e) {
                        return; // codec released while we waited; normal at stop
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = sync.addTrack(codec.getOutputFormat());
                    } else if (idx >= 0) {
                        ByteBuffer buf = codec.getOutputBuffer(idx);
                        if (buf != null && info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size);
                            sync.write(track, buf, info);
                        }
                        try {
                            codec.releaseOutputBuffer(idx, false);
                        } catch (IllegalStateException ignored) {}
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "video drainLoop: " + t);
            }
        }

        void awaitEos(long timeoutMs) throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!eos && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        }

        void release() {
            // Order matters (ROR pattern): wait for the drain thread to exit
            // its dequeue loop before stopping the codec, otherwise the
            // blocked dequeue throws "request cancelled".
            try { vd.release(); } catch (Exception ignored) {}
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ audio

    private static final class AudioEncoder implements Stoppable {
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final MuxerSync sync;
        private final AudioRecord game;
        private final AudioRecord mic; // null unless game_mic
        private final long originNano;
        private volatile boolean stopped = false;
        private volatile boolean eos = false;
        private int track = -1;
        private long samplesFed = 0;

        AudioEncoder(MediaProjection projection, boolean withMic, MuxerSync sync,
                     long originNano) throws Exception {
            this.sync = sync;
            this.originNano = originNano;
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioPlaybackCaptureConfiguration cfg =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            game = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(SAMPLE_RATE * 4)
                    .build();
            if (game.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("playback capture unavailable");
            }
            if (withMic) {
                mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, SAMPLE_RATE * 2);
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    game.release();
                    throw new IllegalStateException("microphone unavailable");
                }
            } else {
                mic = null;
            }
            MediaFormat encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        void start() {
            codec.start();
            game.startRecording();
            if (mic != null) mic.startRecording();
            new Thread(this::feedLoop, "SecretoRecAudio").start();
        }

        private void feedLoop() {
            // Never let this thread die (see video drainLoop note).
            try {
                runFeed();
            } catch (Throwable t) {
                Log.w(TAG, "audio feedLoop: " + t);
            }
        }

        private void runFeed() {
            int frames = SAMPLE_RATE / 10; // 100 ms per read
            short[] gamePcm = new short[frames * CHANNELS];
            short[] micPcm = new short[frames];
            while (!stopped) {
                int n = game.read(gamePcm, 0, gamePcm.length);
                if (n <= 0) continue;
                if (mic != null) {
                    int nMic = mic.read(micPcm, 0, frames);
                    if (nMic > 0) {
                        int out = Math.min(n, nMic * 2);
                        for (int i = 0; i < out; i += 2) {
                            short m = micPcm[i / 2];
                            for (int c = 0; c < 2; c++) {
                                int mix = (gamePcm[i + c] + m) / 2;
                                gamePcm[i + c] = (short) Math.max(Short.MIN_VALUE,
                                        Math.min(Short.MAX_VALUE, mix));
                            }
                        }
                        n = out;
                    }
                }
                // pts aligned to the shared wall-clock origin so A/V sync
                // matches the video Surface timestamps (also nanoTime-based).
                long ptsUs = originNano / 1000
                        + samplesFed * 1_000_000L / (SAMPLE_RATE * CHANNELS);
                samplesFed += n;
                byte[] bytes = new byte[n * 2];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().put(gamePcm, 0, n);
                int idx = codec.dequeueInputBuffer(100_000);
                if (idx < 0) continue;
                ByteBuffer in = codec.getInputBuffer(idx);
                if (in == null) continue;
                in.clear();
                in.put(bytes);
                codec.queueInputBuffer(idx, 0, bytes.length, ptsUs, 0);
                drain();
            }
            int idx = codec.dequeueInputBuffer(100_000);
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, originNano / 1000 + samplesFed,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drain();
        }

        private void drain() {
            while (true) {
                int idx;
                try {
                    idx = codec.dequeueOutputBuffer(info, 50_000);
                } catch (IllegalStateException e) {
                    return; // codec released/failed while we waited (covers CodecException)
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = sync.addTrack(codec.getOutputFormat());
                } else if (idx >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0 && track >= 0) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        sync.write(track, buf, info);
                    }
                    codec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true;
                        return;
                    }
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
            }
        }

        @Override public void requestStop() { stopped = true; }

        void awaitEos(long timeoutMs) throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!eos && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        }

        void release() {
            try { game.release(); } catch (Exception ignored) {}
            if (mic != null) { try { mic.release(); } catch (Exception ignored) {} }
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
    }
}
