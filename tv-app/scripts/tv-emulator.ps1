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

# Hand over to the real suite, pointed at the emulator.
& (Join-Path $PSScriptRoot 'tv-e2e.ps1') -Serial 'emulator-5554' -Adb $adb -SkipBuild:$SkipBuild
exit $LASTEXITCODE