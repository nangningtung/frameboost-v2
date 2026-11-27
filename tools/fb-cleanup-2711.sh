#!/system/bin/sh
# Deploy bersihan 2026-11-27: webroot ui11 + APK + hapus sisa eksperimen HUD.
MODDIR=/data/adb/modules/secreto_space

# 1. Webroot ui11
cp /data/local/tmp/w-app.js "$MODDIR/webroot/app.js"
cp /data/local/tmp/w-index.html "$MODDIR/webroot/index.html"
chmod 644 "$MODDIR/webroot/app.js" "$MODDIR/webroot/index.html"

# 2. APK
pm install -r -d /data/local/tmp/SecretoSpace.apk

# 3. Sisa eksperimen HUD in-process
rm -f /data/adb/secreto_space/zygisk-disabled.so \
      /data/adb/secreto_space/inproc_enable \
      /data/adb/secreto_space/inproc_targets.list \
      /data/local/tmp/w-app.js /data/local/tmp/w-index.html \
      /data/local/tmp/SecretoSpace.apk /data/local/tmp/fb-final.sh \
      /data/local/tmp/hud_check.png

echo "webroot: $(ls "$MODDIR/webroot" | tr '\n' ' ')"
echo "zDIR: $(ls "$MODDIR/zygisk" 2>/dev/null || echo 'tidak ada (bersih)')"
