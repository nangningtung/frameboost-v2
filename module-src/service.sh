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

# Safety net for an interrupted session: put every journaled app back even if
# the daemon binary cannot start. The journal is left in place so the daemon
# can still restore clocks and finish the recovery itself.
SLEEP_LIST=/data/adb/secreto_space/sleeping.list
if [ -f "$SLEEP_LIST" ]; then
    while read -r pkg mode; do
        if [ -n "$pkg" ]; then
            cmd appops set "$pkg" RUN_IN_BACKGROUND "${mode:-default}" 2>/dev/null
        fi
    done < "$SLEEP_LIST"
    log -t SecretoSpace "woke apps from an interrupted session"
fi

# Undo an experimental render scale if the session was interrupted.
WM_JOURNAL=/data/adb/secreto_space/wm_size.journal
if [ -f "$WM_JOURNAL" ]; then
    wm size reset
    rm -f "$WM_JOURNAL"
    log -t SecretoSpace "display size reset to native after an interrupted session"
fi

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
        "$DAEMON" >> "$LOG" 2>&1 &
    fi
    sleep 10
done
