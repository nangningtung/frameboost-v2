SKIPUNZIP=0

ui_print "****************************************"
ui_print "*    FrameBoost A50 - Game Space v2.0  *"
ui_print "* Native Rust Daemon + KernelSU WebUI  *"
ui_print "****************************************"

# Set permissions
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

mkdir -p /data/adb/a50_frameboost
chmod 755 /data/adb/a50_frameboost

ui_print "- Daemon: Native Rust ARM64 (437KB)"
ui_print "- Dashboard: Game Space Console WebUI"
ui_print "- Engine: BBR Ping Booster + Real Framestats"
ui_print "- Selesai! Buka KernelSU Manager -> Modules -> FrameBoost WebUI"
