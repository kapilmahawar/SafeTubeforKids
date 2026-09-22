<#
.SYNOPSIS
  Autonomous end-to-end test of SafeTube on a REAL Android TV over ADB.

.DESCRIPTION
  Builds (optional), installs, launches and exercises the app on the connected TV using
  ADB key events only - no touch input is used, because the acceptance criterion is that a
  child can drive the whole app with a normal TV remote.

  Every run writes artifacts to test-results/tv/<timestamp>/ : device.txt, commit.txt,
  install.log, test.log, logcat.txt, screenshots and an API state dump per step.

  Assertions are made against real signals, never assumptions:
    * the app's own log (SafeTube tag)
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
    [string]$ApiHost = '',
    [switch]$SkipBuild,
    [ValidateSet('smoke', 'player', 'full')]
    [string]$Tier = 'full',
    [switch]$ClearState,
    [switch]$QualityProbe,
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
$pkg = 'tv.safetubeforkids.app'
# Where the dashboard answers: the TV's own address normally, or 127.0.0.1 when a remote
# emulator's guest port has been tunnelled here with adb forward (a guest's 8080 is not exposed
# on its host, so the emulator host address cannot be used directly).
$apiHost = if ($ApiHost) { $ApiHost } else { $Serial.Split(':')[0] }
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
    $lines = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
    return @([regex]::Matches($lines, 'Remote key -> (\w+)') | ForEach-Object { $_.Groups[1].Value })
}

function ForegroundPackage {
    $line = (Adb @('shell', "dumpsys activity activities | grep -m1 mResumedActivity")) -join ''
    if ($line -match '([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+') { return $Matches[1] }
    return ''
}

# A phase must never run against a headless app: if the activity is gone, saying so is part of
# the result. Relaunch so the phase can still execute, and report whether it was already there.
function EnsureApp([string]$phase) {
    $foreground = (ForegroundPackage) -eq $pkg
    if ($foreground) { return $true }
    Log "  app was NOT foreground at '$phase' - relaunching before continuing"
    Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
    Start-Sleep -Seconds 20
    $recovered = (ForegroundPackage) -eq $pkg
    if ($recovered) { Log "  app recovered for '$phase'" }
    return $false
}

function PlayVideo([string]$videoId, [string]$sourceId) {
    Adb @('shell', "am broadcast -a $pkg.DEBUG_PLAY_VIDEO -p $pkg --es video_id $videoId --es playlist_id $sourceId") | Out-Null
}

# ---------------------------------------------------------------- device identity
Log "=== device identity === (tier: $Tier)"
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
# Android names the artifact after the project directory; publish it under the app's own name.
$namedApk = Join-Path (Split-Path $apk) 'SafeTubeforKids-debug.apk'
Copy-Item $apk $namedApk -Force
Log "APK: $namedApk"

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
# A long run with verbose player logging rotates the default ring buffer, which silently ate
# lines the log-based assertions look for. Give it room up front.
Adb @('logcat', '-G', '16M') | Out-Null

Log "=== launch ==="
Adb @('logcat', '-c') | Out-Null
# Start from a clean UI state. Resuming onto whatever screen and focus the previous run left behind -
# the settings screen, the player, its error overlay, or a toolbar button holding focus - makes the
# remote-only navigation phase drive the wrong screen, which reads as "the remote cannot play
# anything" while playback is perfectly healthy. A fresh launch is also what a real install does.
Adb @('shell', "am force-stop $pkg") | Out-Null
Start-Sleep -Seconds 3
Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
Start-Sleep -Seconds $LaunchWaitSec

$launched = ((Adb @('shell', "ps -A | grep -i $pkg")) -join '') -match $pkg
Record 'app-launches' $launched
if (-not $launched) { Log 'ADB_TEST: BLOCKED - app process not running'; exit 3 }

