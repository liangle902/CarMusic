[CmdletBinding()]
param([switch]$TestPhone,[switch]$Install,[string]$Serial='DEVICE_SERIAL',[string]$ExportDirectory)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$oldGoOs=$env:GOOS;$oldGoArch=$env:GOARCH;$oldCgo=$env:CGO_ENABLED
Push-Location (Join-Path $projectRoot 'engine/upstream')
try {
    $env:GOOS='linux';$env:GOARCH='arm64';$env:CGO_ENABLED='0'
    & go build -buildvcs=false -trimpath -ldflags '-s -w' -o ../../app/src/main/jniLibs/arm64-v8a/libcarmusic.so ./cmd/carmusic
    if($LASTEXITCODE -ne 0){throw 'ARM64 引擎编译失败'}
} finally {Pop-Location;$env:GOOS=$oldGoOs;$env:GOARCH=$oldGoArch;$env:CGO_ENABLED=$oldCgo}
Push-Location $projectRoot
$previousAndroidSerial=$env:ANDROID_SERIAL
try {
    if($TestPhone){$env:ANDROID_SERIAL=$Serial}
    $tasks=@(':app:assembleDebug',':app:testDebugUnitTest')
    if($TestPhone){$tasks+=':app:connectedSmokeAndroidTest'}
    & .\gradlew.bat @tasks
    if($LASTEXITCODE -ne 0){throw 'Android 构建或测试失败'}
    $apk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if($Install){& adb -s $Serial install -r $apk;if($LASTEXITCODE -ne 0){throw '覆盖安装失败'}}
    if($ExportDirectory){
        $destination=[IO.Path]::GetFullPath($ExportDirectory)
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Copy-Item -LiteralPath $apk -Destination (Join-Path $destination '星河音乐_CarMusic_v1.0.0.apk')
        $docsDestination=Join-Path $destination 'docs'
        New-Item -ItemType Directory -Path $docsDestination -Force | Out-Null
        foreach($doc in @('CarMusic_开发设计与技术规格说明书.md','IMPLEMENTATION_STATUS.md','UPSTREAM_ANDROID_FEATURE_AUDIT.md','QR_LOGIN_RESEARCH.md','THIRD_PARTY_NOTICES.md','UPSTREAM_ORIGIN.md','UI_IMPLEMENTATION_MAPPING.md')){
            Copy-Item -LiteralPath (Join-Path $projectRoot "docs/$doc") -Destination (Join-Path $destination $doc)
            Copy-Item -LiteralPath (Join-Path $projectRoot "docs/$doc") -Destination (Join-Path $docsDestination $doc)
        }
        Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $destination 'LICENSE')
        Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $destination 'README.md')
        $screenshotsDestination=Join-Path $destination 'docs/screenshots'
        New-Item -ItemType Directory -Path $screenshotsDestination -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'docs/screenshots') -Filter '*.png' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $screenshotsDestination $_.Name)}
        $validationDestination=Join-Path $destination 'validation'
        New-Item -ItemType Directory -Path $validationDestination -Force | Out-Null
        $linkedValidationDestination=Join-Path $docsDestination 'validation'
        New-Item -ItemType Directory -Path $linkedValidationDestination -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'docs/validation') -File | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $validationDestination $_.Name)
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $linkedValidationDestination $_.Name)
        }
        Get-FileHash -LiteralPath (Join-Path $destination '星河音乐_CarMusic_v1.0.0.apk') -Algorithm SHA256
    }
} finally {$env:ANDROID_SERIAL=$previousAndroidSerial;Pop-Location}
