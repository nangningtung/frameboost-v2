param([string]$Serial="192.168.1.3:5555", [int]$Port=9224, [string]$Expr)
$pages = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5
$wv = $pages | Where-Object { $_.url -like "*index.html*" } | Select-Object -First 1
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$ws.ConnectAsync("ws://127.0.0.1:$Port/devtools/page/$($wv.id)", $cts.Token).Wait()
$msg = @{id=1;method='Runtime.evaluate';params=@{expression=$Expr;returnByValue=$true}} | ConvertTo-Json -Compress -Depth 6
$b=[Text.Encoding]::UTF8.GetBytes($msg)
$ws.SendAsync($b,[Net.WebSockets.WebSocketMessageType]::Text,$true,$cts.Token).Wait()
$buf=New-Object byte[] 262144; $ms=New-Object IO.MemoryStream
do { $r=$ws.ReceiveAsync($buf,$cts.Token); while(-not $r.IsCompleted){Start-Sleep -Milliseconds 5}; $ms.Write($buf,0,$r.Result.Count) } while(-not $r.Result.EndOfMessage)
([Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json).result.result.value
