#!/system/bin/sh
MODDIR=${0%/*}

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 3
done

# Ensure daemon binary is executable
DAEMON="$MODDIR/system/bin/frameboostd"
chmod 755 "$DAEMON"

# Watchdog loop
while true; do
    if ! pidof frameboostd >/dev/null 2>&1; then
        rm -f /data/local/tmp/frameboost.sock
        "$DAEMON" >/data/local/tmp/frameboostd.log 2>&1 &
    fi
    sleep 5
done
