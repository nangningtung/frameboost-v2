SKIPUNZIP=0

ui_print "****************************************"
ui_print "*    FrameBoost A50 - Game Space v2.3  *"
ui_print "* Native Rust Daemon + Game Space APK  *"
ui_print "****************************************"

# Set permissions
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

mkdir -p /data/adb/a50_frameboost
chmod 755 /data/adb/a50_frameboost

# Deploy Game Space APK as System Priv-App
if [ -f "$MODPATH/FrameBoost-GameSpace.apk" ]; then
    ui_print "- Installing FrameBoost Game Space as System Priv-App..."
    mkdir -p "$MODPATH/system/priv-app/FrameBoost"
    cp -f "$MODPATH/FrameBoost-GameSpace.apk" "$MODPATH/system/priv-app/FrameBoost/FrameBoost-GameSpace.apk"
    set_perm_recursive "$MODPATH/system/priv-app" 0 0 0755 0644
    pm install -r -d -g "$MODPATH/FrameBoost-GameSpace.apk" >/dev/null 2>&1
    appops set com.a50.frameboost.hud SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
    cmd deviceidle whitelist +com.a50.frameboost.hud >/dev/null 2>&1
fi

ui_print "- Daemon: Native Rust ARM64"
ui_print "- App: FrameBoost Game Space Launcher"
ui_print "- Overlay: Real-time In-Game Floating HUD"
ui_print "- Engine: BBR Ping Booster + Real Framestats"
ui_print "- Selesai! Buka aplikasi FrameBoost di launcher Anda"
