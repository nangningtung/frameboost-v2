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
    }

    public static SnapshotData read() {
        File file = new File(SNAPSHOT_PATH);
        if (!file.exists() || !file.canRead()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String jsonStr = sb.toString();
            if (jsonStr.isEmpty()) return null;

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

            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
