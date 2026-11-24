package com.secreto.space;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class IpcClient {
    private static final String TAG = "SecretoIPC";
    private static final String SOCK_PATH = "/data/local/tmp/secreto.sock";

    public static String sendCommand(String cmd) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(30000);

            OutputStream out = socket.getOutputStream();
            out.write((cmd + "\n").getBytes("UTF-8"));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            Log.w(TAG, "IPC command failed: " + cmd + " (" + e.getMessage() + ")");
            return null;
        }
    }
}