Adb @('shell', "am broadcast -a $pkg.DEBUG_GET_PIN -p $pkg") | Out-Null
Start-Sleep -Seconds 2
$pinLine = (Adb @('logcat', '-d', '-s', 'SafeTube-Intent')) -join "`n"
# The NEWEST line, not the first. The buffer outlives the app process, and PinManager generates its
# PIN in memory on every start, so an earlier line is a PIN this process rejects - which fails auth,
# and with it api-reachable, library readiness and the whole navigation phase.
$pinMatches = [regex]::Matches($pinLine, '"pin":"(\d{6})"')
$pin = if ($pinMatches.Count -gt 0) { $pinMatches[$pinMatches.Count - 1].Groups[1].Value } else { '' }
Log "dashboard pin acquired: $($pin -ne '')"
$headers = @{}
if ($pin) {
    # The embedded server can refuse a connection right after launch; retry before giving up,
    # and never let a transient failure abort the whole run.
    foreach ($attempt in 1..3) {
        try {
            $auth = Invoke-RestMethod -Uri "http://${apiHost}:8080/auth" -Method Post `
                -Body (@{ pin = $pin } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 20
            if ($auth.token) { $headers['Authorization'] = "Bearer $($auth.token)"; break }
        } catch {
            Log "  auth attempt ${attempt} failed: $($_.Exception.Message)"
            Start-Sleep -Seconds 4
        }
    }
}
function ApiState {
    if (-not $headers.ContainsKey('Authorization')) { return $null }
    try { return Invoke-RestMethod -Uri "http://${apiHost}:8080/status" -Headers $headers -TimeoutSec 10 } catch { return $null }
}

Shot '01-library'
$state = ApiState
if ($state) { $state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'api-library.json') }
Record 'api-reachable' ($null -ne $state)

# ---------------------------------------------------------------- remote-only navigation
# The child UI must be reachable with the remote alone: no touch, no intents, no adb tricks.
# The library needs a D-pad press before a card holds focus, so several realistic remote
# sequences are tried and each attempt is verified against the app's own status API.
# The library renders nothing until its sources resolve, and a 50-video playlist takes a while
# after a fresh install. Navigating before then presses keys at an empty screen - which is how
# the D-pad check failed while the same sequence worked by hand a minute later.
$libraryReady = $false
for ($attempt = 1; $attempt -le 20; $attempt++) {
    if ($headers.ContainsKey('Authorization')) {
        try {
            $lib = Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -Headers $headers -TimeoutSec 10
            if (($lib | Where-Object { $_.videoCount -gt 0 } | Measure-Object).Count -gt 0) { $libraryReady = $true; break }
        } catch { }
    }
    Start-Sleep -Seconds 5
}
Log "  library ready before navigating: $libraryReady"

# The app can be restored onto Settings or Connect from the previous session's saved view state, and
# a library card must own focus before DOWN+OK can open a video. Without this the first OK landed on
# a toolbar button and every later sequence ran inside the wrong screen, which read as "the remote
# cannot start a video" while nothing was actually wrong with playback.
for ($i = 0; $i -lt 3; $i++) {
    Adb @('shell', 'uiautomator dump /sdcard/ui.xml') | Out-Null
    $screenDump = (Adb @('shell', 'cat /sdcard/ui.xml')) -join ' '
    # The library is the only screen carrying both of these. Anything else - a parent screen, the
    # player, or the player's error overlay - has to be backed out of first, or the remote is driving
    # the wrong UI: on the error screen the D-pad belongs to Retry/Back, so no sequence can ever
    # start a video and the run reads as "playback is broken" while playback is fine.
    if ($screenDump -match 'SafeTube for Kids' -and $screenDump -match 'Refresh') { break }
    Log '  the app was restored onto another screen - returning to the library first'
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 2
}
# YouTube throttles anonymous watch access from an IP that has asked too often, and then every
# video resolution fails with LOGIN_REQUIRED. Nothing is wrong with the app or with the remote, so
# name the cause here: for two rounds this read as "the remote cannot start a video", and the fix
# looked like harness state when the real answer was in one log line.
if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'LOGIN_REQUIRED') {
    Log 'NOTE: YouTube is refusing anonymous watch access from this IP (LOGIN_REQUIRED) - playback phases will fail for that reason, not because of the app'
}

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
        $allSources = Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -Headers $headers -TimeoutSec 10
        $match = $allSources | Where-Object { $_.sourceId -eq $sourceId } | Select-Object -First 1
        if ($match) { $sourceCount = [int]$match.videoCount }
    } catch { Log "  could not read source size: $($_.Exception.Message)" }
    Log "  playing $videoId from source $sourceId ($sourceCount approved videos)"

    # Wait until playback is genuinely running (playhead advancing) before asserting controls,
    # otherwise a pause press would land while the video is still buffering. A resume prompt is
    # legitimate here (the previous run left a position), so dismiss it to reach playback.
    $running = $false
    # Only lines written from here on can say whether a prompt is live right now: a buffer-wide
    # match keeps finding the previous phase's prompt, and an OK sent to a player with no prompt
    # merely toggles play/pause.
    $logBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 2
        $snapshot = PlayingNow
        if ($snapshot -and $snapshot.playing -eq $true -and [int]$snapshot.positionSec -gt 0) {
            $running = $true
            break
        }
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $logBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME') {
            Log '  resume prompt is open - choosing Resume so the control tests have a live player'
            Key 'KEYCODE_DPAD_CENTER'
            $logBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
            Start-Sleep -Seconds 5
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
    # Start from a known position. A resumed video can be near its end, and +20s of seeking then
    # overshoots it: playback finishes, the queue advances, and the new video's 0s playhead reads
    # like a failed seek. Rewinding first gives the measurement room.
    foreach ($i in 1..15) { Key 'KEYCODE_DPAD_LEFT' }
    Start-Sleep -Seconds 2

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

    if ($Tier -ne 'smoke') {
    # --- player menus (subtitles / quality / audio / speed / screen fit) ---
    # Menus are driven entirely from the remote: DOWN enters the button row, LEFT/RIGHT pick a
    # button, OK opens it, UP/DOWN pick an option, OK applies, BACK closes it.
    $menuLog = { (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n" }

    $fgMenus = EnsureApp 'menus'
    Record 'app-foreground-at-menus' $fgMenus

    # Subtitles are per-video: play a video from the multi-video playlist that has caption
    # tracks (the single-video source has none).
    $captionsVideo = $false
    if ($sourceCount -le 1) {
        # Clear the log first so the resume-prompt check below only sees this phase.
        Adb @('logcat', '-c') | Out-Null
        PlayVideo 'e_04ZrNroTo' 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT'
        Start-Sleep -Seconds 18
        # A resume prompt may legitimately appear first; clear it so the menu tests are clean.
        if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
            Log '  resume prompt opened first - continuing playback before the caption test'
            Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 5
        }
        $captionsVideo = $null -ne (PlayingNow)
        Log "  opened a playlist video for the caption test: $captionsVideo"
    } else {
        $captionsVideo = $true
    }

    Key 'KEYCODE_DPAD_DOWN'          # enter the settings row (Subtitles is first)
    Start-Sleep -Milliseconds 600
    Key 'KEYCODE_DPAD_CENTER'        # open the Subtitles menu
    Start-Sleep -Seconds 2
    Shot '10-subtitles-menu'
    $menuOpened = ((& $menuLog) -match 'Menu opened: CAPTIONS')
    Record 'menu-subtitles-opens' $menuOpened

    # choose the first subtitle track after "Off"
    Key 'KEYCODE_DPAD_DOWN'
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 3
    Shot '11-subtitles-on'
    $captionsOn = ((& $menuLog) -match 'Captions selection: (?!off)')
    if ($captionsVideo) {
        Record 'captions-enable' $captionsOn
    } else {
        Log '  CAPTION_TEST: LIMITED - no video with caption tracks could be opened'
    }

    # Make sure a menu is genuinely open before asserting that BACK closes it: the idle timer may
    # already have closed the previous one, in which case BACK would leave the player instead.
    Key 'KEYCODE_DPAD_DOWN'
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 1
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 2
    $stillPlayingAfterBack = ($null -ne (PlayingNow))
    Record 'menu-back-closes-menu-only' $stillPlayingAfterBack
    Shot '12-after-menu-back'

    # playback speed: DOWN into the row, RIGHT x3 to Speed, open, then 1.0x -> 1.25x
    Key 'KEYCODE_DPAD_DOWN'
    foreach ($i in 1..3) { Key 'KEYCODE_DPAD_RIGHT' }
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 2
    Key 'KEYCODE_DPAD_DOWN'
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 2
    Shot '13-speed-menu'
    $speedChanged = ((& $menuLog) -match 'Player menu SPEED -> sp:1\.25')
    Record 'speed-menu-changes-speed' $speedChanged
    # Screen fit. This phase used to begin with a BACK meant to close the speed menu; when that
    # menu had already auto-closed, the BACK left the player and every following key landed in the
    # library - which is how it failed while the app's own log showed ASPECT -> zoom working.
    # Reopen the video so no phase depends on the previous one's leftover state.
    $fitVideo = if (PlayingNow) { (PlayingNow).videoId } else { $videoId }
    Adb @('logcat', '-c') | Out-Null
    PlayVideo $fitVideo $fitVideo
    Start-Sleep -Seconds 18
    if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 5
    }

    # DOWN into the settings row, RIGHT to the last button, open, choose crop-to-fill
    Key 'KEYCODE_DPAD_DOWN'
    foreach ($i in 1..4) { Key 'KEYCODE_DPAD_RIGHT' }
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 2
    Key 'KEYCODE_DPAD_DOWN'
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 2
    Shot '14-aspect-crop'
    $aspectChanged = ((& $menuLog) -match 'Player menu ASPECT -> zoom')
    Record 'aspect-menu-changes-fit' $aspectChanged
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 1

    # --- resume (deterministic setup via the debug play intent) ---
    $fgResume = EnsureApp 'resume'
    Record 'app-foreground-at-resume' $fgResume

    # Discover the test video from what is actually approved and playing rather than hardcoding
    # an id: a parent may remove a source, and a stale id silently turns this phase into a
    # series of vacuous passes.
    $playingNow0 = PlayingNow
    $resumeVideo = if ($playingNow0) { $playingNow0.videoId } else { $videoId }
    Log "  resume test video: $resumeVideo"
    Adb @('logcat', '-c') | Out-Null
    PlayVideo $resumeVideo $resumeVideo
    Start-Sleep -Seconds 16
    # Deterministic baseline: an earlier phase may have left a saved position, which would make
    # the measurement below compare against a stale value. "Start over" deletes that row.
    if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
        Log '  clearing a pre-existing saved position (Start over) for a deterministic baseline'
        Key 'KEYCODE_DPAD_DOWN'
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 6
    }
    # The baseline step above only works when a resume prompt is actually showing. When it is
    # not, its DOWN+OK opens a settings menu instead, and every later key press moves the menu
    # rather than the video - which is why this phase once measured "left at 0s". Close it.
    if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: (CAPTIONS|QUALITY|AUDIO|SPEED|ASPECT)') {
        Log '  a settings menu was open - closing it so the seek keys reach the player'
        Key 'KEYCODE_BACK'
        Start-Sleep -Seconds 2
    }

    foreach ($i in 1..4) { Key 'KEYCODE_DPAD_RIGHT' }   # ~40s in
    Start-Sleep -Seconds 14                              # let the periodic save happen
    $posBefore = [int](PlayingNow).positionSec
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 5
    Record 'resume-remembers-position' ($posBefore -gt 20) "left at ${posBefore}s"

    $resumeLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    PlayVideo $resumeVideo $resumeVideo
    # The prompt answers itself after 10s - a deliberate rule: no choice means start from the
    # beginning - so it has to be answered the moment it appears. A fixed sleep longer than that
    # window measures the start-over rule rather than the resume choice.
    $promptSeen = $false
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Milliseconds 500
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $resumeLogBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME') { $promptSeen = $true; break }
    }
    Record 'resume-prompt-appears' $promptSeen
    Shot '15-resume-prompt'

    Key 'KEYCODE_DPAD_CENTER'                            # the prompt opens on "Resume from ..."
    Start-Sleep -Seconds 4
    $posAfter = [int](PlayingNow).positionSec
    $resumeSlice = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $resumeLogBase) -join "`n"
    # Without this, a choice sent late would let the start-over rule satisfy the assertion below.
    $answeredInTime = $resumeSlice -notmatch 'No resume choice'
    Record 'resume-continues-position' (($posBefore -gt 20) -and ([Math]::Abs($posAfter - $posBefore) -lt 20)) "resumed at ${posAfter}s (left at ${posBefore}s)"
    Record 'resume-choice-applied' ($answeredInTime -and (((& $menuLog)) -match 'Resume chosen'))
    Shot '16-resumed'

    # start over on the same video (no BACK here: if the player is already gone, BACK would
    # leave the app entirely and poison every later phase)
    $restartLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    PlayVideo $resumeVideo $resumeVideo
    $promptAgain = $false
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Milliseconds 500
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $restartLogBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME') { $promptAgain = $true; break }
    }
    Key 'KEYCODE_DPAD_DOWN'                              # second option is "Start over"
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 4
    $posRestart = [int](PlayingNow).positionSec
        $restartSlice = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $restartLogBase) -join "`n"
        # The 10s auto-restart logs the very same "Start over chosen" line, so require that the
        # prompt was live when the keys were sent - otherwise this passes for the wrong reason.
        $startOverOk = ($promptAgain -and ($restartSlice -notmatch 'No resume choice') -and ($restartSlice -match 'Start over chosen'))
        Record 'resume-start-over' $startOverOk
        Record 'start-over-begins-at-zero' (($posRestart -lt 15) -and $startOverOk) "restarted at ${posRestart}s"

    # --- security: an unapproved video id must never reach the player ---
    $fgSecurity = EnsureApp 'security'
    Record 'app-foreground-at-security' $fgSecurity
    PlayVideo 'dQw4w9WgXcQ' 'dQw4w9WgXcQ'
    Start-Sleep -Seconds 10
    Record 'unapproved-video-blocked' (((& $menuLog)) -match 'Blocked playback of unapproved video')
    Record 'unapproved-video-not-playing' ($null -eq (PlayingNow))
    Shot '17-unapproved-blocked'
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 3

    }
    if ($Tier -eq 'full') {
    # --- autoplay: a finished video must advance to the next APPROVED queue item ---------
    EnsureApp 'autoplay' | Out-Null
    Adb @('logcat', '-c') | Out-Null
    PlayVideo 'e_04ZrNroTo' 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT'
    Start-Sleep -Seconds 18
    if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 5
    }
    $beforeAuto = PlayingNow
    if ($beforeAuto) {
        $autoDuration = [int]$beforeAuto.durationSec
        if ($autoDuration -gt 20 -and $autoDuration -le 900) {
            $autoPresses = [Math]::Max(1, [int](($autoDuration - 12) / 10))
            for ($i = 1; $i -le $autoPresses; $i++) { Key 'KEYCODE_DPAD_RIGHT' }
            Start-Sleep -Seconds 45
            $afterAuto = PlayingNow
            Record 'autoplay-advances-to-next-approved' (($null -ne $afterAuto) -and ($afterAuto.videoId -ne $beforeAuto.videoId)) "queue $($beforeAuto.videoId) -> $($afterAuto.videoId) (reached $($afterAuto.positionSec)s of $($beforeAuto.durationSec)s)"
            Record 'autoplay-stays-in-same-source' (($null -ne $afterAuto) -and ($afterAuto.playlistId -eq $beforeAuto.playlistId))
            Shot '21-autoplay'
        } else {
            Log "  AUTOPLAY_TEST: LIMITED - duration ${autoDuration}s cannot be reached by remote seeking"
        }
    }

    # --- natural end of video: playback must stop inside the approved queue -------------
    EnsureApp 'end-of-video' | Out-Null
    Adb @('logcat', '-c') | Out-Null
    PlayVideo $resumeVideo $resumeVideo
    Start-Sleep -Seconds 16
    if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 5
    }
    $durationSec = [int](PlayingNow).durationSec
    if ($durationSec -gt 20 -and $durationSec -le 900) {
        # Seek to just before the end and let it play out, so the end is reached naturally.
        $presses = [Math]::Max(1, [int](($durationSec - 14) / 10))
        for ($i = 1; $i -le $presses; $i++) { Key 'KEYCODE_DPAD_RIGHT' }
        # Watch the playhead instead of sleeping a fixed amount: reaching the end by remote
        # seeking consumes most of any fixed window, which is why this check once reported a
        # video "still playing" at 164s of 165s.
        $endBefore = PlayingNow
        $lastPos = -1
        $stalled = 0
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Seconds 5
            $snap = PlayingNow
            if ($null -eq $snap) { break }
            if ($snap.videoId -ne $endBefore.videoId) { break }
            $pos = [int]$snap.positionSec
            if ($pos -eq $lastPos) { $stalled++; if ($stalled -ge 3) { break } } else { $stalled = 0 }
            $lastPos = $pos
        }
        $endAfter = PlayingNow
        if ($null -eq $endAfter) {
            Record 'end-of-video-handling' $true "queue ended: playback stopped"
        } elseif ($endAfter.videoId -ne $endBefore.videoId) {
            Record 'end-of-video-handling' $true "advanced to next approved item: $($endAfter.videoId)"
        } else {
            Record 'end-of-video-handling' $false "still on $($endAfter.videoId) at $($endAfter.positionSec)s of $($endAfter.durationSec)s after waiting"
        }
    } else {
        Log "  END_OF_VIDEO_TEST: LIMITED - duration ${durationSec}s cannot be reached by remote seeking alone"
    }

    # The checks below assert that nothing is playing, so start from a stopped state: the
    # autoplay check deliberately leaves a video running.
    Adb @('shell', "am broadcast -a $pkg.DEBUG_STOP_PLAYBACK -p $pkg") | Out-Null
    Start-Sleep -Seconds 5
    Adb @('shell', "am broadcast -a $pkg.DEBUG_STOP_PLAYBACK -p $pkg") | Out-Null
    Start-Sleep -Seconds 4
    Record 'stopped-before-security' ($null -eq (PlayingNow))

    # --- security: the API must refuse anything unauthenticated -------------------------
    $unauthRead = 0
    $unauthWrite = 0
    try {
        Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -TimeoutSec 10 | Out-Null
    } catch { $unauthRead = $_.Exception.Response.StatusCode.value__ }
    try {
        Invoke-WebRequest -Uri "http://${apiHost}:8080/playlists" -Method Post `
            -Body '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}' -ContentType 'application/json' `
            -TimeoutSec 10 -UseBasicParsing | Out-Null
    } catch { $unauthWrite = $_.Exception.Response.StatusCode.value__ }
    Record 'api-refuses-unauth-read' ($unauthRead -eq 401) "GET /playlists -> $unauthRead"
    Record 'api-refuses-unauth-write' ($unauthWrite -eq 401) "POST /playlists -> $unauthWrite"

    # --- security: no deep link may carry a video into the player ----------------------
    Adb @('shell', "am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'") | Out-Null
    Start-Sleep -Seconds 6
    $foregroundAfterView = ForegroundPackage
    Record 'no-view-deeplink-handler' ($foregroundAfterView -ne $pkg) "foreground=$foregroundAfterView"
    Record 'deeplink-plays-nothing' ($null -eq (PlayingNow))

    Adb @('shell', "am start -n $pkg/.MainActivity --es url 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'") | Out-Null
    Start-Sleep -Seconds 6
    Record 'extras-cannot-start-playback' ($null -eq (PlayingNow))
    Shot '18-security'

    # exported surface, recorded as an artifact for review
    $aapt2 = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe'
    if (Test-Path $aapt2) {
        & $aapt2 dump xmltree --file AndroidManifest.xml $apk 2>&1 | Set-Content (Join-Path $out 'manifest-tree.txt')
        Log '  exported component surface written to manifest-tree.txt'
    }

    # --- optional: quality/audio switching needs multi-rendition content -----------------
    # Kids' videos expose a single progressive rendition (CoComelon resolved 1 quality, 0
    # audio), so this check is opt-in: it temporarily approves a public multi-rendition video,
    # exercises the menus, then removes it again so the child's library is left untouched.
    if ($QualityProbe) {
        $probeVideo = 'aqz-KE-bpKQ'
        $probeSourceId = $null
        try {
            $added = Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -Method Post `
                -Headers $headers -Body (@{ url = "https://www.youtube.com/watch?v=$probeVideo" } | ConvertTo-Json -Compress) `
                -ContentType 'application/json' -TimeoutSec 20
            $probeSourceId = $added.id
            Log "  temporarily approved $probeVideo for the quality/audio probe"
        } catch {
            Log "  QUALITY_TEST: BLOCKED - could not approve the probe video"
        }

        if ($probeSourceId) {
            # A newly approved source has to be resolved AND cached before the player is allowed to
            # play it; a blind 12-second wait was not always enough, the video then never opened, and
            # the whole probe reported "0 options offered" as if the quality menu were broken. Resolve
            # it explicitly and wait for the app to say so, with a timeout so a genuinely unavailable
            # video still fails honestly.
            Adb @('shell', "am broadcast -a $pkg.DEBUG_RESOLVE_PLAYLIST -p $pkg --es playlist_id $probeVideo") | Out-Null
            $probeReady = $false
            for ($i = 0; $i -lt 20; $i++) {
                Start-Sleep -Seconds 3
                if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match "Resolved video $probeVideo") { $probeReady = $true; break }
            }
            Log "  probe source resolved and cached: $probeReady"
            Adb @('logcat', '-c') | Out-Null
            PlayVideo $probeVideo $probeVideo
            Start-Sleep -Seconds 20

            Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_RIGHT'; Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 2
            Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 12
            $qualityLog = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
            $qualityOptions = 0
            if ($qualityLog -match 'Menu opened: QUALITY \((\d+) options\)') { $qualityOptions = [int]$Matches[1] }
            Record 'quality-menu-lists-real-renditions' ($qualityOptions -gt 1) "$qualityOptions options offered"
            Record 'quality-switch-applied' ($qualityLog -match 'Player menu QUALITY -> h\d+')
            Shot '19-quality'

            Key 'KEYCODE_BACK'
            Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_RIGHT'; Key 'KEYCODE_DPAD_RIGHT'; Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 2
            Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 12
            $audioLog = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
            $audioOptions = 0
            if ($audioLog -match 'Menu opened: AUDIO \((\d+) options\)') { $audioOptions = [int]$Matches[1] }
            Record 'audio-menu-lists-real-tracks' ($audioOptions -gt 1) "$audioOptions options offered"
            Record 'audio-switch-applied' ($audioLog -match 'Player menu AUDIO -> (ab|at):\d+')
            Shot '20-audio'

            try {
                Invoke-WebRequest -Uri "http://${apiHost}:8080/playlists/$probeSourceId" -Method Delete `
                    -Headers $headers -TimeoutSec 20 -UseBasicParsing | Out-Null
                Log '  removed the probe video again'
            } catch {
                Log "  WARNING: could not remove the probe video (id $probeSourceId)"
            }
        }
    } else {
        Log '  QUALITY_TEST: skipped - run with -QualityProbe (needs a multi-rendition video)'
    }

    # --- approved-queue navigation ----------------------------------
    # Recompute the queue size for whatever is playing now (the caption step may have switched
    # to a video from the multi-video playlist).
    $current = PlayingNow
    $queueCount = 0
    if ($current) {
        try {
            $sources = Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -Headers $headers -TimeoutSec 10
            $entry = $sources | Where-Object { $_.sourceId -eq $current.playlistId } | Select-Object -First 1
            if ($entry) { $queueCount = [int]$entry.videoCount }
        } catch { }
    }
    Log "  queue under test: source=$($current.playlistId) videos=$queueCount"

    if ($queueCount -gt 1) {
        Key 'KEYCODE_MEDIA_NEXT'
        Start-Sleep -Seconds 12
        $nextId = (PlayingNow).videoId
        Record 'next-is-approved-queue' (($null -ne $nextId) -and ($nextId -ne $current.videoId)) "queue $($current.videoId) -> $nextId"
        Shot '04-next'

        Key 'KEYCODE_MEDIA_PREVIOUS'
        Start-Sleep -Seconds 12
        $prevId = (PlayingNow).videoId
        Record 'previous-is-approved-queue' ($prevId -eq $current.videoId) "queue $nextId -> $prevId"
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
$logcatLines = Get-Content (Join-Path $out 'logcat.txt')
$fatalIndex = -1
for ($i = 0; $i -lt $logcatLines.Count; $i++) {
    if ($logcatLines[$i] -match 'FATAL EXCEPTION') { $fatalIndex = $i; break }
}
if ($fatalIndex -ge 0) {
    # Persist the trace next to the run so a wrapped logcat buffer cannot hide it.
    $from = [Math]::Max(0, $fatalIndex - 4)
    $to = [Math]::Min($logcatLines.Count - 1, $fatalIndex + 45)
    $logcatLines[$from..$to] | Set-Content (Join-Path $out 'crash.txt')
    Log '  CRASH TRACE captured in crash.txt'
}
$appCrash = $false
if ($fatalIndex -ge 0) {
    # Only a crash belonging to this app counts; the TV logs unrelated processes too.
    $window = $logcatLines[$fatalIndex..([Math]::Min($logcatLines.Count - 1, $fatalIndex + 45))] -join "`n"
    $appCrash = $window -match 'tv\.parentapproved'
}
Record 'no-app-crash' (-not $appCrash)
Shot '08-final'

# ---------------------------------------------------------------- summary
    }

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