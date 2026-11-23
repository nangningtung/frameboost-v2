package com.a50.frameboost.hud;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class IpcClient {
    private static final String SOCK_PATH = "/data/local/tmp/frameboost.sock";

    public static String sendCommand(String cmd) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(2000);

            OutputStream out = socket.getOutputStream();
            out.write((cmd + "\n").getBytes("UTF-8"));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
                if (in.available() == 0) {
                    // Small sleep to ensure daemon finished writing all buffer
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    if (in.available() == 0) break;
                }
            }
            if (baos.size() > 0) {
                return baos.toString("UTF-8").trim();
            }
        } catch (Exception e) {
            // Fallback via root shell if direct socket is restricted
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "printf '" + cmd + "\\n' | nc -U " + SOCK_PATH});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                p.waitFor();
                return sb.toString().trim();
            } catch (Exception ignored) {}
        }
        return null;
    }
}
