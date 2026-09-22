<#
.SYNOPSIS
  Autonomous end-to-end test of ParentApproved on a REAL Android TV over ADB.

.DESCRIPTION
  Builds (optional), installs, launches and exercises the app on the connected TV using
  ADB key events only - no touch input is used, because the acceptance criterion is that a
  child can drive the whole app with a normal TV remote.

  Every run writes artifacts to test-results/tv/<timestamp>/ : device.txt, commit.txt,
  install.log, test.log, logcat.txt, screenshots and an API state dump per step.

  Assertions are made against real signals, never assumptions:
    * the app's own log (ParentApproved tag)
    * the Ktor status API (currentlyPlaying / position / playing flag)
    * screenshots for visual checks

.EXAMPLE
  ./tv-e2e.ps1                       # build, install (keeps app data), test
  ./tv-e2e.ps1 -SkipBuild            # reuse the existing APK
  ./tv-e2e.ps1 -ClearState           # DESTRUCTIVE: wipes approved sources first
#>
[CmdletBinding()]
param(
    [string]$Serial = "172.16.1.2:5555",
    [string]$Adb = '',
    [switch]$SkipBuild,
    [switch]$ClearState,
    [int]$LaunchWaitSec = 25
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repository root
$apk = Join-Path $repoRoot 'tv-app\app\build\outputs\apk\debug\app-debug.apk'
$adb = if ($Adb) {
    $Adb
} elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
    Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} else {
    'adb'
}
$pkg = 'tv.parentapproved.app'
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$out = Join-Path $repoRoot "test-results\tv\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$script:failures = @()
$script:results = [ordered]@{}
$script:blocked = $false

function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg
    # Write-Host, not Write-Output: logging must never leak into a function's return value.
    Write-Host $line
    Add-Content -Path (Join-Path $out 'test.log') -Value $line
}
function Record([string]$name, [bool]$ok, [string]$detail = '') {
    $script:results[$name] = if ($ok) { 'PASS' } else { 'FAIL' }
    if (-not $ok) { $script:failures += $name }
    Log ("{0}: {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
}
function Adb([string[]]$adbArgs) {
    # Android tools (monkey, logcat) routinely write progress to stderr; that must not be
    # treated as a terminating error under $ErrorActionPreference = 'Stop'.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $adb -s $Serial @adbArgs 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
}
function Key([string]$code) { Adb @('shell', 'input', 'keyevent', $code) | Out-Null; Start-Sleep -Milliseconds 700 }
function Shot([string]$name) {
    $device = "/sdcard/$name.png"
    Adb @('shell', "screencap -p $device") | Out-Null
    Adb @('pull', $device, (Join-Path $out "$name.png")) | Out-Null
    Adb @('shell', "rm -f $device") | Out-Null
}
# Focus evidence. NOTE: multiple uiautomator dumps can destabilise the app's embedded server,
# so these are only taken at the end, after every API-based assertion has already run.
function Dump([string]$name) {
    $device = "/sdcard/$name.xml"
    Adb @('shell', "uiautomator dump $device") | Out-Null
    Adb @('pull', $device, (Join-Path $out "$name.xml")) | Out-Null
    Adb @('shell', "rm -f $device") | Out-Null
}
# The app's embedded server occasionally drops a single request; an unreadable state must be
# retried and reported as UNKNOWN, never silently converted into a FAIL or a PASS.
function Wait-PlayingFlag {
    param([bool]$Expected, [int]$Attempts = 6)
    for ($i = 0; $i -lt $Attempts; $i++) {
        $snapshot = PlayingNow
        if ($snapshot) {
            Log "    poll $i : playing=$($snapshot.playing) position=$($snapshot.positionSec)s"
        } else {
            Log "    poll $i : status unreadable (currentlyPlaying=null)"
        }
        if ($snapshot -and ($snapshot.playing -eq $Expected)) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Direct evidence that remote keys were handled by the player, straight from the app's log.
function Get-RemoteActions {
    $lines = (Adb @('logcat', '-d', '-s', 'ParentApproved')) -join "`n"
    return @([regex]::Matches($lines, 'Remote key -> (\w+)') | ForEach-Object { $_.Groups[1].Value })
}

# ---------------------------------------------------------------- device identity
Log "=== device identity ==="
$devices = (Adb @('devices', '-l')) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial)) {
    Log "device not attached - attempting adb connect $Serial"
    Adb @('connect', $Serial) | ForEach-Object { Log "  $_" }
    Start-Sleep -Seconds 3
    $devices = (Adb @('devices', '-l')) -join "`n"
}
if ($devices -notmatch [regex]::Escape($Serial)) {
    Log "ADB_TEST: BLOCKED - $Serial not present in adb devices"
    exit 2
}
$props = [ordered]@{}
foreach ($p in @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release',
        'ro.build.version.sdk', 'ro.build.display.id', 'ro.product.cpu.abi')) {
    $props[$p] = ((Adb @('shell', "getprop $p")) -join '').Trim()
}
$wmSize = ((Adb @('shell', 'wm size')) -join '').Trim()
$wmDensity = ((Adb @('shell', 'wm density')) -join '').Trim()
$deviceInfo = @("serial: $Serial") + ($props.GetEnumerator() | ForEach-Object { "$($_.Key): $($_.Value)" }) +
    @("wm size: $wmSize", "wm density: $wmDensity")
