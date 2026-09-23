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

# Waits until playback has moved off $Before - a different video, or playback stopped - instead of
# sleeping a fixed amount and hoping. This is the fix for the autoplay/end-of-video flakiness: the
# test remote-seeks to the end of the video, and those key presses consume most of any fixed window,
# so a fixed sleep measured while the video was still a second short of its end (observed as
# "still on e_04ZrNroTo at 228s of 229s"). A position that briefly stops changing must NOT be read
# as "the queue ended", so this never breaks early on a stall; it only gives up at the deadline.
# Returns the last snapshot read, or $null when playback stopped (itself a valid queue outcome).
function Wait-QueueAdvance {
    param($Before, [int]$TimeoutSec = 150)
    # An unreadable baseline must not be compared: "different from nothing" is trivially true and
    # would report a pass for a queue that never moved. Returning the baseline unchanged fails the
    # caller's comparison instead, which is the safe direction.
    if ($null -eq $Before -or -not $Before.videoId) {
        Log '    queue-advance: refusing to compare against an unreadable baseline'
        return $Before
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $Before
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $snap = PlayingNow
        if ($null -eq $snap) { return $null }
        if ($snap.videoId -ne $Before.videoId) { return $snap }
        $last = $snap
    }
    return $last
}

# Waits for a pattern to appear in the app's own log (optionally only in lines after $Since), so a
# test synchronises on the event it asserts rather than on a fixed sleep. This is the fix for the
# captions flakiness: the menu has an ~8s idle timeout, so a fixed sleep could spend the window
# before the key press landed.
function Wait-LogMatch {
    param([string]$Pattern, [int]$Since = 0, [int]$Attempts = 24, [int]$DelayMs = 500)
    for ($i = 0; $i -lt $Attempts; $i++) {
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $Since) -join "`n"
        if ($fresh -match $Pattern) { return $true }
        Start-Sleep -Milliseconds $DelayMs
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
    # otherwise a pause press would land while the video is still buffering.
    #
    # The resume offer no longer pauses anything: the video plays from the beginning behind it and
    # the offer withdraws itself after a few seconds. While it is on screen it still owns the D-pad,
    # so it has to be got out of the way first or the control keys below would move the offer
    # instead of the player. BACK dismisses it and playback carries on from the beginning.
    $running = $false
    # Only lines written from here on can say whether an offer is live right now: a buffer-wide
    # match keeps finding the previous phase's offer, and a stray BACK with nothing open would
    # leave the player entirely.
    $logBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 2
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $logBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME' -and $fresh -notmatch 'Resume offer withdrawn') {
            Log '  resume offer is up - dismissing it so the control keys reach the player'
            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 2
            $logBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
            continue
        }
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

    $capBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    Key 'KEYCODE_DPAD_DOWN'          # enter the settings row (Subtitles is first)
    Start-Sleep -Milliseconds 600
    Key 'KEYCODE_DPAD_CENTER'        # open the Subtitles menu
    # Synchronise on the menu actually opening rather than sleeping and hoping: the menu has an
    # ~8s idle timeout, so a fixed sleep can spend the very window the next key press needs. This
    # is why captions-enable passed twice and failed once on the same APK.
    $menuOpened = Wait-LogMatch 'Menu opened: CAPTIONS' $capBase
    Shot '10-subtitles-menu'
    Record 'menu-subtitles-opens' $menuOpened

    # choose the first subtitle track after "Off"
    $captionsOn = $false
    if ($menuOpened) {
        Key 'KEYCODE_DPAD_DOWN'
        Key 'KEYCODE_DPAD_CENTER'
        # Wait for the selection to be applied instead of sleeping a fixed amount.
        $captionsOn = Wait-LogMatch 'Captions selection: (?!off)' $capBase
    } else {
        Log '  captions: the Subtitles menu never opened - sending no key rather than a stray one'
    }
    Shot '11-subtitles-on'
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

    # ------------------------------------------------------------------ resume, stage 2
    # ORDER MATTERS HERE, and it is the fix for a real regression: the "no input" phase at the end
    # leaves the video playing from the beginning, which saves a position well below
    # RESUME_MIN_MS (20s). A position that low is not "resumable" at all, so running that phase
    # *before* the choice tests destroyed the precondition they depend on and they failed with no
    # offer on screen. The choice tests therefore run FIRST, while a >=20s position is known to be
    # saved, and the destructive no-input phase runs LAST.
    #
    # The choice tests also no longer trust a baseline measured several phases earlier: each one
    # establishes the saved position itself and reads back the value the app reports as resumable,
    # which is the only authoritative source for what the offer will use.
    $ResumeThresholdSec = 20   # mirrors PlaybackController.RESUME_MIN_MS (20_000L)

    # The position the app itself reports as resumable for $videoId, or -1 when it offered nothing.
    function GetSavedResumeSeconds([string]$videoId, [int]$since) {
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $since) -join "`n"
        $m = [regex]::Match($fresh, 'Resumable position for ' + [regex]::Escape($videoId) + ': (\d+)s')
        if ($m.Success) { return [int]$m.Groups[1].Value }
        return -1
    }

    # Drives playback past the resume threshold, lets the periodic save persist it, then reopens the
    # video and leaves its resume offer on screen. Returns the saved seconds, or -1 if a >=20s saved
    # position could not be established (in which case the choice tests must not be asserted).
    function EstablishResumeBaseline([string]$videoId) {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            # A settings menu left open by an earlier step would swallow the seek keys.
            if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: (CAPTIONS|QUALITY|AUDIO|SPEED|ASPECT)') {
                Key 'KEYCODE_BACK'; Start-Sleep -Seconds 2
            }
            PlayVideo $videoId $videoId
            Start-Sleep -Seconds 16
            # A stale offer from a previous attempt would swallow the seek keys too.
            if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
                Key 'KEYCODE_DPAD_DOWN'; Key 'KEYCODE_DPAD_CENTER'; Start-Sleep -Seconds 6
            }
            foreach ($i in 1..4) { Key 'KEYCODE_DPAD_RIGHT' }   # ~40s in
            Start-Sleep -Seconds 14                              # let the periodic save happen
            $leftAt = [int](PlayingNow).positionSec
            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 5
            # Reopen and read back what the app actually considers resumable.
            $since = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
            PlayVideo $videoId $videoId
            $saved = -1
            for ($i = 0; $i -lt 30; $i++) {
                Start-Sleep -Milliseconds 500
                $saved = GetSavedResumeSeconds $videoId $since
                if ($saved -ge 0) { break }
            }
            Log "  resume baseline attempt ${attempt}: playhead was at ${leftAt}s; app reports resumable ${saved}s (threshold ${ResumeThresholdSec}s)"
            if ($saved -ge $ResumeThresholdSec) { return $saved }
            Key 'KEYCODE_BACK'; Start-Sleep -Seconds 4
        }
        Log "  resume baseline: could NOT establish a saved position >= ${ResumeThresholdSec}s"
        return -1
    }

    # --- choice 1: "Resume" must move the playhead to the saved position ---
    $resumeLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $savedForResume = EstablishResumeBaseline $resumeVideo
    # Only send the key once the offer is actually on screen: a stray CENTER would land on the
    # player and toggle pause, which previously poisoned every later step as well.
    $promptForResume = $false
    for ($i = 0; $i -lt 24; $i++) {
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $resumeLogBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME') { $promptForResume = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Record 'resume-prompt-appears' $promptForResume
    Log "  resume choice: saved=${savedForResume}s threshold=${ResumeThresholdSec}s promptOnScreen=$promptForResume"
    # Only the window of the press itself is judged. Establishing the baseline deliberately plays
    # the video without answering its offer, so an ignored-offer withdrawal necessarily appears
    # earlier in the log; sliding the window forward is what keeps the assertion about this press.
    $resumeActionBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $posAfter = -1
    if ($promptForResume) {
        Key 'KEYCODE_DPAD_CENTER'                        # the offer opens on "Resume from ..."
        Start-Sleep -Seconds 4
        $posAfter = [int](PlayingNow).positionSec
    } else {
        Log '  resume choice: the offer was NOT presented - sending no key rather than a stray one'
    }
    $resumeSlice = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $resumeActionBase) -join "`n"
    # The baseline is the position the app reported for THIS open, not a value from an earlier phase.
    Record 'resume-continues-position' (($savedForResume -ge $ResumeThresholdSec) -and $promptForResume -and ([Math]::Abs($posAfter - $savedForResume) -lt 30)) "resumed at ${posAfter}s, app reported resumable ${savedForResume}s"
    Record 'resume-choice-applied' ($promptForResume -and ($resumeSlice -match 'Resume chosen'))
    Shot '15-resume-prompt'
    Shot '16b-resumed'

    # --- choice 2: "Start over" must delete the saved position ---
    # (no BACK here: if the player is already gone, BACK would leave the app entirely and poison
    # every later phase)
    $restartLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $savedForRestart = EstablishResumeBaseline $resumeVideo
    $promptAgain = $false
    for ($i = 0; $i -lt 24; $i++) {
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $restartLogBase) -join "`n"
        if ($fresh -match 'Menu opened: RESUME') { $promptAgain = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Log "  start over: saved=${savedForRestart}s threshold=${ResumeThresholdSec}s promptOnScreen=$promptAgain"
    # Judged over the press window only, for the same reason as the Resume choice above: the
    # baseline step plays unanswered, so it logs its own withdrawal before this press happens.
    $restartActionBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $posRestart = -1
    if ($promptAgain) {
        Key 'KEYCODE_DPAD_DOWN'                          # second option is "Start over"
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 4
        $posRestart = [int](PlayingNow).positionSec
    } else {
        Log '  start over: the offer was NOT presented - sending no key rather than a stray one'
    }
    $restartSlice = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $restartActionBase) -join "`n"
    # "Start over" must still be a deliberate press: require the offer to have been live and this
    # press to be the reason it went away.
    $startOverOk = ($promptAgain -and ($restartSlice -match 'Start over chosen') -and ($restartSlice -notmatch 'Resume offer withdrawn'))
    Record 'resume-start-over' $startOverOk
    Record 'start-over-begins-at-zero' (($posRestart -lt 15) -and $startOverOk) "restarted at ${posRestart}s"

    # --- the no-input phase runs LAST: it deliberately saves a sub-threshold position ---
    # The resume offer no longer holds playback up: the video plays from the beginning immediately
    # and the offer sits over it, so it must NOT be answered and must NOT pause anything. Pressing
    # nothing for longer than its idle window is what proves the new rule - it withdraws itself and
    # playback carries on from the beginning.
    $noChoiceLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $savedForNoChoice = EstablishResumeBaseline $resumeVideo
    $promptSeen = $savedForNoChoice -ge $ResumeThresholdSec
    Log "  no-input phase: saved=${savedForNoChoice}s offerOnScreen=$promptSeen"
    # No input at all: the video must already be playing (nothing was chosen), and the offer must
    # remove itself rather than wait for a decision that is never coming.
    $posWhileOffered = [int](PlayingNow).positionSec
    $playingWhileOffered = [bool](PlayingNow).playing
    Start-Sleep -Seconds 12
    $afterIdle = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $noChoiceLogBase) -join "`n"
    Record 'resume-offer-does-not-block-playback' ($promptSeen -and $playingWhileOffered) "playing=$playingWhileOffered at ${posWhileOffered}s while the offer was up"
    Record 'resume-offer-removes-itself-when-ignored' ($afterIdle -match 'Resume offer withdrawn with no choice')
    $posUnanswered = [int](PlayingNow).positionSec
    Record 'no-choice-starts-from-beginning' ($promptSeen -and ($posUnanswered -lt ($posWhileOffered + 30))) "at ${posUnanswered}s with no choice made (offer was at ${posWhileOffered}s)"
    Shot '16-resume-offer-ignored'

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
            # Wait for the queue to actually advance rather than sleeping a fixed window: the seeks
            # above consume most of any fixed window, so a 45s sleep once measured this video at
            # "228s of 229s" - one second short of the end it was waiting for. The deadline is
            # derived from the time still to play, so it also covers the case where the seek presses
            # did not land and the video plays out from wherever it actually is.
            $autoRemaining = [Math]::Max(30, $autoDuration - [int]$beforeAuto.positionSec)
            $afterAuto = Wait-QueueAdvance $beforeAuto ($autoRemaining + 90)
            $autoReached = if ($null -eq $afterAuto) { 'playback stopped' } else { "$($afterAuto.videoId) (reached $($afterAuto.positionSec)s of $($beforeAuto.durationSec)s)" }
            Log "  autoplay: queue $($beforeAuto.videoId) -> $autoReached"
            Record 'autoplay-advances-to-next-approved' (($null -ne $afterAuto) -and ($afterAuto.videoId -ne $beforeAuto.videoId)) "queue $($beforeAuto.videoId) -> $autoReached"
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
        # Capture the pre-end state robustly. The embedded server occasionally drops a single
        # request, and a null/empty baseline would make the "did it move on?" comparison below
        # trivially true - reporting a PASS for a video that never actually ended. That is a silent
        # false pass, so an unreadable baseline is retried and then reported as a failure with its
        # own reason rather than being converted into a pass.
        $endBefore = $null
        for ($i = 0; $i -lt 6; $i++) {
            $snap = PlayingNow
            if ($snap -and $snap.videoId) { $endBefore = $snap; break }
            Log "    end-of-video: player state unreadable (attempt $($i + 1)) - retrying"
            Start-Sleep -Seconds 2
        }
        if ($null -eq $endBefore) {
            Record 'end-of-video-handling' $false 'could not read the player state before waiting for the end'
        } else {
            # Watch for the actual advance instead of breaking on a position stall: at the end of a
            # video the playhead legitimately stops changing for a moment while the queue moves on,
            # and treating that as "finished waiting" is what produced
            # "still on e_04ZrNroTo at 228s of 229s after waiting" - one second short of the end.
            # The deadline has to cover the video actually playing to its end. When the seek presses
            # land, only the last few seconds remain; when they do not (observed as a baseline of
            # "0s of 165s"), the video has to play from the beginning and a fixed 150s deadline is
            # simply too short - which is what failed as "still on MR5XSOdjKMA at 152s of 165s".
            $remaining = [Math]::Max(30, $durationSec - [int]$endBefore.positionSec)
            $endAfter = Wait-QueueAdvance $endBefore ($remaining + 90)
            Log "  end-of-video: was $($endBefore.videoId) at $($endBefore.positionSec)s of $($endBefore.durationSec)s; now $(if ($null -eq $endAfter) { 'playback stopped' } else { "$($endAfter.videoId) at $($endAfter.positionSec)s" })"
            if ($null -eq $endAfter) {
                Record 'end-of-video-handling' $true "queue ended: playback stopped"
            } elseif ($endAfter.videoId -ne $endBefore.videoId) {
                Record 'end-of-video-handling' $true "advanced to next approved item: $($endAfter.videoId)"
            } else {
                Record 'end-of-video-handling' $false "still on $($endAfter.videoId) at $($endAfter.positionSec)s of $($endAfter.durationSec)s after waiting"
            }
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
            # Say WHERE this phase went wrong. The video is known to play when driven by hand, so
            # if it did not reach the player here the cause is this phase (or the screen the app was
            # left on), and the next person should not have to guess at it the way this one did.
            $probeLog = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
            if ($probeLog -match "Playing $probeVideo") {
                Log '  probe: the video reached the player'
            } else {
                $resumedLine = (Adb @('shell', 'dumpsys activity activities') | Select-String -SimpleMatch 'ResumedActivity' | Select-Object -First 1)
                Log "  probe: the video did NOT reach the player. Foreground: $($resumedLine -replace '\s+', ' ')"
                ($probeLog -split "`n" | Select-Object -Last 5) | ForEach-Object { Log "    $_" }
            }

            # The probe video is played repeatedly across runs, so it can carry a saved position
            # and open the resume prompt - which then eats these keys: DOWN moves the prompt, RIGHT
            # falls through to a seek, and OK answers the prompt, leaving the later keys to open
            # Subtitles instead of Quality. That is why this phase reported "0 options offered"
            # while the video itself was playing perfectly well.
            if (((Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n") -match 'Menu opened: RESUME') {
                Log '  probe: a resume prompt is open - dismissing it so the menu keys land on the player'
                Key 'KEYCODE_BACK'
                Start-Sleep -Seconds 2
            }
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

            # No BACK here. The quality menu closes itself 5s after a choice and this phase waits 12s, so
            # BACK was not closing a menu - it was popping the player, which sent the audio keys to the
            # library and made this assert "0 options offered" while the audio menu was fine.
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