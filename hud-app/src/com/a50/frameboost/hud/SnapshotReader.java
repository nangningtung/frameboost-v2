package com.a50.frameboost.hud;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class SnapshotReader {
    private static final String SNAPSHOT_PATH = "/data/local/tmp/frameboost-snapshot.json";

    public static class SnapshotData {
        public String daemonState = "IDLE";
        public String gamePkg = "";
        public String gamePreset = "";
        public boolean isGame = false;
        public double fps = 0.0;
        public double p1LowFps = 0.0;
        public int tempBigC = 0;
        public int gpuClockMhz = 0;
        public double gpuBusyPct = 0.0;
        public int cpuBigMhz = 0;
        public String tcpCc = "";
        public String battStatus = "Discharging";
        public int battCapacity = 0;
        public int battCurrentMa = 0;
        public int battVoltageMv = 0;
        public double battTempC = 0.0;
        public String battChargeType = "";
        public double battPowerW = 0.0;
        public boolean battBypass = false;
        public boolean gosDisabled = false;
        public boolean hasGos = false;
    }

    public static SnapshotData read() {
        String jsonStr = null;
        File file = new File(SNAPSHOT_PATH);
        if (file.exists() && file.canRead()) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                jsonStr = sb.toString();
            } catch (Exception ignored) {}
        }

        if (jsonStr == null || jsonStr.isEmpty()) {
            jsonStr = IpcClient.sendCommand("snapshot");
        }

        if (jsonStr == null || jsonStr.isEmpty()) return null;

        try {
            JSONObject obj = new JSONObject(jsonStr);
            SnapshotData data = new SnapshotData();
            data.daemonState = obj.optString("daemon_state", "IDLE");
            data.gamePkg = obj.optString("game_pkg", "");
            data.gamePreset = obj.optString("game_preset", "");
            data.isGame = obj.optBoolean("is_game", false);
            data.fps = obj.optDouble("fps", 0.0);
            data.p1LowFps = obj.optDouble("p1_low_fps", 0.0);
            data.gosDisabled = obj.optBoolean("gos_disabled", false);
            data.hasGos = obj.optBoolean("has_gos", false);

            int tempMc = obj.optInt("temp_big_mc", 0);
            data.tempBigC = tempMc > 0 ? (tempMc / 1000) : 0;

            // Missing readings stay 0/empty so the UI can show "N/A" instead of
            // inventing a plausible-looking clock or governor.
            JSONObject gpu = obj.optJSONObject("gpu");
            if (gpu != null) {
                data.gpuClockMhz = Math.max(0, gpu.optInt("current_clock", 0)) / 1000;
                data.gpuBusyPct = gpu.optDouble("busy_pct", 0.0);
            }

            JSONObject cpu = obj.optJSONObject("cpu");
            if (cpu != null) {
                JSONObject big = cpu.optJSONObject("big");
                if (big != null) {
                    data.cpuBigMhz = Math.max(0, big.optInt("cur_freq", 0)) / 1000;
                }
            }

            JSONObject net = obj.optJSONObject("net");
            if (net != null) {
                data.tcpCc = net.optString("congestion_control", "");
            }

            JSONObject batt = obj.optJSONObject("battery");
            if (batt != null) {
                data.battStatus = batt.optString("status", "Discharging");
                data.battCapacity = batt.optInt("capacity_pct", 0);
                data.battCurrentMa = batt.optInt("current_now_ma", 0);
                data.battVoltageMv = batt.optInt("voltage_now_mv", 0);
                data.battTempC = batt.optDouble("temp_c", 0.0);
                data.battChargeType = batt.optString("charge_type", "");
                data.battPowerW = batt.optDouble("power_w", 0.0);
                data.battBypass = batt.optBoolean("bypass_charging", false);
            }

            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
