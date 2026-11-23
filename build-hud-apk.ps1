$ErrorActionPreference = "Stop"

$sdk = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$plat = "$sdk\platforms\android-36\android.jar"
$appDir = "C:\Users\Administrator\Documents\code\frameboost-v2\hud-app"
$buildDir = "$appDir\build"
$jdk = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin"

Write-Host "=== 1. Sync assets (webroot) ==="
New-Item -ItemType Directory -Path "$appDir\assets\webroot" -Force | Out-Null
Copy-Item "C:\Users\Administrator\Documents\code\frameboost-v2\module-src\webroot\*" "$appDir\assets\webroot\" -Force

Write-Host "=== 2. Clean & prepare build dirs ==="
Remove-Item -Path $buildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "$buildDir\gen", "$buildDir\obj" -Force | Out-Null

Write-Host "=== 3. Compile Android resources (aapt2 compile) ==="
& "$bt\aapt2.exe" compile --dir "$appDir\res" -o "$buildDir\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 4. Link resources & generate R.java (aapt2 link) ==="
& "$bt\aapt2.exe" link -I $plat `
    "$buildDir\resources.zip" `
    --manifest "$appDir\AndroidManifest.xml" `
    --min-sdk-version 26 `
    --target-sdk-version 36 `
    --java "$buildDir\gen" `
    -o "$buildDir\unaligned.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 5. Compile Java classes (javac) ==="
$javaFiles = Get-ChildItem -Path "$appDir\src\com\a50\frameboost\hud\*.java", "$buildDir\gen\com\a50\frameboost\hud\*.java" | Select-Object -ExpandProperty FullName
javac --release 17 -cp "$plat;$buildDir\gen" $javaFiles -d "$buildDir\obj"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== 6. Convert bytecode to Dalvik dex (d8) ==="
$classFiles = Get-ChildItem -Path "$buildDir\obj" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --lib $plat $classFiles --output "$buildDir"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "=== 7. Add classes.dex and assets to APK ==="
cd "$buildDir"
& "$jdk\jar.exe" -uf "$buildDir\unaligned.apk" "classes.dex"
cd "$appDir"
& "$jdk\jar.exe" -uf "$buildDir\unaligned.apk" -C "." "assets"

Write-Host "=== 8. Zipalign APK ==="
& "$bt\zipalign.exe" -p -f 4 "$buildDir\unaligned.apk" "$buildDir\FrameBoost-GameSpace-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 9. Sign APK ==="
$keystore = "$appDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\keytool.exe" -genkey -v -keystore $keystore `
        -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$finalApk = "$appDir\FrameBoost-GameSpace.apk"
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out $finalApk "$buildDir\FrameBoost-GameSpace-aligned.apk"

Write-Host "=== 10. Sync APK into module-src (ZIP payload) ==="
$moduleApk = Join-Path (Split-Path $appDir -Parent) "module-src\FrameBoost-GameSpace.apk"
Copy-Item $finalApk $moduleApk -Force

Write-Host "SUCCESS: $finalApk created!"
Get-Item $finalApk, $moduleApk | Select-Object Name, Length
