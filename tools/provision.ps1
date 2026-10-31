<#
.SYNOPSIS
  Installs and sets up the app on an Android TV over adb.

.EXAMPLE
  .\tools\provision.ps1 -Tv 192.168.1.60 -StartUrl "http://192.168.1.50:8123/house?tv=1"

.NOTES
  Default APK: the debug build (app\build\outputs\apk\standard\debug\app-standard-debug.apk). Add -Release for a
  release build. Prints the API token at the end: store it in Home Assistant's secrets.yaml only.
#>
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [Parameter(Mandatory = $true)][string]$StartUrl,
    [string]$Apk,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$ns = 'io.github.mattgrechtech.openpane'
if ($Release) {
    $pkg = $ns
    if (-not $Apk) { $Apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk' }
} else {
    $pkg = "$ns.debug"
    if (-not $Apk) { $Apk = Join-Path $root 'app\build\outputs\apk\standard\debug\app-standard-debug.apk' }
}
$receiver = "$pkg/$ns.service.ProvisionReceiver"

if ($StartUrl -notmatch '^https?://') { throw 'The start URL must begin with http:// or https://' }
if (-not (Test-Path $Apk)) { throw "No APK at $Apk. Build it first: .\gradlew.bat assembleDebug" }

$target = "${Tv}:5555"
Write-Host "Connecting to $target ..."
adb connect $target | Out-Null
adb -s $target get-state 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Can't reach $target. Is network debugging on, and did you accept the prompt on the TV?" }

Write-Host "Installing $(Split-Path -Leaf $Apk) ..."
adb -s $target install -r $Apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Install failed' }

Write-Host 'Allowing pop-ups over other apps and background start ...'
adb -s $target shell appops set $pkg SYSTEM_ALERT_WINDOW allow
adb -s $target shell appops set $pkg TURN_SCREEN_ON allow 2>$null  # Android 14+: wake on motion turns the screen on
adb -s $target shell appops set $pkg AUTO_START allow 2>$null  # TCL: lets boot and updates restart it
adb -s $target shell appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>$null  # lets you install updates from Home Assistant (Android still asks)
Write-Host 'Excluding it from battery optimisation (keeps the service running) ...'
adb -s $target shell dumpsys deviceidle whitelist "+$pkg" | Out-Null

# base64 keeps the JSON intact: Windows PowerShell 5.1 strips quotes passed to adb.
$json = @{ start_url = $StartUrl } | ConvertTo-Json -Compress
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
Write-Host 'Setting the start URL ...'
$out = adb -s $target shell am broadcast -n $receiver -a "$ns.CONFIGURE" --es json_b64 $b64
$out | Select-String 'data=' | ForEach-Object { Write-Host "  $($_.Line)" }

Write-Host 'Opening the dashboard ...'
adb -s $target shell am start -n "$pkg/$ns.web.KioskActivity" | Out-Null

$tokenOut = adb -s $target shell am broadcast -n $receiver -a "$ns.GET_TOKEN"
$token = ([regex]'"token":"([0-9a-f]+)"').Match(($tokenOut -join "`n")).Groups[1].Value
Write-Host ''
Write-Host "Done. API: http://${Tv}:2424/api/v1/state"
Write-Host "Token (put it in Home Assistant's secrets.yaml, nowhere else):"
Write-Host "  $token"
