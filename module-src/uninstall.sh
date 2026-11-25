#!/system/bin/sh

# 1. Stop daemon gracefully
if [ -S /data/local/tmp/secreto.sock ]; then
    printf "stop\n" | nc -U /data/local/tmp/secreto.sock 2>/dev/null || true
    sleep 1
fi
pkill -9 -f secretod 2>/dev/null || true

# 1.5 Wake every app the session engine slept, before the journal is deleted.
if [ -f /data/adb/secreto_space/sleeping.list ]; then
    while read -r pkg mode; do
        if [ -n "$pkg" ]; then
            cmd appops set "$pkg" RUN_IN_BACKGROUND "${mode:-default}" 2>/dev/null || true
        fi
    done < /data/adb/secreto_space/sleeping.list
fi

# 2. Force restore stock knobs
wm size reset 2>/dev/null || true
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