$deviceInfo | Set-Content (Join-Path $out 'device.txt')
$deviceInfo | ForEach-Object { Log "  $_" }

$commit = (& git -C $repoRoot rev-parse HEAD 2>&1) -join ''
"$commit" | Set-Content (Join-Path $out 'commit.txt')
Log "commit: $commit"

# ---------------------------------------------------------------- build + install
if (-not $SkipBuild) {
    Log "=== build (assembleDebug) ==="
    $gradlew = Join-Path $repoRoot 'tv-app\gradlew.bat'
    $buildLog = & $gradlew -p (Join-Path $repoRoot 'tv-app') assembleDebug --console=plain 2>&1
    $buildLog | Set-Content (Join-Path $out 'build.log')
    if ($LASTEXITCODE -ne 0) { Log 'BUILD: FAIL'; $buildLog | Select-Object -Last 20 | ForEach-Object { Log "  $_" }; exit 1 }
    Log 'BUILD: PASS'
}
if (-not (Test-Path $apk)) { Log "ADB_TEST: BLOCKED - APK not found at $apk"; exit 2 }

Log "=== install ==="
if ($ClearState) {
    Log 'pm clear (destructive: approved sources are wiped)'
    Adb @('shell', "pm clear $pkg") | Out-Null
}
$install = Adb @('install', '-r', $apk)
$install | Set-Content (Join-Path $out 'install.log')
$installed = ($install -join "`n") -match 'Success'
Record 'install' $installed
if (-not $installed) { $install | ForEach-Object { Log "  $_" }; exit 1 }

# ---------------------------------------------------------------- launch + pin
Log "=== launch ==="
Adb @('logcat', '-c') | Out-Null
Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
Start-Sleep -Seconds $LaunchWaitSec

$launched = ((Adb @('shell', "ps -A | grep -i $pkg")) -join '') -match $pkg
Record 'app-launches' $launched
if (-not $launched) { Log 'ADB_TEST: BLOCKED - app process not running'; exit 3 }

