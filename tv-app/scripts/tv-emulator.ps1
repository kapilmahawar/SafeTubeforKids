<#
.SYNOPSIS
  Boots the Android TV 12 emulator and runs the end-to-end suite against it.

.DESCRIPTION
  Routine verification belongs on the emulator so the family TV stays free; the TV is only
  needed for what an emulator cannot reproduce (physical remote key repeat, hardware video
  decoding, launcher/icon rendering, real network behaviour).

  Creates the AVD if it is missing, boots it, waits for the system to finish booting, installs
  the current APK and hands off to tv-e2e.ps1 pointed at emulator-5554.

  Requires the Android TV image (system-images;android-31;android-tv;x86) and the emulator
  package, and WHPX/hypervisor support: run `emulator -accel-check` first, it exits with 3
  ("Virtualization extension is not supported") when Windows Hypervisor Platform is missing.

.EXAMPLE
  ./tv-emulator.ps1                 # headless run
  ./tv-emulator.ps1 -ShowWindow     # same, but with the TV screen visible on this PC
#>
[CmdletBinding()]
param(
    [string]$AvdName = 'safetube_tv31',
    [switch]$ShowWindow,
    [switch]$SkipBuild,
    [int]$BootTimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path (Split-Path -Parent $repoRoot) 'toolchain\android-sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avdmanager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$image = 'system-images;android-31;android-tv;x86'

if (-not (Test-Path $emulator)) { throw "emulator not found at $emulator - install the 'emulator' SDK package" }

# Fail fast and clearly when the host cannot accelerate the emulator.
$accel = & $emulator -accel-check 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Output "EMULATOR_TEST: BLOCKED - the host cannot accelerate the emulator:"
    Write-Output ($accel.Trim())
    Write-Output 'Enable Windows Hypervisor Platform (elevated PowerShell, then reboot):'
    Write-Output '  dism /online /enable-feature /featurename:HypervisorPlatform /all'
    exit 3
}

# Create the AVD on first use.
$existing = & $avdmanager list avd 2>&1 | Out-String
if ($existing -notmatch [regex]::Escape($AvdName)) {
    Write-Output "creating AVD $AvdName from $image"
    'no' | & $avdmanager create avd -n $AvdName -k $image -d tv_1080p --force | Out-Null
}

$running = (& $adb devices) -join "`n"
if ($running -notmatch 'emulator-5554') {
    $args = @('-avd', $AvdName, '-no-audio', '-no-snapshot', '-no-boot-anim', '-port', '5554',
        '-gpu', 'swiftshader_indirect')
    if (-not $ShowWindow) { $args += '-no-window' }
    Write-Output "booting $AvdName (window: $([bool]$ShowWindow))"
    Start-Process -FilePath $emulator -ArgumentList $args -WindowStyle Hidden
}

Write-Output 'waiting for the system to finish booting...'
$booted = $false
$waited = 0
while ($waited -lt $BootTimeoutSec) {
    Start-Sleep -Seconds 10
    $waited += 10
    $state = ((& $adb -s emulator-5554 shell getprop sys.boot_completed 2>&1) -join '').Trim()
    if ($state -eq '1') { $booted = $true; break }
}
if (-not $booted) {
    Write-Output "EMULATOR_TEST: BLOCKED - no boot after ${waited}s; adb reports:"
    & $adb devices
    exit 3
}
Write-Output "booted after ${waited}s: android $(((& $adb -s emulator-5554 shell getprop ro.build.version.release) -join '').Trim()) (api $(((& $adb -s emulator-5554 shell getprop ro.build.version.sdk) -join '').Trim()))"

# A fresh emulator starts with an empty library, and the suite navigates into approved content,
# so seed the same two sources the TV has when the library is empty.
& $adb -s emulator-5554 install -r (Join-Path $repoRoot 'tv-app\app\build\outputs\apk\debug\app-debug.apk') | Out-Null
& $adb -s emulator-5554 shell monkey -p tv.safetubeforkids.app -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 30
$pin = ''
foreach ($attempt in 1..6) {
    & $adb -s emulator-5554 logcat -c | Out-Null
    & $adb -s emulator-5554 shell am broadcast -a tv.safetubeforkids.app.DEBUG_GET_PIN -p tv.safetubeforkids.app | Out-Null
    Start-Sleep -Seconds 3
    $pin = ([regex]::Match(((& $adb -s emulator-5554 logcat -d) -join "`n"), '"pin":"(\d{6})"')).Groups[1].Value
    if ($pin) { break }
    Start-Sleep -Seconds 8
}
if (-not $pin) {
    Write-Output 'EMULATOR_TEST: BLOCKED - could not read the app PIN to seed the library'
    exit 3
}
$token = $null
foreach ($attempt in 1..6) {
    try {
        $auth = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/auth' -Method Post -Body (@{ pin = $pin } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 15
        if ($auth.token) { $token = $auth.token; break }
    } catch { Start-Sleep -Seconds 5 }
}
if (-not $token) {
    Write-Output 'EMULATOR_TEST: BLOCKED - app is not serving on the emulator'
    exit 3
}
& $adb -s emulator-5554 forward tcp:8080 tcp:8080 | Out-Null
$existingSources = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/playlists' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
if (($existingSources | Measure-Object).Count -eq 0) {
    foreach ($url in @('https://www.youtube.com/playlist?list=PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT', 'https://www.youtube.com/watch?v=DuXwFlL8Usk')) {
        try {
            Invoke-WebRequest -Uri 'http://127.0.0.1:8080/playlists' -Method Post -Headers @{ Authorization = "Bearer $token" } -Body (@{ url = $url } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 20 -UseBasicParsing | Out-Null
            Write-Output "seeded $url"
        } catch { Write-Output "seed failed for $url" }
    }
    Start-Sleep -Seconds 40
}

# Hand over to the real suite, pointed at the emulator.
& (Join-Path $PSScriptRoot 'tv-e2e.ps1') -Serial 'emulator-5554' -Adb $adb -SkipBuild:$SkipBuild
exit $LASTEXITCODE
