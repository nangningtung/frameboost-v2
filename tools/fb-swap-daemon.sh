#!/system/bin/sh
# Swap daemon (pola deploy-device.ps1) tanpa menyentuh zygisk.
MODDIR=/data/adb/modules/secreto_space
DAEMON=$MODDIR/system/bin/secretod

for p in $(ps -A -o PID,ARGS | grep "secreto_space/service.sh" | grep -v grep | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
done
sleep 1
for p in $(pidof secretod); do kill -9 "$p" 2>/dev/null; done
sleep 1

cp /data/local/tmp/secretod-new "$DAEMON"
chmod 755 "$DAEMON"
rm -f /data/local/tmp/secreto.sock /data/local/tmp/secretod-new
"$DAEMON" >/data/local/tmp/secretod.log 2>&1 &
sleep 2
busybox sh "$MODDIR/service.sh" >/dev/null 2>&1 &
sleep 1

echo "installed: $(ls -l $DAEMON)"
echo "daemon: $(pidof secretod)"
printf "probe" | nc -U /data/local/tmp/secreto.sock | head -c 80
echo
