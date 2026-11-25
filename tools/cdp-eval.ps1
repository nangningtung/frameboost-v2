param(
  [Parameter(Mandatory = $true)][string]$Expr,
  [int]$TimeoutSec = 20,
  [string]$Serial = "192.168.1.3:5555"
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Refresh: find current WebView devtools socket
$unix = (& $adb -s $Serial shell cat /proc/net/unix 2>&1) -join "`n"
$socket = ([regex]::Match($unix, "@webview_devtools_remote_(\d+)")).Groups[1].Value
if (-not $socket) { throw "no webview devtools socket found" }

& $adb -s $Serial forward tcp:9222 "localabstract:webview_devtools_remote_$socket" | Out-Null
Start-Sleep -Milliseconds 400

$pages = Invoke-RestMethod -Uri "http://127.0.0.1:9222/json" -TimeoutSec 5
$wv = $pages | Where-Object { $_.url -like "*index.html*" } | Select-Object -First 1
if (-not $wv) { throw "webview page not found" }

$wsUrl = "ws://127.0.0.1:9222/devtools/page/$($wv.id)"
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$cts.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec))
$connect = $ws.ConnectAsync($wsUrl, $cts.Token)
while (-not $connect.IsCompleted) { Start-Sleep -Milliseconds 50 }
if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { throw "WS not open: $($ws.State)" }

# JSON-encode the expression safely
$escaped = $Expr -replace '\\', '\\' -replace '"', '\"' -replace "`r", '' -replace "`n", '\n'
$msg = '{"id":1,"method":"Runtime.evaluate","params":{"expression":"' + $escaped + '","returnByValue":true}}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
$send = $ws.SendAsync($bytes, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token)
while (-not $send.IsCompleted) { Start-Sleep -Milliseconds 50 }

# Read (may need multiple frames)
$recvBuf = New-Object byte[] 65536
$fullResp = ""
do {
  $recv = $ws.ReceiveAsync($recvBuf, $cts.Token)
  while (-not $recv.IsCompleted) { Start-Sleep -Milliseconds 50 }
  $fullResp += [System.Text.Encoding]::UTF8.GetString($recvBuf, 0, $recv.Result.Count)
} while ($recv.Result.EndOfMessage -eq $false)

$ws.Dispose()
$cts.Dispose()

# Extract the value
try {
  $obj = $fullResp | ConvertFrom-Json
  if ($obj.result.result.value) { $obj.result.result.value }
  elseif ($obj.result.result.description) { $obj.result.result.description }
  else { $fullResp }
} catch {
  $fullResp
}
