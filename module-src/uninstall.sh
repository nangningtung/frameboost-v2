#!/system/bin/sh

# 1. Stop daemon gracefully
if [ -S /data/local/tmp/secreto.sock ]; then
    printf "stop\n" | nc -U /data/local/tmp/secreto.sock 2>/dev/null || true
    sleep 1
fi
pkill -9 -f secretod 2>/dev/null || true

# 2. Force restore stock knobs
echo "Interactive" > /sys/kernel/gpu/gpu_governor 2>/dev/null || true
echo "260000" > /sys/kernel/gpu/gpu_min_clock 2>/dev/null || true
echo "702000" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null || true
echo "1040000" > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq 2>/dev/null || true
echo "westwood" > /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || true

# 3. Clean runtime files
rm -f /data/local/tmp/secreto.sock
rm -f /data/local/tmp/secreto-snapshot.json
rm -f /data/local/tmp/secretod.log
rm -rf /data/adb/secreto_space
