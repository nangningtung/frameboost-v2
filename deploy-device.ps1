# Build + deploy Secreto Space to a rooted device over ADB.
# Usage: powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1
#        powershell -ExecutionPolicy Bypass -File .\deploy-device.ps1 -SkipBuild

param(
    [switch]$SkipBuild,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$modulePath = "/data/adb/modules/secreto_space"

if (-not (Test-Path $adb)) {
    throw "adb not found at $adb"
}

Write-Host "=== 1. Build ARM daemon ==="
if (-not $SkipBuild) {
    Push-Location "$root\secretod"
    try {
        cargo build --release --target aarch64-linux-android
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    } finally {
        Pop-Location
    }
    Copy-Item "$root\secretod\target\aarch64-linux-android\release\secretod" "$root\module-src\system\bin\secretod" -Force
}

Write-Host "=== 2. Build APK + module ZIP ==="
if (-not $SkipBuild) {
    & powershell -ExecutionPolicy Bypass -File "$root\build-hud-apk.ps1" | Select-Object -Last 4
    if ($LASTEXITCODE -ne 0) { throw "APK build failed" }
    python "$root\make-zip.py" | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) { throw "ZIP build failed" }
}

Write-Host "=== 3. Find device ==="
& $adb start-server | Out-Null
if (-not $Serial) {
    $Serial = (& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
}
if (-not $Serial) { throw "No ADB device connected" }
Write-Host "Device: $Serial"

Write-Host "=== 4. Install APK ==="
& $adb -s $Serial uninstall com.a50.frameboost.hud | Out-Null
& $adb -s $Serial install -r "$root\hud-app\SecretoSpace.apk"

Write-Host "=== 5. Deploy daemon (root) ==="
& $adb -s $Serial push "$root\module-src\system\bin\secretod" /data/local/tmp/secretod-new | Out-Null

$deviceScript = @'
#!/system/bin/sh
MODDIR=/data/adb/modules/secreto_space
DAEMON=$MODDIR/system/bin/secretod

# Stop watchdog so it cannot restart the daemon mid-replace.
for p in $(ps -A -o PID,ARGS | grep "secreto_space/service.sh" | grep -v grep | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
done
sleep 1

for p in $(pidof secretod); do kill -9 "$p" 2>/dev/null; done
sleep 1

cp /data/local/tmp/secretod-new "$DAEMON"
chmod 755 "$DAEMON"
echo "installed: $(ls -l $DAEMON)"

rm -f /data/local/tmp/secreto.sock
"$DAEMON" >/data/local/tmp/secretod.log 2>&1 &
sleep 2

busybox sh "$MODDIR/service.sh" >/dev/null 2>&1 &
sleep 1

echo "daemon: $(pidof secretod)"
printf "list_games" | nc -U /data/local/tmp/secreto.sock
echo
'@

$tmp = Join-Path $env:TEMP "fb-deploy-device.sh"
Set-Content -Path $tmp -Value ($deviceScript -replace "`r`n", "`n") -NoNewline -Encoding ascii
& $adb -s $Serial push $tmp /data/local/tmp/fb-deploy-device.sh | Out-Null
& $adb -s $Serial shell su -c "sh /data/local/tmp/fb-deploy-device.sh"

Write-Host "=== 6. Relaunch app ==="
& $adb -s $Serial shell am force-stop com.secreto.space
& $adb -s $Serial shell monkey -p com.secreto.space 1 | Select-Object -Last 1

Write-Host ""
Write-Host "Done. Daemon and APK are up to date on $Serial"
