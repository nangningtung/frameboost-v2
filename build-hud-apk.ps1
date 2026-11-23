$ErrorActionPreference = "Stop"

$sdk = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$plat = "$sdk\platforms\android-36\android.jar"
$appDir = "C:\Users\Administrator\Documents\code\frameboost-v2\hud-app"
$buildDir = "$appDir\build"

Write-Host "=== 1. Clean & prepare build dirs ==="
Remove-Item -Path $buildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "$buildDir\gen", "$buildDir\obj" -Force | Out-Null

Write-Host "=== 2. Compile Android resources (aapt2 compile) ==="
& "$bt\aapt2.exe" compile --dir "$appDir\res" -o "$buildDir\resources.zip"

Write-Host "=== 3. Link resources & generate R.java (aapt2 link) ==="
& "$bt\aapt2.exe" link -I $plat `
    "$buildDir\resources.zip" `
    --manifest "$appDir\AndroidManifest.xml" `
    --min-sdk-version 26 `
    --target-sdk-version 36 `
    --java "$buildDir\gen" `
    -o "$buildDir\unaligned.apk"

Write-Host "=== 4. Compile Java classes (javac) ==="
$javaFiles = Get-ChildItem -Path "$appDir\src\com\a50\frameboost\hud\*.java", "$buildDir\gen\com\a50\frameboost\hud\*.java" | Select-Object -ExpandProperty FullName
javac --release 17 -cp "$plat;$buildDir\gen" $javaFiles -d "$buildDir\obj"

Write-Host "=== 5. Convert bytecode to Dalvik dex (d8) ==="
$classFiles = Get-ChildItem -Path "$buildDir\obj" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --lib $plat $classFiles --output "$buildDir"

Write-Host "=== 6. Add classes.dex to APK ==="
# Use jar tool from JDK to add classes.dex into unaligned.apk
cd "$buildDir"
& "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\jar.exe" -uf "$buildDir\unaligned.apk" "classes.dex"

Write-Host "=== 7. Zipalign APK ==="
& "$bt\zipalign.exe" -p -f 4 "$buildDir\unaligned.apk" "$buildDir\FrameBoost-HUD-aligned.apk"

Write-Host "=== 8. Generate debug keystore & Sign APK ==="
$keystore = "$appDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\keytool.exe" -genkey -v -keystore $keystore `
        -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$finalApk = "$appDir\FrameBoost-HUD.apk"
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out $finalApk "$buildDir\FrameBoost-HUD-aligned.apk"

Write-Host "SUCCESS: $finalApk created!"
Get-Item $finalApk | Select-Object Name, Length
