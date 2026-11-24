$ErrorActionPreference = "Stop"

$sdk = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$plat = "$sdk\platforms\android-36\android.jar"
$appDir = "C:\Users\Administrator\Documents\code\frameboost-v2\hud-app"
$buildDir = "$appDir\build"
$jdkRoot = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jdkRoot) { throw "JDK not found in C:\Program Files\Eclipse Adoptium" }
$jdk = Join-Path $jdkRoot.FullName "bin"
$env:JAVA_HOME = $jdkRoot.FullName
$env:Path = "$jdk;$env:Path"

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
$javaFiles = Get-ChildItem -Path "$appDir\src\com\secreto\space\*.java", "$buildDir\gen\com\secreto\space\*.java" | Select-Object -ExpandProperty FullName
& "$jdk\javac.exe" --release 17 -cp "$plat;$buildDir\gen" $javaFiles -d "$buildDir\obj"
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
& "$bt\zipalign.exe" -p -f 4 "$buildDir\unaligned.apk" "$buildDir\SecretoSpace-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 9. Sign APK ==="
$keystore = "$appDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\keytool.exe" -genkey -v -keystore $keystore `
        -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$finalApk = "$appDir\SecretoSpace.apk"
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out $finalApk "$buildDir\SecretoSpace-aligned.apk"

Write-Host "=== 10. Sync APK into module-src (ZIP payload) ==="
$moduleApk = Join-Path (Split-Path $appDir -Parent) "module-src\SecretoSpace.apk"
Copy-Item $finalApk $moduleApk -Force
$privAppDir = Join-Path (Split-Path $appDir -Parent) "module-src\system\priv-app\SecretoSpace"
New-Item -ItemType Directory -Path $privAppDir -Force | Out-Null
Copy-Item $finalApk "$privAppDir\SecretoSpace.apk" -Force

Write-Host "SUCCESS: $finalApk created!"
Get-Item $finalApk, $moduleApk, "$privAppDir\SecretoSpace.apk" | Select-Object Name, Length
