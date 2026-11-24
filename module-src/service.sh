#!/system/bin/sh
MODDIR=${0%/*}

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 3
done

# Ensure daemon binary is executable
DAEMON="$MODDIR/system/bin/frameboostd"
LOG=/data/local/tmp/frameboostd.log
chmod 755 "$DAEMON"

# Single-instance watchdog. Do not start duplicate daemons from repeated module hooks.
while true; do
    # Rotate the log at 512 KB. The daemon owns the file descriptor, so the
    # clean way to switch files is to stop it and let the watchdog restart it.
    if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 524288 ]; then
        mv -f "$LOG" "$LOG.1"
        for p in $(pidof frameboostd); do
            kill "$p" 2>/dev/null
        done
        sleep 1
    fi

    if ! pidof frameboostd >/dev/null 2>&1; then
        rm -f /data/local/tmp/frameboost.sock
        "$DAEMON" > "$LOG" 2>&1 &
    fi
    sleep 10
done
