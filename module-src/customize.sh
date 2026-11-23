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

# Install Game Space APK if included
if [ -f "$MODPATH/FrameBoost-GameSpace.apk" ]; then
    ui_print "- Installing FrameBoost Game Space APK..."
    pm install -r "$MODPATH/FrameBoost-GameSpace.apk" >/dev/null 2>&1
    appops set com.a50.frameboost.hud SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
fi

ui_print "- Daemon: Native Rust ARM64"
ui_print "- App: FrameBoost Game Space Launcher"
ui_print "- Overlay: Real-time In-Game Floating HUD"
ui_print "- Engine: BBR Ping Booster + Real Framestats"
ui_print "- Selesai! Buka aplikasi FrameBoost di launcher Anda"
