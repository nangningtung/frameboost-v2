#!/system/bin/sh
MODDIR=${0%/*}

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 3
done

# Secreto Space is built for one device. Refuse to run anywhere else.
DEVICE="$(getprop ro.product.device)"
if [ "$DEVICE" != "a50" ]; then
    log -t SecretoSpace "device ${DEVICE:-unknown} is not a50, refusing to start"
    exit 0
fi

# Migrate leftovers from the old brand on first boot.
mkdir -p /data/adb/secreto_space
if pm list packages 2>/dev/null | grep -q "com.a50.frameboost.hud"; then
    pm uninstall com.a50.frameboost.hud >/dev/null 2>&1
fi
rm -f /data/local/tmp/frameboost.sock
rm -f /data/local/tmp/frameboost-snapshot.json

# Ensure daemon binary is executable
DAEMON="$MODDIR/system/bin/secretod"
LOG=/data/local/tmp/secretod.log
chmod 755 "$DAEMON"

# Single-instance watchdog. Do not start duplicate daemons from repeated module hooks.
while true; do
    # Rotate the log at 512 KB. The daemon owns the file descriptor, so the
    # clean way to switch files is to stop it and let the watchdog restart it.
    if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 524288 ]; then
        mv -f "$LOG" "$LOG.1"
        for p in $(pidof secretod); do
            kill "$p" 2>/dev/null
        done
        sleep 1
    fi

    if ! pidof secretod >/dev/null 2>&1; then
        rm -f /data/local/tmp/secreto.sock
        "$DAEMON" > "$LOG" 2>&1 &
    fi
    sleep 10
done
