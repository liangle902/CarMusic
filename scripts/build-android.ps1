[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$KeyStore,
    [Parameter(Mandatory=$true)][string]$KeyAlias,
    [string]$CertificateLineage,
    [string]$PreviousKeyStore,
    [string]$PreviousKeyAlias,
    [switch]$Install,
    [string]$Serial,
    [string]$ExportDirectory
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not (Test-Path -LiteralPath $KeyStore)){throw '签名文件不存在'}
if(-not $env:CARMUSIC_SIGNING_PASSWORD){throw '请在进程环境变量 CARMUSIC_SIGNING_PASSWORD 中提供签名口令'}
if($Install -and -not $Serial){throw '安装时请通过 -Serial 指定目标设备'}
if($CertificateLineage -and (-not $PreviousKeyStore -or -not $PreviousKeyAlias -or -not $env:CARMUSIC_PREVIOUS_SIGNING_PASSWORD)){throw '迁移签名需要原签名文件、别名和 CARMUSIC_PREVIOUS_SIGNING_PASSWORD'}
$sdkRoot=$env:ANDROID_HOME
if(-not $sdkRoot){$sdkRoot=$env:ANDROID_SDK_ROOT}
if(-not $sdkRoot -and (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties'))){
    $sdkEntry=Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') | Where-Object {$_ -match '^sdk\.dir='} | Select-Object -First 1
    if($sdkEntry){$sdkRoot=$sdkEntry.Substring(8).Replace('\\','\').Replace('\:',':')}
}
if(-not $sdkRoot){throw '请设置 ANDROID_HOME 或 local.properties 中的 sdk.dir'}
$buildTools=Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory | Where-Object {$_.Name -match '^\d+\.\d+\.\d+$'} | Sort-Object {[version]$_.Name} -Descending | Select-Object -First 1
if(-not $buildTools){throw 'Android build-tools 不存在'}
$apksigner=Join-Path $buildTools.FullName 'apksigner.bat'
$zipalign=Join-Path $buildTools.FullName 'zipalign.exe'
$oldGoOs=$env:GOOS;$oldGoArch=$env:GOARCH;$oldCgo=$env:CGO_ENABLED
Push-Location (Join-Path $projectRoot 'engine/upstream')
try {
    $env:GOOS='linux';$env:GOARCH='arm64';$env:CGO_ENABLED='0'
    & go build -buildvcs=false -trimpath -ldflags '-s -w' -o ../../app/src/main/jniLibs/arm64-v8a/libcarmusic.so ./cmd/carmusic
    if($LASTEXITCODE -ne 0){throw 'ARM64 引擎编译失败'}
} finally {Pop-Location;$env:GOOS=$oldGoOs;$env:GOARCH=$oldGoArch;$env:CGO_ENABLED=$oldCgo}
Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleRelease
    if($LASTEXITCODE -ne 0){throw 'Android Release 构建失败'}
    $versionMatch=[regex]::Match((Get-Content -LiteralPath 'app/build.gradle.kts' -Raw),'versionName\s*=\s*"([^"]+)"')
    if(-not $versionMatch.Success){throw '无法读取版本号'}
    $version=$versionMatch.Groups[1].Value
    $output=Join-Path $projectRoot 'app/build/outputs/apk/release'
    $unsigned=Join-Path $output 'app-release-unsigned.apk'
    $aligned=Join-Path $output 'app-release-aligned.apk'
    $apk=Join-Path $output "CarMusic-v$version-arm64.apk"
    & $zipalign -f -p 4 $unsigned $aligned
    if($LASTEXITCODE -ne 0){throw 'APK 对齐失败'}
    $signArgs=@('sign','--out',$apk,'--debuggable-apk-permitted','false','--v4-signing-enabled','false')
    if($CertificateLineage){
        $signArgs+=@('--lineage',$CertificateLineage,'--rotation-min-sdk-version','28','--ks',$PreviousKeyStore,'--ks-key-alias',$PreviousKeyAlias,'--ks-pass','env:CARMUSIC_PREVIOUS_SIGNING_PASSWORD','--v1-signer-name','CARMUSIC','--next-signer')
    }
    $signArgs+=@('--ks',$KeyStore,'--ks-key-alias',$KeyAlias,'--ks-pass','env:CARMUSIC_SIGNING_PASSWORD','--v1-signer-name','CARMUSIC',$aligned)
    & $apksigner @signArgs
    if($LASTEXITCODE -ne 0){throw 'APK 签名失败'}
    & $apksigner verify --verbose --print-certs $apk
    if($LASTEXITCODE -ne 0){throw 'APK 签名校验失败'}
    $hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'),"$hash  CarMusic-v$version-arm64.apk`n",[Text.UTF8Encoding]::new($false))
    if($Install){
        & (Join-Path $sdkRoot 'platform-tools/adb.exe') -s $Serial install -r $apk
        if($LASTEXITCODE -ne 0){throw '覆盖安装失败；请勿卸载应用以免丢失数据'}
    }
    if($ExportDirectory){
        $destination=[IO.Path]::GetFullPath($ExportDirectory)
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Copy-Item -LiteralPath $apk -Destination (Join-Path $destination "星河音乐_CarMusic_v$version.apk")
        [IO.File]::WriteAllText((Join-Path $destination "SHA256SUMS_v$version.txt"),"$hash  星河音乐_CarMusic_v$version.apk`n",[Text.UTF8Encoding]::new($false))
        $docsDestination=Join-Path $destination 'docs'
        New-Item -ItemType Directory -Path $docsDestination -Force | Out-Null
        foreach($doc in @('CarMusic_开发设计与技术规格说明书.md','THIRD_PARTY_NOTICES.md','UPSTREAM_ORIGIN.md','UI_IMPLEMENTATION_MAPPING.md')){
            Copy-Item -LiteralPath (Join-Path $projectRoot "docs/$doc") -Destination (Join-Path $docsDestination $doc)
        }
        Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $destination 'LICENSE')
        Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $destination 'README.md')
        Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/screenshots') -Destination $docsDestination -Recurse -Force
    }
    Write-Output "正式 APK：$apk"
} finally {Pop-Location}
