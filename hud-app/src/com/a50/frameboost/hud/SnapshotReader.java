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
        public boolean isGame = false;
        public double fps = 0.0;
        public double p1LowFps = 0.0;
        public int tempBigC = 0;
        public int gpuClockMhz = 0;
        public double gpuBusyPct = 0.0;
        public int cpuBigMhz = 0;
        public String tcpCc = "westwood";
        public String battStatus = "Discharging";
        public int battCapacity = 0;
        public int battCurrentMa = 0;
        public int battVoltageMv = 0;
        public double battTempC = 0.0;
        public String battChargeType = "";
        public double battPowerW = 0.0;
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
            data.isGame = obj.optBoolean("is_game", false);
            data.fps = obj.optDouble("fps", 0.0);
            data.p1LowFps = obj.optDouble("p1_low_fps", 0.0);

            int tempMc = obj.optInt("temp_big_mc", 0);
            data.tempBigC = tempMc > 0 ? (tempMc / 1000) : 0;

            JSONObject gpu = obj.optJSONObject("gpu");
            if (gpu != null) {
                int cur = gpu.optInt("current_clock", 0);
                int min = gpu.optInt("min_clock", 260000);
                data.gpuClockMhz = (cur > 0 ? cur : min) / 1000;
                data.gpuBusyPct = gpu.optDouble("busy_pct", 0.0);
            }

            JSONObject cpu = obj.optJSONObject("cpu");
            if (cpu != null) {
                JSONObject big = cpu.optJSONObject("big");
                if (big != null) {
                    int cur = big.optInt("cur_freq", 0);
                    int min = big.optInt("min_freq", 1040000);
                    data.cpuBigMhz = (cur > 0 ? cur : min) / 1000;
                }
            }

            JSONObject net = obj.optJSONObject("net");
            if (net != null) {
                data.tcpCc = net.optString("congestion_control", "westwood");
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
            }

            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
