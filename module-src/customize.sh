SKIPUNZIP=0

DEVICE="$(getprop ro.product.device)"
MODEL="$(getprop ro.product.model)"

if [ "$DEVICE" != "a50" ]; then
    abort "! Secreto Space is built for Galaxy A50 only (detected ${MODEL:-unknown} / ${DEVICE:-unknown}). Installation aborted."
fi

ui_print "****************************************"
ui_print "*         Secreto Space v0.1.0         *"
ui_print "*    Galaxy A50 - Exynos 9610 only     *"
ui_print "****************************************"

# Set permissions
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# Data dir + default config (HUD in-process eksperimen tidak lagi di payload;
# kode tetap ada di inproc-hud/ untuk penggunaan manual).
mkdir -p /data/adb/secreto_space

# Migrate data from FrameBoost 2.x, then retire the old module.
OLD_DATA=/data/adb/a50_frameboost
NEW_DATA=/data/adb/secreto_space
mkdir -p "$NEW_DATA"
chmod 755 "$NEW_DATA"

if [ -d "$OLD_DATA" ]; then
    ui_print "- Migrating data from the old module"
    for f in game_profiles.json stock_snapshot.json config.sh games.list custom_games.list auto_games.list disabled_games.list; do
        if [ -f "$OLD_DATA/$f" ] && [ ! -f "$NEW_DATA/$f" ]; then
            cp -f "$OLD_DATA/$f" "$NEW_DATA/$f"
        fi
    done
    rm -rf "$OLD_DATA"
fi

# Deploy APK as system priv-app
if [ -f "$MODPATH/SecretoSpace.apk" ]; then
    ui_print "- Installing Secreto Space as system priv-app"
    mkdir -p "$MODPATH/system/priv-app/SecretoSpace"
    cp -f "$MODPATH/SecretoSpace.apk" "$MODPATH/system/priv-app/SecretoSpace/SecretoSpace.apk"
    set_perm_recursive "$MODPATH/system/priv-app" 0 0 0755 0644
    pm uninstall com.a50.frameboost.hud >/dev/null 2>&1
    pm install -r -d -g "$MODPATH/SecretoSpace.apk" >/dev/null 2>&1
    appops set com.secreto.space SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
    cmd deviceidle whitelist +com.secreto.space >/dev/null 2>&1
fi

# Retire the old brand: restore stock, then remove the module directory.
if [ -f /data/adb/modules/a50_frameboost/uninstall.sh ]; then
    sh /data/adb/modules/a50_frameboost/uninstall.sh >/dev/null 2>&1
fi
if [ -d /data/adb/modules/a50_frameboost ]; then
    rm -rf /data/adb/modules/a50_frameboost
fi
rm -f /data/local/tmp/frameboost.sock
rm -f /data/local/tmp/frameboost-snapshot.json
rm -f /data/local/tmp/frameboostd.log

ui_print "- Daemon: native Rust ARM64"
ui_print "- App: Secreto Space launcher"
ui_print "- Overlay: real-time in-game HUD"
ui_print "- Done. Open Secreto Space from your launcher"
