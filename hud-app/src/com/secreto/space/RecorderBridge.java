package com.secreto.space;

import android.content.Intent;

/**
 * Main-process cache of the MediaProjection consent token. Lives in the
 * overlay/WebUI process so OverlayService can decide instantly whether a new
 * consent is needed. The token is passed to the recorder process via Intent.
 */
public final class RecorderBridge {
    private static int sCode = 0;
    private static Intent sData = null;

    public static synchronized boolean hasConsent() { return sData != null; }
    public static synchronized void save(int code, Intent data) { sCode = code; sData = data; }
    public static synchronized Intent data() { return sData; }
    public static synchronized int code() { return sCode; }
    public static synchronized void clear() { sCode = 0; sData = null; }
}
