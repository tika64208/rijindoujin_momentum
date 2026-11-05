# 一键构建 APK（本机工具链已就绪，无需 Android Studio）
#
# 背景：AGP 会拒绝包含非 ASCII 字符的项目路径，而本仓库位于 ...\竞价分析\ 之下，
# 因此脚本先把工程同步到一个纯英文路径再构建，最后把 APK 拷回 dist\。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1              # 构建 debug
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Variant release
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Test        # 顺便跑单元测试
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -AppIdSuffix .verify
#       # 包名变成 com.example.momentum.verify，可与原版并存安装（签名不同也能装）
#
# 注意：-AppIdSuffix 只改构建副本里的 applicationId，不修改仓库源码。

param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',
    [switch]$Test,
    [string]$AppIdSuffix = '',
    [string]$Toolchain = 'F:\ai\deepseekharness\toolchain',
    [string]$BuildRoot = 'F:\ai\build\momentum'
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$repo = $PSScriptRoot
$src = Join-Path $repo 'MomentumRotation'
$dist = Join-Path $repo 'dist'

$env:JAVA_HOME = Join-Path $Toolchain 'jdk17'
$env:ANDROID_HOME = Join-Path $Toolchain 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;C:\Windows\System32;$env:ANDROID_HOME\platform-tools;$env:PATH"

$gradle = Join-Path $Toolchain 'gradle-8.10.2\bin\gradle.bat'
$initScript = Join-Path $Toolchain 'init-mirror.gradle'

if (-not (Test-Path $gradle)) { throw "Gradle not found: $gradle" }
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw "JDK not found: $env:JAVA_HOME" }
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'))) { throw "Android SDK incomplete: $env:ANDROID_HOME" }

Write-Host "== Sync sources to ASCII path: $BuildRoot" -ForegroundColor Cyan
robocopy $src $BuildRoot /E /XD build .gradle .idea /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }

# local.properties 用 ASCII 路径写入，避免中文路径进 SDK 定位
"sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Set-Content (Join-Path $BuildRoot 'local.properties') -Encoding ASCII

# 需要并存时，给构建副本的包名加后缀（源码不动）
if ($AppIdSuffix) {
    $bf = Join-Path $BuildRoot 'app\build.gradle.kts'
    $txt = Get-Content -Raw -Encoding UTF8 $bf
    if ($txt -notmatch 'applicationIdSuffix') {
        $txt = $txt -replace '(applicationId = "com\.example\.momentum")', "`$1`n        applicationIdSuffix = `"$AppIdSuffix`""
        [System.IO.File]::WriteAllText($bf, $txt, (New-Object System.Text.UTF8Encoding($false)))
    }
    Write-Host "== applicationIdSuffix: $AppIdSuffix" -ForegroundColor Cyan
}

Push-Location $BuildRoot
try {
    $tasks = @(":app:assemble$($Variant.Substring(0,1).ToUpper() + $Variant.Substring(1))")
    if ($Test) { $tasks += ':app:testDebugUnitTest' }
    Write-Host "== Build: $($tasks -join ' ')" -ForegroundColor Cyan
    & $gradle -I $initScript @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }
}
finally {
    Pop-Location
}

New-Item -ItemType Directory -Force $dist | Out-Null
$apk = Join-Path $BuildRoot "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path $apk)) { throw "Build succeeded but APK missing: $apk" }
$tag = if ($AppIdSuffix) { '-' + ($AppIdSuffix -replace '^\.', '') } else { '' }
$out = Join-Path $dist "app-$Variant$tag.apk"
Copy-Item $apk $out -Force
$hash = (Get-FileHash $out -Algorithm SHA256).Hash
$mb = [math]::Round((Get-Item $out).Length / 1MB, 2)
Write-Host "`n== Done: $out ($mb MB)" -ForegroundColor Green
Write-Host "   SHA256: $hash"
