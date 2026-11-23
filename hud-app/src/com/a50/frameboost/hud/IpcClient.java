package com.a50.frameboost.hud;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.InputStream;
import java.io.OutputStream;

public class IpcClient {
    private static final String SOCK_PATH = "/data/local/tmp/frameboost.sock";

    public static String sendCommand(String cmd) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(1500);

            OutputStream out = socket.getOutputStream();
            out.write((cmd + "\n").getBytes());
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            int read = in.read(buf);
            if (read > 0) {
                return new String(buf, 0, read).trim();
            }
        } catch (Exception e) {
            // Fallback via shell if socket access is restricted by Android sandbox
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "printf '" + cmd + "\\n' | nc -U " + SOCK_PATH});
                p.waitFor();
            } catch (Exception ignored) {}
        }
        return null;
    }
}
