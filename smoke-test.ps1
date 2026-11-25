# Secreto Space smoke test. Run while a game is open for full coverage.
# Usage: powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
#        powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1 -Serial RR8M50C0W8T

param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$results = New-Object System.Collections.ArrayList
function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    [void]$results.Add([pscustomobject]@{ Check = $name; Result = $(if ($ok) { "PASS" } else { "FAIL" }); Detail = $detail })
}
function Add-Skip([string]$name, [string]$detail) {
    [void]$results.Add([pscustomobject]@{ Check = $name; Result = "SKIP"; Detail = $detail })
}

# 1. Device
if (-not $Serial) {
    $Serial = (& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
}
if (-not $Serial) {
    Write-Host "No ADB device connected" -ForegroundColor Red
    exit 1
}
Add-Result "adb device" $true $Serial

# 2. Daemon process
$daemonPid = (& $adb -s $Serial shell "pidof secretod").Trim()
Add-Result "daemon running" ($daemonPid -ne "") $(if ($daemonPid) { "pid $daemonPid" } else { "not running" })

# 3. Module version
$moduleProp = & $adb -s $Serial shell su -c "head -3 /data/adb/modules/secreto_space/module.prop"
$moduleVersion = ($moduleProp | Select-String "version=").Line -replace "version=", ""
Add-Result "module v0.1.0" ($moduleVersion -eq "v0.1.0") $moduleVersion

# 4. APK version
$pkg = & $adb -s $Serial shell dumpsys package com.secreto.space
$apkVersion = (($pkg | Select-String "versionName=") | Select-Object -First 1).Line.Trim()
Add-Result "APK versionName" ($apkVersion -match "0\.1\.0") $apkVersion

# 5. Snapshot
$snapRaw = & $adb -s $Serial shell "cat /data/local/tmp/secreto-snapshot.json"
try {
    $snap = $snapRaw | ConvertFrom-Json
    Add-Result "snapshot readable" $true ("state=" + $snap.daemon_state)
} catch {
    Add-Result "snapshot readable" $false "invalid JSON"
    $snap = $null
}

if ($snap) {
    # DND setting must match the snapshot field (side panel and app share it)
    $headsUp = (& $adb -s $Serial shell "settings get global heads_up_notifications_enabled").Trim()
    $dndOk = (($headsUp -eq "0") -eq [bool]$snap.heads_up_blocked)
    Add-Result "dnd state matches snapshot" $dndOk ("setting=" + $headsUp + " blocked=" + $snap.heads_up_blocked)

    $isGame = [bool]$snap.is_game
    if ($isGame) {
        Add-Result "game detected" $true $snap.game_pkg
    } else {
        Add-Skip "game detected" "no game in foreground (partial test)"
    }

    if ($isGame) {
        # 6. FPS sanity. The first snapshot after a game comes to the foreground
        # can land before the frame baseline exists; retry once instead of
        # failing on that one-tick window.
        $fpsValue = $snap.fps
        $fpsOk = ($null -ne $fpsValue) -and ([double]$fpsValue -gt 0) -and ([double]$fpsValue -le 120)
        if (-not $fpsOk) {
            Start-Sleep -Seconds 2
            try {
                $snapRetry = ((& $adb -s $Serial shell "cat /data/local/tmp/secreto-snapshot.json") -join "`n") | ConvertFrom-Json
                $fpsValue = $snapRetry.fps
                $fpsOk = ($null -ne $fpsValue) -and ([double]$fpsValue -gt 0) -and ([double]$fpsValue -le 120)
            } catch {}
        }
        Add-Result "fps live" $fpsOk ("fps=" + $fpsValue)
        if ($null -ne $snap.p1_low_fps) {
            $p1Ok = [double]$snap.p1_low_fps -le [double]$snap.fps
            Add-Result "p1 low <= fps" $p1Ok ("p1=" + $snap.p1_low_fps)
        }

        # 7. Profile applied
        $expected = @{
            "BATTERY"     = @{ gov = "Dynamic"; gpu = 385000; cpu0 = 910000; cpu4 = 1248000 }
            "BALANCED"    = @{ gov = "Booster"; gpu = 455000; cpu0 = 910000; cpu4 = 1352000 }
            "PERFORMANCE" = @{ gov = "Booster"; gpu = 572000; cpu0 = 1053000; cpu4 = 1508000 }
            "ESPORTS"     = @{ gov = "Booster"; gpu = 683000; cpu0 = 1053000; cpu4 = 1508000 }
        }
        $preset = ([string]$snap.game_preset).ToUpper()
        if ($expected.ContainsKey($preset)) {
            $target = $expected[$preset]
            $govOk = $snap.gpu.governor -eq $target.gov
            $gpuOk = [int]$snap.gpu.min_clock -eq $target.gpu
            Add-Result "preset governor ($preset)" $govOk ("got " + $snap.gpu.governor + ", want " + $target.gov)
            Add-Result "preset gpu min ($preset)" $gpuOk ("got " + $snap.gpu.min_clock + ", want " + $target.gpu)
        }

        # 8. Overlay layer present
        $layers = & $adb -s $Serial shell "dumpsys SurfaceFlinger --list | grep -c com.secreto.space"
        $layerOk = [int]$layers -ge 1
        Add-Result "overlay layer" $layerOk ("layers=" + $layers.Trim())

        # 9. Watchdog pin
        $hudPid = (& $adb -s $Serial shell "pidof com.secreto.space").Trim()
        if ($hudPid) {
            $oom = (& $adb -s $Serial shell su -c "cat /proc/$hudPid/oom_score_adj").Trim()
            # ActivityManager can overwrite the adj while the game is coming to
            # the foreground; the daemon repins it on the next tick. Retry once
            # instead of failing on that one-second window.
            if ($oom -ne "-800") {
                Start-Sleep -Seconds 2
                $oom = (& $adb -s $Serial shell su -c "cat /proc/$hudPid/oom_score_adj").Trim()
            }
            Add-Result "hud oom_score_adj" ($oom -eq "-800") ("adj=" + $oom)
        } else {
            Add-Result "hud process" $false "not running"
        }

        # 10. Session engine
        $sessRaw = & $adb -s $Serial shell su -c "printf 'session_status' | nc -U /data/local/tmp/secreto.sock"
        try {
            $sess = $sessRaw | ConvertFrom-Json
            $sessOk = [bool]$sess.session.active -and ($sess.session.pkg -eq $snap.game_pkg)
            Add-Result "session active" $sessOk ("pkg=" + $sess.session.pkg)

            $journal = (& $adb -s $Serial shell su -c "cat /data/adb/secreto_space/sleeping.list 2>/dev/null").Trim()
            $lines = @($journal -split "`n" | Where-Object { $_.Trim() -ne "" })
            $badLines = @($lines | Where-Object { $_ -notmatch '^\S+ (allow|default|ignore|deny|foreground)$' })
            Add-Result "sleep journal" ($lines.Count -ge 1 -and $badLines.Count -eq 0) ("apps=" + $lines.Count)

            $gameSlept = @($lines | Where-Object { $_ -match [regex]::Escape([string]$snap.game_pkg) })
            Add-Result "active game not slept" ($gameSlept.Count -eq 0) ("matches=" + $gameSlept.Count)
        } catch {
            Add-Result "session active" $false "invalid JSON"
        }
    } else {
        $journalExists = (& $adb -s $Serial shell su -c "[ -f /data/adb/secreto_space/sleeping.list ] && echo yes || echo no").Trim()
        Add-Result "no stale sleep journal" ($journalExists -eq "no") ("journal=" + $journalExists)
    }
}

# 10. No apply errors in the current log
$logErrors = & $adb -s $Serial shell su -c "grep -c re-apply /data/local/tmp/secretod.log 2>/dev/null"
Add-Result "no keepalive errors" ([int]$logErrors -eq 0) ("count=" + $logErrors.Trim())

# Report
Write-Host ""
$results | Format-Table -AutoSize | Out-String -Width 140 | Write-Host
$failed = ($results | Where-Object { $_.Result -eq "FAIL" }).Count
if ($failed -eq 0) {
    Write-Host "SMOKE TEST PASS" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("SMOKE TEST FAIL: " + $failed + " check(s)") -ForegroundColor Red
    exit 1
}