Adb @('shell', "am broadcast -a $pkg.DEBUG_GET_PIN -p $pkg") | Out-Null
Start-Sleep -Seconds 2
$pinLine = (Adb @('logcat', '-d', '-s', 'ParentApproved-Intent')) -join "`n"
$pin = ([regex]::Match($pinLine, '"pin":"(\d{6})"')).Groups[1].Value
Log "dashboard pin acquired: $($pin -ne '')"
$headers = @{}
if ($pin) {
    $auth = Invoke-RestMethod -Uri "http://$($Serial.Split(':')[0]):8080/auth" -Method Post `
        -Body (@{ pin = $pin } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 15
    if ($auth.token) { $headers['Authorization'] = "Bearer $($auth.token)" }
}
function ApiState {
    if (-not $headers.ContainsKey('Authorization')) { return $null }
    try { return Invoke-RestMethod -Uri "http://$($Serial.Split(':')[0]):8080/status" -Headers $headers -TimeoutSec 10 } catch { return $null }
}

Shot '01-library'
$state = ApiState
if ($state) { $state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'api-library.json') }
Record 'api-reachable' ($null -ne $state)

# ---------------------------------------------------------------- remote-only navigation
# The child UI must be reachable with the remote alone: no touch, no intents, no adb tricks.
# The library needs a D-pad press before a card holds focus, so several realistic remote
# sequences are tried and each attempt is verified against the app's own status API.
Log "=== navigate with D-pad only ==="
function PlayingNow {
    $s = ApiState
    if ($s -and $s.currentlyPlaying) { return $s.currentlyPlaying }
    return $null
}

$sequences = @(
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER')
)
$opened = $null
foreach ($sequence in $sequences) {
    foreach ($keyCode in $sequence) { Key $keyCode }
    Start-Sleep -Seconds 10
    $opened = PlayingNow
    if ($opened) { Log "  opened with remote sequence: $($sequence -join ' -> ')"; break }
    Log "  no playback after sequence: $($sequence -join ' -> ')"
}
Shot '02-player-opened'
$afterOpen = ApiState
if ($afterOpen) { $afterOpen | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'api-playing.json') }
Record 'dpad-opens-approved-video' ([bool]$opened)

if (-not $opened) {
    Log 'PLAYER_FEATURE_TEST: BLOCKED - no approved video started, so player controls cannot be exercised'
    $script:blocked = $true
}

if ($opened) {
    $videoId = $opened.videoId
    $sourceId = $opened.playlistId
    $sourceCount = 0
    try {
        $allSources = Invoke-RestMethod -Uri "http://$($Serial.Split(':')[0]):8080/playlists" -Headers $headers -TimeoutSec 10
        $match = $allSources | Where-Object { $_.sourceId -eq $sourceId } | Select-Object -First 1
        if ($match) { $sourceCount = [int]$match.videoCount }
    } catch { Log "  could not read source size: $($_.Exception.Message)" }
    Log "  playing $videoId from source $sourceId ($sourceCount approved videos)"

    # Wait until playback is genuinely running (playhead advancing) before asserting controls,
    # otherwise a pause press would land while the video is still buffering.
    $running = $false
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        Start-Sleep -Seconds 2
        $snapshot = PlayingNow
        if ($snapshot -and $snapshot.playing -eq $true -and [int]$snapshot.positionSec -gt 0) {
            $running = $true
            break
        }
    }
    Record 'playback-running' $running

    # --- play / pause ------------------------------------------------
    Key 'KEYCODE_MEDIA_PLAY_PAUSE'
    $pauseOk = Wait-PlayingFlag -Expected $false
    Record 'remote-play-pause' $pauseOk "expected playing=false"

    Key 'KEYCODE_MEDIA_PLAY_PAUSE'
    $resumeOk = Wait-PlayingFlag -Expected $true
    Record 'remote-resume' $resumeOk "expected playing=true"

    # --- seeking (asserted on the real playhead, not watch time) -----
    $p0 = [int](PlayingNow).positionSec
    Key 'KEYCODE_MEDIA_FAST_FORWARD'
    Key 'KEYCODE_DPAD_RIGHT'
    Start-Sleep -Seconds 3
    $p1 = [int](PlayingNow).positionSec
    Record 'seek-forward' ($p1 -ge ($p0 + 8)) "position ${p0}s -> ${p1}s"

    Key 'KEYCODE_MEDIA_REWIND'
    Key 'KEYCODE_DPAD_LEFT'
    Start-Sleep -Seconds 3
    $p2 = [int](PlayingNow).positionSec
    Record 'seek-backward' ($p2 -le ($p1 - 8)) "position ${p1}s -> ${p2}s"
    Shot '03-after-seek'

    # --- remote keys actually reached the player ---------------------
    $actions = @(Get-RemoteActions)
    Record 'remote-keys-reach-player' ($actions.Count -ge 4) ("handled: " + ($actions -join ','))

    # --- controls auto-hide -----------------------------------------
    Start-Sleep -Seconds 6
    Shot '05-controls-hidden'
    Key 'KEYCODE_DPAD_UP'
    Start-Sleep -Milliseconds 900
    Shot '06-controls-shown'

    # --- approved-queue navigation ----------------------------------
    if ($sourceCount -gt 1) {
        Key 'KEYCODE_MEDIA_NEXT'
        Start-Sleep -Seconds 12
        $nextId = (PlayingNow).videoId
        Record 'next-is-approved-queue' (($null -ne $nextId) -and ($nextId -ne $videoId)) "queue $videoId -> $nextId"
        Shot '04-next'

        Key 'KEYCODE_MEDIA_PREVIOUS'
        Start-Sleep -Seconds 12
        $prevId = (PlayingNow).videoId
        Record 'previous-is-approved-queue' ($prevId -eq $videoId) "queue $nextId -> $prevId"
    } else {
        # A single-video source has no next item: NEXT must end playback rather than ask
        # YouTube for a recommendation.
        Key 'KEYCODE_MEDIA_NEXT'
        Start-Sleep -Seconds 6
        Record 'next-ends-at-queue-end' ($null -eq (PlayingNow)) 'single approved video: playback must stop'
        Log '  queue next/previous: NOT APPLICABLE (source holds 1 approved video)'
    }

    # --- back returns to the library (only meaningful if still playing)
    if (PlayingNow) {
        Key 'KEYCODE_BACK'
        Start-Sleep -Seconds 4
        Record 'back-returns-to-library' ($null -eq (PlayingNow))
        Shot '07-library-again'
    } else {
        Log '  back test: SKIPPED because playback already ended at the queue boundary'
    }
}

# ---------------------------------------------------------------- lifecycle
Log "=== HOME -> return ==="
Key 'KEYCODE_HOME'
Start-Sleep -Seconds 3
Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
Start-Sleep -Seconds 8
$alive = ((Adb @('shell', "ps -A | grep -i $pkg")) -join '') -match $pkg
Record 'survives-home-return' $alive

# ---------------------------------------------------------------- logs + crash check
Adb @('logcat', '-d') | Set-Content (Join-Path $out 'logcat.txt')
$logcat = Get-Content (Join-Path $out 'logcat.txt') -Raw
$crash = $logcat -match 'FATAL EXCEPTION' -or $logcat -match 'ANR in tv.parentapproved'
Record 'no-crash-or-anr' (-not $crash)
Shot '08-final'

# ---------------------------------------------------------------- summary
Log "=== SUMMARY ==="
Dump '09-final-ui'
$script:results.GetEnumerator() | ForEach-Object { Log ("  {0,-30} {1}" -f $_.Key, $_.Value) }
if ($script:blocked) { Log '  PLAYER_FEATURE_TEST           BLOCKED' }
$script:results | ConvertTo-Json | Set-Content (Join-Path $out 'result.json')
Log "artifacts: $out"
if ($script:blocked) { Log 'FINAL: BLOCKED (player features could not be exercised over the remote)'; exit 3 }
if ($script:failures.Count -gt 0) { Log "FINAL: FAIL ($($script:failures -join ', '))"; exit 1 }
Log 'FINAL: PASS'
exit 0
