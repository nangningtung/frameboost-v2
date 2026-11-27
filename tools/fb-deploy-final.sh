#!/system/bin/sh
# Deploy final 2026-11-27: daemon rapi + webroot ui10 + APK root handshake
# + bersih-bersih temp. Pola swap sama dengan deploy-device.ps1.
MODDIR=/data/adb/modules/secreto_space
DAEMON=$MODDIR/system/bin/secretod

# 1. Stop watchdog + daemon, replace binary, restart
for p in $(ps -A -o PID,ARGS | grep "secreto_space/service.sh" | grep -v grep | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
done
sleep 1
for p in $(pidof secretod); do kill -9 "$p" 2>/dev/null; done
sleep 1
cp /data/local/tmp/secretod-new "$DAEMON"
chmod 755 "$DAEMON"
rm -f /data/local/tmp/secreto.sock
"$DAEMON" >/data/local/tmp/secretod.log 2>&1 &
sleep 2
busybox sh "$MODDIR/service.sh" >/dev/null 2>&1 &

# 2. Webroot ui10
cp /data/local/tmp/w-app.js "$MODDIR/webroot/app.js"
cp /data/local/tmp/w-index.html "$MODDIR/webroot/index.html"
chmod 644 "$MODDIR/webroot/app.js" "$MODDIR/webroot/index.html"

# 3. APK baru (root handshake)
pm install -r -d /data/local/tmp/SecretoSpace.apk

# 4. Tidy: temp files + default HUD targets
rm -f /data/local/tmp/secretod-new /data/local/tmp/app.js /data/local/tmp/index.html \
      /data/local/tmp/w-app.js /data/local/tmp/w-index.html \
      /data/local/tmp/sec_hud.so /data/local/tmp/fb-deploy-2711.sh \
      /data/local/tmp/fb-deploy-device.sh /data/local/tmp/test_screenshot.png
printf "com.mobile.legends\n" > /data/adb/secreto_space/inproc_targets.list
chmod 644 /data/adb/secreto_space/inproc_targets.list

sleep 1
echo "daemon: $(pidof secretod)"
printf "hud_inproc" | nc -U /data/local/tmp/secreto.sock
echo
