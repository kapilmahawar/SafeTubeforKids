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

.NOTES
  Exit codes: 0 every assertion passed, 1 an assertion failed, 2 blocked (device or APK missing),
  3 blocked (the app could not be driven), 4 RESULT=HARNESS_PRECONDITION_FAILURE - the run could not be
  performed at all (no usable approved library, or no resolvable multi-item queue), so the app was
  never measured and the result is neither a product pass nor a product failure.
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

# ---------------------------------------------------------------- preconditions and approved library
# A precondition failure is not a product failure: the run could not be performed at all, so it must
# not be summarised as a PASS or a FAIL of the app. Exit code 4 keeps it distinct from an assertion
# failure (1) and from "blocked" (2, 3).
function Stop-HarnessPrecondition([string]$reason) {
    Log 'RESULT=HARNESS_PRECONDITION_FAILURE'
    Log "REASON=$reason"
    Log '  the app was not measured - this is a harness precondition, not a product result'
    $script:results | ConvertTo-Json | Set-Content (Join-Path $out 'result.json')
    Log "artifacts: $out"
    exit 4
}

# "true"/"false" exactly as the closure evidence prints them.
function Format-Bool([bool]$value) { if ($value) { 'true' } else { 'false' } }

# The approved library exactly as the app reports it, keyed by sourceId, with the number of videos
# approved inside each source. GET /playlists is the authority for what may play.
#
# A hashtable, deliberately, and the response is assigned to a variable before anything is done with it:
# PowerShell 5.1's Invoke-RestMethod hands a JSON array back as ONE object that is not enumerated into
# the pipeline, so `@(Invoke-RestMethod ...)` produces a list *containing* a list. That nesting is silent
# - `$sources.Count` reads 1, `$sources[0]` is the whole list and `$sources[0].videoCount` is an array -
# and it is what made the queue probe's count a failed cast the first time this was rehearsed against the
# TV. A hashtable cannot be unrolled at all, so every caller gets the same, predictable object.
function Get-ApprovedSourceMap {
    $map = @{}
    if (-not $headers.ContainsKey('Authorization')) { return $map }
    try {
        $response = Invoke-RestMethod -Uri "http://${apiHost}:8080/playlists" -Headers $headers -TimeoutSec 10
    } catch {
        Log "  could not read the approved sources from GET /playlists: $($_.Exception.Message)"
        return $map
    }
    foreach ($source in $response) {
        if ($source -and $source.sourceId) { $map["$($source.sourceId)"] = $source }
    }
    return $map
}

# One approved source by its YouTube id, or $null: an id that GET /playlists does not list is not an
# approved source, whatever else the app may be playing.
function Get-ApprovedSource([string]$sourceId) {
    if ([string]::IsNullOrWhiteSpace($sourceId)) { return $null }
    $map = Get-ApprovedSourceMap
    if ($map.ContainsKey($sourceId)) { return $map[$sourceId] }
    return $null
}

# The app's actual current playback state. ApiState swallows every failure and returns $null, and the
# embedded server drops the occasional request, so "the status call failed" must not be read as "nothing
# is playing": the read is retried and the reason for an unreadable state is reported.
function Get-PlaybackState([string]$why, [int]$Attempts = 5) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        $state = ApiState
        if ($state) {
            if ($state.currentlyPlaying) { return $state.currentlyPlaying }
            Log "  [$why] GET /status reports nothing playing (currentlyPlaying=null)"
            return $null
        }
        Log "  [$why] GET /status unreadable (attempt $($i + 1) of $Attempts)"
        Start-Sleep -Seconds 2
    }
    Log "  [$why] GET /status stayed unreadable for $Attempts attempts"
    return $null
}

# Starts an APPROVED video and returns the state the app then reports for it, or $null when none could be
# started. The video/playlist pairs are discovered from the app's own recent play events
# (GET /stats/recent) and filtered to sources that are approved right now, so no id is ever hard-coded
# here and a source a parent has since removed is skipped rather than used.
#
# A source holding several approved videos is tried first, because that is the queue the next/previous
# witness needs. Inside it the OLDEST recorded item is tried first: GET /stats/recent is newest-first,
# and its newest item is where the previous phase's own queue advance stopped, which makes it the item
# most likely to BE the end of the queue - and NEXT from the end of a queue legitimately ends playback
# instead of moving on.
function Start-ApprovedPlayback([string]$why, [string[]]$Skip = @()) {
    $approvedBySource = Get-ApprovedSourceMap
    if ($approvedBySource.Count -eq 0) {
        Log "  [$why] no approved source is registered, so there is no approved video to start"
        return $null
    }

    $recent = $null
    try {
        $recent = Invoke-RestMethod -Uri "http://${apiHost}:8080/stats/recent" -Headers $headers -TimeoutSec 10
    } catch {
        Log "  [$why] could not read GET /stats/recent: $($_.Exception.Message)"
    }

    $multi = @(); $single = @(); $seen = @{}
    # Oldest first: $recent is newest-first and a JSON array arrives as one un-enumerated object, so it is
    # indexed rather than wrapped (see Get-ApprovedSourceMap).
    for ($i = @($recent).Count - 1; $i -ge 0; $i--) {
        $event = @($recent)[$i]
        if (-not $event.videoId -or -not $event.playlistId) { continue }
        if ($seen.ContainsKey("$($event.videoId)")) { continue }
        $source = $null
        if ($approvedBySource.ContainsKey("$($event.playlistId)")) { $source = $approvedBySource["$($event.playlistId)"] }
        # Played once but not approved any more: the app would refuse it, so it is not a candidate.
        if (-not $source) { continue }
        $seen["$($event.videoId)"] = $true
        $candidate = [pscustomobject]@{
            videoId    = "$($event.videoId)"
            playlistId = "$($event.playlistId)"
            videoCount = [int]$source.videoCount
        }
        if ($candidate.videoCount -gt 1) { $multi += $candidate } else { $single += $candidate }
    }

    foreach ($candidate in (@($multi) + @($single))) {
        if ($Skip -contains $candidate.videoId) { continue }
        Log "  [$why] starting approved video $($candidate.videoId) from source $($candidate.playlistId) ($($candidate.videoCount) approved videos)"
        $logBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
        PlayVideo $candidate.videoId $candidate.playlistId
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Seconds 2
            $state = ApiState
            if ($state -and $state.currentlyPlaying -and ($state.currentlyPlaying.videoId -eq $candidate.videoId)) {
                # While the resume offer is up it owns the D-pad, so the queue keys would move the offer
                # instead of the player. It withdraws itself after a few seconds; this dismisses it
                # early, and only while the fresh log says it is actually up - a stray BACK with nothing
                # open would leave the player entirely, which is how earlier phases went wrong.
                $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $logBase) -join "`n"
                if ($fresh -match 'Menu opened: RESUME' -and $fresh -notmatch 'Resume offer withdrawn') {
                    Log "  [$why] a resume offer is up - dismissing it so the queue keys reach the player"
                    Key 'KEYCODE_BACK'
                    Start-Sleep -Seconds 2
                }
                return (Get-PlaybackState $why 3)
            }
        }
        Log "  [$why] $($candidate.videoId) did not reach the player - trying the next approved item"
        Adb @('shell', "am broadcast -a $pkg.DEBUG_STOP_PLAYBACK -p $pkg") | Out-Null
        Start-Sleep -Seconds 3
    }
    return $null
}

# Waits for the app to report a DIFFERENT video playing, and returns the state it reported. $null means
# playback stopped, which is itself an honest queue outcome rather than something a fixed sleep should
# hide. A dropped status request is retried, never believed.
function Wait-PlayingChange([string]$Before, [int]$TimeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $state = ApiState
        if (-not $state) { continue }
        $last = $state.currentlyPlaying
        if (-not $last) { return $null }
        if ($last.videoId -and ($last.videoId -ne $Before)) { return $last }
    }
    return $last
}

# Waits until the resume offer is out of the way before a queue key is pressed, judged from the app's own
# log. While it is up the offer owns the D-pad: a NEXT press moves the offer instead of the queue, and a
# BACK press only closes the offer - which is exactly what the first rehearsal of the BACK witness
# measured, with playback carrying on after BACK (TvPlayerScreen sends BACK to closeMenu() whenever a
# menu is open, and only to the player when none is).
#
# The offer is opened by the same prepare() call that logs "Playing <videoId>" and withdraws itself after
# the 8s menu idle window, so a window that never mentions it proves there is none to clear. It gates
# nothing, so it is waited out rather than raced.
function Clear-ResumeOffer([string]$why) {
    $base = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
    $deadline = (Get-Date).AddSeconds(20)
    $waited = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $waited++
        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $base) -join "`n"
        # Withdrawn means it lapsed by itself or a previous BACK closed it: either way it is gone.
        if ($fresh -match 'Resume offer withdrawn') { return }
        if (-not ($fresh -match 'Menu opened: RESUME')) {
            if ($waited -ge 10) { return }
            continue
        }
        if ($waited -ge 10) {
            Log "  [$why] the resume offer is still up - dismissing it (BACK closes the offer, not the player)"
            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 2
            if (-not (Get-PlaybackState $why)) {
                Log "  [$why] WARNING: playback is no longer running after dismissing the offer"
            }
            return
        }
    }
    Log "  [$why] the resume offer never cleared within the wait"
}

# Is this video authorized, by the app's own gate? PlaybackAuthorization approves a video only while it
# is present in the approved cache AND its source still exists, and the player's queue is built from
# exactly that cache. Three independent signals are required, so this is never inferred from "something
# is playing": the status API must report it playing from the approved queue source, the app must have
# recorded a play event for it under that source, and the fresh log must not say it was blocked.
function Test-VideoAuthorized([string]$videoId, [string]$sourceId, [int]$LogBase) {
    if ([string]::IsNullOrWhiteSpace($videoId) -or [string]::IsNullOrWhiteSpace($sourceId)) { return $false }
    $state = Get-PlaybackState 'authorization' 3
    if (-not $state) { return $false }
    if ($state.videoId -ne $videoId) {
        Log "  [authorization] GET /status reports $($state.videoId), not $videoId"
        return $false
    }
    if ($state.playlistId -ne $sourceId) {
        Log "  [authorization] $videoId is reported from source '$($state.playlistId)', not '$sourceId'"
        return $false
    }
    $event = $null
    try {
        # Assigned first, then piped: a JSON array from Invoke-RestMethod arrives as one un-enumerated
        # object, and Where-Object over the variable is what enumerates it (see Get-ApprovedSourceMap).
        $recent = Invoke-RestMethod -Uri "http://${apiHost}:8080/stats/recent" -Headers $headers -TimeoutSec 10
        $event = $recent | Where-Object { ($_.videoId -eq $videoId) -and ($_.playlistId -eq $sourceId) } | Select-Object -First 1
    } catch {
        Log "  [authorization] could not read GET /stats/recent: $($_.Exception.Message)"
        return $false
    }
    if (-not $event) {
        Log "  [authorization] no play event for $videoId under source $sourceId"
        return $false
    }
    $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $LogBase) -join "`n"
    if ($fresh -match ('Blocked playback of unapproved video: ' + [regex]::Escape($videoId))) { return $false }
    if ($fresh -match ('Blocked playback from removed source: ' + [regex]::Escape($sourceId))) { return $false }
    return $true
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

# A library precondition, not an advisory one. This loop used to give up quietly: the run carried on and
# the D-pad phase then reported "no approved video started", which reads as "the remote cannot play
# anything" while the real cause is a device with nothing approved on it. With nothing usable in the
# library there is no video any playback tier can play, so every later failure would say nothing about
# the app - the honest result is that the app was not measured.
#
# The registered sources are listed before failing, so "nothing registered at all" and "registered but
# never resolved" can be told apart from the artifact instead of guessed at.
#
# This is a distinct exit code (4) with its own result line: RESULT=HARNESS_PRECONDITION_FAILURE is not a
# product FAIL, and a caller must be able to tell the two apart.
if (-not $libraryReady) {
    if (-not $headers.ContainsKey('Authorization')) {
        Stop-HarnessPrecondition 'no dashboard session - the PIN could not be exchanged for a token, so the approved library cannot be read'
    }
    $registered = Get-ApprovedSourceMap
    Log "  approved sources registered: $($registered.Count)"
    foreach ($key in $registered.Keys) {
        $src = $registered[$key]
        Log "    source=$($src.sourceId) videos=$($src.videoCount) status=$($src.status)"
    }
    # The em dash is built from its code point so this file stays pure ASCII: PowerShell 5.1 reads a
    # BOM-less script as ANSI, which would mangle a literal dash and break the exact REASON text.
    Stop-HarnessPrecondition ('library empty ' + [char]0x2014 + ' re-seed before running playback tiers')
}

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

# ---------------------------------------------------------------- W6: the catalog hierarchy
#
# A category is a shelf *title*; a sub-category is a card that opens it. These checks use the app's own
# projection for what it intends to draw and uiautomator for what is actually on screen and focused, so
# a failure says which of the two is wrong. They run before the player phases and leave the app on the
# library with nothing playing.
function Get-DebugDump([string]$action, [string]$extra = '') {
    Adb @('logcat', '-c') | Out-Null
    $command = "am broadcast -f 0x01000000 -a $pkg.$action -p $pkg"
    if ($extra) { $command = "$command $extra" }
    Adb @('shell', $command) | Out-Null
    Start-Sleep -Seconds 3
    $lines = Adb @('logcat', '-d', '-s', 'SafeTube-Intent')
    # logcat splits a long entry into several lines, so the payload is reassembled before parsing.
    $text = ($lines | ForEach-Object { if ($_ -match 'D SafeTube-Intent: (.*)$') { $Matches[1] } else { $_ } }) -join ''
    $start = $text.IndexOfAny([char[]]@('[', '{'))
    if ($start -lt 0) { return $null }
    return $text.Substring($start)
}

# The labels of a focusable node and its whole subtree: a Compose card is a focusable container whose
# name lives on a child (the artwork's description and the title), so the card itself looks unlabelled.
function Get-UiFacts([string]$dumpPath) {
    $facts = [ordered]@{ Focused = ''; Focusable = @(); Texts = @(); Images = @(); Dump = '' }
    if (-not (Test-Path $dumpPath)) { return $facts }
    $raw = Get-Content $dumpPath -Raw
    # uiautomator writes XML, so a title containing '&' arrives as '&amp;'. Decoding the five entities
    # is what lets a card's title be compared with the title the app reported.
    $facts.Dump = $raw -replace '&amp;', '&' -replace '&quot;', '"' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&apos;', "'"
    try { [xml]$xml = $raw } catch { return $facts }
    $all = $xml.SelectNodes('//node')
    $focusable = @()
    foreach ($node in $all) {
        if ($node.text) { $facts.Texts += $node.text }
        # Artwork is an ImageView whose contentDescription is the card's title; a heading has no image.
        if ($node.class -eq 'android.widget.ImageView') { $facts.Images += $node.'content-desc' }
        if ($node.focusable -ne 'true') { continue }
        $labels = @()
        $stack = New-Object System.Collections.Stack
        $stack.Push($node)
        while ($stack.Count -gt 0) {
            $current = $stack.Pop()
            foreach ($child in $current.ChildNodes) {
                if ($child.NodeType -eq 'Element') { $stack.Push($child) }
            }
            if ($current.text) { $labels += $current.text }
            elseif ($current.'content-desc') { $labels += $current.'content-desc' }
        }
        $label = ($labels | Select-Object -Unique) -join ' / '
        $focusable += $label
        if ($node.focused -eq 'true') { $facts.Focused = $label }
    }
    $facts.Focusable = $focusable
    return $facts
}

# Whether a D-pad focus label belongs to a card with this exact title. A label is the card's own texts
# joined with ' / ' (the artwork's description and the title), so a *substring* test is not enough:
# "Wheels on the Bus | @CoComelon ..." would otherwise match a card called "CoComelon".
function Test-CardLabel([string]$label, [string]$title) {
    if (-not $label -or -not $title) { return $false }
    foreach ($part in ($label -split ' / ')) {
        $trimmed = $part.Trim()
        if ($trimmed -eq $title -or $trimmed -eq "[$title]") { return $true }
    }
    return $false
}

# What is playing right now, from the app's own status endpoint.
function Get-W6Playing {
    $state = ApiState
    if ($state -and $state.currentlyPlaying) { return $state.currentlyPlaying }
    return $null
}

function Go-ToLibrary([string]$why) {
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        Dump "w6-library-$attempt"
        $facts = Get-UiFacts (Join-Path $out "w6-library-$attempt.xml")
        if ($facts.Dump -match 'SafeTube for Kids' -and $facts.Dump -match 'Refresh') { return $true }
        Log "  $why - returning to the library from whatever screen is up"
        Key 'KEYCODE_BACK'
        Start-Sleep -Seconds 2
    }
    return $false
}

EnsureApp 'w6' | Out-Null
if (Go-ToLibrary 'before the hierarchy checks') {
    Log '=== W6: categories are titles, sub-categories are cards ==='
    $projection = Get-DebugDump 'DEBUG_DUMP_CATALOG_UI'
    $model = $null
    if ($projection) { try { $model = $projection | ConvertFrom-Json } catch { $model = $null } }

    if (-not $model) {
        Record 'w6-catalog-projection' $false 'the app did not report its catalogue projection'
    } else {
        $shelf = $model.shelves | Where-Object { $_.id -ne 'shelf-continue-watching' } | Select-Object -First 1
        if (-not $shelf -or $shelf.cards.Count -eq 0) {
            Record 'w6-catalog-projection' $false 'no shelf with cards to navigate'
        } else {
            $first = $shelf.cards[0]
            $shelfLine = ($shelf.cards | ForEach-Object { "$($_.title)[$($_.kind)]" }) -join ' '
            Log "  shelf '$($shelf.title)': $shelfLine"
            # Remembered for the D-pad phase below, which then knows whether the first press of Enter
            # opens a container or starts a video - a guess about that route is what made it flaky.
            $script:w6FirstCardIsContainer = ($first.kind -eq 'CONTAINER')

            Dump 'w6-a-home'
            $homeFacts = Get-UiFacts (Join-Path $out 'w6-a-home.xml')

            # 0. W6.1: a category heading is text and nothing else. Checked from both sides - the app's
            # own projection must report no heading picture for any shelf, and on screen every image must
            # belong to a card. A card's artwork carries that card's title as its description, so an
            # image with no description at all is exactly what a heading decoration would be, and an
            # image described with the shelf's own title would be a category picture by another route.
            $shelvesWithArtwork = @($model.shelves | Where-Object { $_.hasArtwork })
            Record 'w6-1-category-heading-has-no-picture' ($shelvesWithArtwork.Count -eq 0) `
                "shelves carrying a heading picture: $($shelvesWithArtwork.Count) of $($model.shelves.Count)"

            $describedImages = @($homeFacts.Images | Where-Object { $_ })
            $anonymousImages = @($homeFacts.Images | Where-Object { -not $_ })
            $headingImages = @($homeFacts.Images | Where-Object { $_ -eq $shelf.title })
            $noCategoryImage = ($anonymousImages.Count -eq 0) -and ($headingImages.Count -eq 0)
            Record 'w6-1-category-heading-has-no-image' $noCategoryImage `
                "images on screen: $($homeFacts.Images.Count) ($($describedImages.Count) described as a card, $($anonymousImages.Count) undescribed); none for '$($shelf.title)'"
            Log "  images on screen: $($describedImages -join ' | ')"

            # 1. the category title is a heading, and nothing focusable carries it
            $titleFocusable = @($homeFacts.Focusable | Where-Object { $_ -eq $shelf.title -or $_ -eq "[$($shelf.title)]" })
            Record 'w6-category-title-not-focusable' ($titleFocusable.Count -eq 0) `
                "shelf title '$($shelf.title)' is a heading; focusable labels: $($homeFacts.Focusable.Count)"

            # 2. the shelf's cards are focusable, in the configured order
            $orderedLabels = @()
            foreach ($card in $shelf.cards) {
                $orderedLabels += @($homeFacts.Focusable | Where-Object { Test-CardLabel $_ $card.title } | Select-Object -First 1)
            }
            $present = @($orderedLabels | Where-Object { $_ })
            Record 'w6-subcategory-is-a-card' ($first.kind -eq 'CONTAINER') "first card '$($first.title)' kind=$($first.kind)"
            Record 'w6-cards-are-focusable' ($present.Count -ge [Math]::Min(2, $shelf.cards.Count)) `
                "focusable cards on screen: $($present.Count) of $($shelf.cards.Count)"

            # 3. focus the first card with the D-pad alone
            $focused = ''
            for ($press = 0; $press -lt 6; $press++) {
                Key 'KEYCODE_DPAD_DOWN'
                Dump "w6-b-focus-$press"
                $facts = Get-UiFacts (Join-Path $out "w6-b-focus-$press.xml")
                if (Test-CardLabel $facts.Focused $first.title) { $focused = $facts.Focused; break }
            }
            Record 'w6-dpad-reaches-the-first-card' ($focused -ne '') "focused '$focused'"
            if ($focused -ne '') {
                Log "  D-pad focus: $focused"
                if ($first.kind -eq 'CONTAINER') {
                    # 4. Enter opens the container - and must not start playing anything
                    Key 'KEYCODE_DPAD_CENTER'
                    Start-Sleep -Seconds 4
                    $playingAfterEnter = Get-W6Playing
                    Record 'w6-container-card-does-not-autoplay' ($null -eq $playingAfterEnter) `
                        $(if ($null -eq $playingAfterEnter) { 'nothing started' } else { "started $($playingAfterEnter.videoId)" })

                    # 5. the container's own children are what is shown
                    $children = Get-DebugDump 'DEBUG_DUMP_CONTAINER_UI' "--es container_id $($first.containerId)"
                    $childModel = $null
                    if ($children) { try { $childModel = $children | ConvertFrom-Json } catch { $childModel = $null } }
                    $childTitles = @()
                    if ($childModel) { $childTitles = @($childModel.cards | ForEach-Object { $_.title }) }
                    Dump 'w6-c-container'
                    $inside = Get-UiFacts (Join-Path $out 'w6-c-container.xml')
                    # Only the cards that fit on screen are in the hierarchy dump, so the check is that
                    # the container's own name and its *first* child are there, and that this is the
                    # container's screen rather than the home screen (which carries the Refresh button).
                    $firstChild = if ($childTitles.Count -gt 0) { $childTitles[0] } else { '' }
                    $openedChildren = ($childTitles.Count -gt 0) -and
                        ($inside.Dump -match [regex]::Escape($first.title)) -and
                        ($inside.Dump -match [regex]::Escape($firstChild)) -and
                        ($inside.Dump -notmatch 'Refresh')
                    Record 'w6-container-opens-its-children' $openedChildren `
                        "container '$($first.title)' shows its first child '$firstChild' (of $($childTitles.Count))"
                    if ($childTitles.Count -eq 0) {
                        Log '  (the container is empty, so there is nothing to show inside it)'
                    }

                    # 6. Back returns to the shelf, with the same card focused again
                    Key 'KEYCODE_BACK'
                    Start-Sleep -Seconds 3
                    Dump 'w6-d-back'
                    $back = Get-UiFacts (Join-Path $out 'w6-d-back.xml')
                    Record 'w6-back-returns-to-the-shelf' (($null -eq (Get-W6Playing)) -and ($back.Dump -match 'Refresh')) `
                        'the library is back on screen and nothing is playing'
                    Record 'w6-back-restores-the-card-focus' (Test-CardLabel $back.Focused $first.title) `
                        "focused '$($back.Focused)'"
                } else {
                    Log '  the first card is a video, so the container checks do not apply to this catalog'
                }
            }

            # Leave the focus where the D-pad phase below expects to find it: on the top bar. The
            # hierarchy checks moved it onto a card, and a phase that assumes the library's starting
            # focus would otherwise drive from the wrong place.
            for ($up = 0; $up -lt 4; $up++) {
                Dump 'w6-e-topbar'
                $topFacts = Get-UiFacts (Join-Path $out 'w6-e-topbar.xml')
                if ($topFacts.Focused -match 'Refresh') { break }
                Key 'KEYCODE_DPAD_UP'
                Start-Sleep -Seconds 1
            }
        }
    }
} else {
    Record 'w6-catalog-projection' $false 'the app could not be returned to the library for the hierarchy checks'
}

Log "=== navigate with D-pad only ==="
function PlayingNow {
    $s = ApiState
    if ($s -and $s.currentlyPlaying) { return $s.currentlyPlaying }
    return $null
}

# Observation only: records exactly what the status API returned at the instant a playing-state
# assertion is evaluated, so a failure can be explained from evidence instead of guessed at.
#
# Why this is needed: `ApiState` swallows every error and returns $null, and `PlayingNow` returns
# $null when the state is unreadable, so `$null -eq (PlayingNow)` cannot distinguish "nothing is
# playing" from "the status call failed". This records the raw HTTP status, the raw body and the
# parsed fields, and never changes an assertion's outcome.
function Capture-PlayingState([string]$testName) {
    $stamp = (Get-Date).ToString('HH:mm:ss.fff')
    $url = "http://${apiHost}:8080/status"
    $httpStatus = $null; $raw = $null; $parseError = $null; $exception = $null; $np = $null
    try {
        $resp = Invoke-WebRequest -Uri $url -Headers $headers -TimeoutSec 10 -UseBasicParsing
        $httpStatus = [int]$resp.StatusCode
        $raw = $resp.Content
        try { $np = ($raw | ConvertFrom-Json).currentlyPlaying }
        catch { $parseError = $_.Exception.Message }
    } catch {
        $exception = $_.Exception.Message
        if ($_.Exception.Response) { $httpStatus = [int]$_.Exception.Response.StatusCode }
    }
    $entry = [ordered]@{
        timestamp        = $stamp
        test             = $testName
        url              = $url
        httpStatus       = $httpStatus
        parseError       = $parseError
        exception        = $exception
        appForeground    = (ForegroundPackage)
        currentlyPlaying = $np
        videoId          = $np.videoId
        playing          = $np.playing
        positionSec      = $np.positionSec
        durationSec      = $np.durationSec
        title            = $np.title
        raw              = $raw
    }
    ($entry | ConvertTo-Json -Depth 6 -Compress) | Add-Content -Path (Join-Path $out 'playing-state-diagnostics.jsonl') -Encoding utf8
    $desc = if ($null -eq $np) { 'null' } else { "vid=$($np.videoId) pos=$($np.positionSec)/$($np.durationSec) playing=$($np.playing)" }
    Log "  [diag:$testName] http=$httpStatus currentlyPlaying=$desc fg=$(ForegroundPackage)"
}

# W6: a shelf's first card may be a *sub-category*, and pressing it opens that container instead of
# starting anything - which is the correction W6 makes. The route therefore continues one level in:
# after a CENTER that did not start playback, descend into the container and press its first video.
# Every key is followed by a playback check, so no sequence can ever press a key on a running player.
$sequences = @()
# When the hierarchy checks saw a sub-category as the first card, the route is known: Enter opens it,
# then down onto its first video and Enter plays it. Trying that first keeps this phase deterministic
# instead of discovering the route by pressing keys and watching what happens.
if ($script:w6FirstCardIsContainer) {
    $sequences += , @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER')
}
$sequences += @(
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER'),
    @('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_CENTER')
)
$opened = $null
$route = @()
foreach ($sequence in $sequences) {
    $pressed = @()
    foreach ($keyCode in $sequence) {
        Key $keyCode
        $pressed += $keyCode
        Start-Sleep -Seconds 4
        $opened = PlayingNow
        if ($opened) {
            Log "  opened with remote sequence: $($pressed -join ' -> ')"
            break
        }
    }
    if ($opened) { $route = $pressed; break }
    Log "  no playback after sequence: $($sequence -join ' -> ')"
    # Whatever screen the failed sequence left behind, come back to the library before trying the next
    # route - and never press BACK blind *on* the library, because that would leave the app.
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        Dump 'nav-restore'
        $restorePath = Join-Path $out 'nav-restore.xml'
        $restoreDump = ''
        if (Test-Path $restorePath) { $restoreDump = Get-Content $restorePath -Raw }
        if ($restoreDump -match 'SafeTube for Kids' -and $restoreDump -match 'Refresh') { break }
        Key 'KEYCODE_BACK'
        Start-Sleep -Seconds 2
    }
}
Shot '02-player-opened'
$afterOpen = ApiState
if ($afterOpen) { $afterOpen | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'api-playing.json') }
Record 'dpad-opens-approved-video' ([bool]$opened)
if ($route.Count) { Log "  route used: $($route -join ' -> ')" }

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
    Capture-PlayingState 'stopped-before-security'
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
    Capture-PlayingState 'deeplink-plays-nothing'
    Record 'deeplink-plays-nothing' ($null -eq (PlayingNow))

    Adb @('shell', "am start -n $pkg/.MainActivity --es url 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'") | Out-Null
    Start-Sleep -Seconds 6
    Capture-PlayingState 'extras-cannot-start-playback'
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
    # The queue under test is the queue the app is ACTUALLY playing. It is resolved from the app's own
    # status API at the moment of the probe and then matched against the approved library.
    #
    # WHAT WAS WRONG (Phase 5 closure run 2026-09-25-005712): this probe read PlayingNow directly, but the
    # phases above deliberately STOP playback - the security checks broadcast DEBUG_STOP_PLAYBACK and then
    # assert "stopped-before-security". PlayingNow therefore returned $null, $current.playlistId was
    # empty, the /playlists lookup was skipped by its own "if ($current)" guard and $queueCount stayed 0,
    # so the probe printed "queue under test: source= videos=0" and then took the single-video branch,
    # whose "next-ends-at-queue-end" assertion passed vacuously against a stopped player. Nothing was
    # wrong with the library, the catalog, authorization, production playback or the queue: the probe was
    # reading a stopped player at a point where the harness itself had stopped it, and it called that
    # "NOT APPLICABLE".
    #
    # So the probe now establishes the state it means to observe: when nothing is playing, an approved
    # video is started (ids discovered from the app, never hard-coded), and the playlist id is then read
    # from GET /status - the actual current playback state - never from a variable captured earlier.
    # 1. Resolve the queue under test from the app's ACTUAL current playback state. The playlist id is
    #    read from GET /status and matched against GET /playlists; when that state does not resolve to a
    #    multi-item approved queue, an approved video is started from the approved library and the state
    #    is read again. The id always comes from the status API, never from a variable captured earlier.
    $current = Get-PlaybackState 'queue-under-test'
    $playlistLookupId = ''
    $approvedSource = $null
    $queueCount = 0
    for ($resolveAttempt = 1; $resolveAttempt -le 2; $resolveAttempt++) {
        $playlistLookupId = if ($current -and $current.playlistId) { "$($current.playlistId)" } else { '' }
        $approvedSource = Get-ApprovedSource $playlistLookupId
        $queueCount = if ($approvedSource) { [int]$approvedSource.videoCount } else { 0 }
        if ($queueCount -ge 2) { break }
        if ($resolveAttempt -gt 1) { break }
        # Name the exact cause, so the artifact distinguishes the three cases that used to print the same
        # blank line: nothing was playing, what is playing is no longer an approved source, and a source
        # that genuinely holds fewer than two approved videos.
        if (-not $current) {
            Log '  queue under test: nothing is playing - starting an approved video so the queue can be observed'
        } else {
            Log "  queue under test: the app is playing $($current.videoId), but that state does not resolve to a multi-item approved queue (source='$playlistLookupId', approved videos=$queueCount) - starting an approved video"
        }
        EnsureApp 'queue-under-test' | Out-Null
        $current = Start-ApprovedPlayback 'queue-under-test'
        if (-not $current) {
            Log '  queue under test: no approved video could be started'
            break
        }
    }

    $currentSourceText = '<no current playback state>'
    if ($playlistLookupId -and $approvedSource) {
        $currentSourceText = "$playlistLookupId ($($approvedSource.sourceType), $($approvedSource.videoCount) videos)"
    } elseif ($playlistLookupId) {
        $currentSourceText = "<not an approved source: GET /playlists does not list $playlistLookupId>"
    }

    # Diagnostic instrumentation in a safe form: identifiers, the approved source's own metadata and
    # counts. No PIN, authentication token, cookie or credential is printed.
    Log 'QUEUE_PROBE_DEBUG'
    Log "currentVideoId=$($current.videoId)"
    Log "currentPlaylistId=$($current.playlistId)"
    Log "currentTitle=$($current.title)"
    Log "currentSource=$currentSourceText"
    Log "playlistLookupId=$playlistLookupId"
    Log "queueCount=$queueCount"
    Log 'QUEUE_PROBE_STATE_ENDPOINT=/status (the current playback state)'
    Log 'QUEUE_PROBE_ENDPOINT=/playlists (the approved sources, matched on sourceId)'
    Log "QUEUE_SOURCE=$playlistLookupId"
    Log "QUEUE_SIZE=$queueCount"
    Log "  queue under test: source=$playlistLookupId videos=$queueCount"

    if ($queueCount -lt 2) {
        # A hard precondition, never a "NOT APPLICABLE": an unresolvable queue and a genuinely
        # single-video source used to print the same sentence, which is exactly how a harness bug read as
        # a product result. The multi-item next/previous witness needs a real queue.
        if ($queueCount -eq 1) {
            # A genuinely single-video source is still measured on its own terms first - the app must end
            # playback at the queue's end rather than ask YouTube for a recommendation - so that evidence
            # is recorded before the precondition stops the run.
            Key 'KEYCODE_MEDIA_NEXT'
            Start-Sleep -Seconds 6
            Record 'next-ends-at-queue-end' ($null -eq (PlayingNow)) 'single approved video: playback must stop'
        }
        Stop-HarnessPrecondition "queue under test is not multi-item: QUEUE_SOURCE='$playlistLookupId' QUEUE_SIZE=$queueCount (the multi-item NEXT/BACK witness requires QUEUE_SOURCE != blank and QUEUE_SIZE >= 2)"
    }

    # --- NEXT witness: the remote's own NEXT action must move the approved queue --------------------
    # The starting point matters: NEXT from the LAST item of a queue legitimately ends playback
    # ("Approved queue finished"), which says nothing about the queue having a next item - so when an
    # attempt starts on the queue's end, the witness restarts on another approved item of the SAME queue
    # instead of recording a false failure. The assertion itself is unchanged: the id must change.
    $nextBefore = ''; $nextAfter = ''; $nextChanged = $false; $authLogBase = 0
    $tried = @()
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if ($attempt -gt 1) {
            Log "  NEXT witness: attempt $attempt - restarting on another approved item of $playlistLookupId"
            EnsureApp 'next-witness' | Out-Null
            $current = Start-ApprovedPlayback 'next-witness' $tried
            if (-not $current) {
                Log '  NEXT witness: no other approved item could be started'
                break
            }
            if ($current.playlistId) { $playlistLookupId = "$($current.playlistId)" }
        }
        $nextBefore = "$($current.videoId)"
        $tried += $nextBefore
        # The queue keys only reach the player once the resume offer is out of the way.
        Clear-ResumeOffer 'next-witness'
        $authLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
        Capture-PlayingState 'next-is-approved-queue:before'
        Key 'KEYCODE_MEDIA_NEXT'
        $afterNext = Wait-PlayingChange $nextBefore 60
        $nextAfter = if ($afterNext) { "$($afterNext.videoId)" } else { '' }
        Capture-PlayingState 'next-is-approved-queue:after'
        $nextChanged = ($nextAfter -ne '') -and ($nextAfter -ne $nextBefore)
        Log "NEXT_BEFORE=$nextBefore"
        Log "NEXT_AFTER=$nextAfter"
        Log "NEXT_CHANGED=$(Format-Bool $nextChanged)"
        if ($nextChanged) { break }
        if ($nextAfter -eq '') {
            Log "  NEXT witness: NEXT from $nextBefore ended the queue (it was the queue's last item) - restarting on another approved item"
            continue
        }
        # Playback is still on the same video: the key did not move the queue. Retrying would only hide
        # that, so the witness reports it.
        Log "  NEXT witness: playback stayed on $nextBefore after NEXT - not retrying"
        break
    }

    Record 'next-is-approved-queue' $nextChanged "queue $nextBefore -> $nextAfter"
    $nextAuthorized = Test-VideoAuthorized $nextAfter $playlistLookupId $authLogBase
    Record 'next-item-is-authorized' $nextAuthorized "next item $nextAfter from source $playlistLookupId"
    Log "NEXT_AFTER_AUTHORIZED=$(Format-Bool $nextAuthorized)"
    Shot '04-next'

    # --- PREVIOUS must return to the video the queue came from ---
    if ($nextChanged) {
        Key 'KEYCODE_MEDIA_PREVIOUS'
        $afterPrev = Wait-PlayingChange $nextAfter 60
        $prevId = if ($afterPrev) { "$($afterPrev.videoId)" } else { '' }
        Record 'previous-is-approved-queue' ($prevId -eq $nextBefore) "queue $nextAfter -> $prevId"
    } else {
        Record 'previous-is-approved-queue' $false "no NEXT transition was witnessed, so PREVIOUS has no baseline"
    }

    # --- BACK witness: from playing, BACK must land on the library UI ------------------------------
    # Not inferred from the stale-session fix and not inferred from "nothing is playing any more": the app
    # must be in the FOREGROUND with the library actually on screen, and the BACK must be the press that
    # left the player - so any open menu (a resume offer on the video PREVIOUS just restarted) is cleared
    # first, or the BACK would only close that menu.
    Clear-ResumeOffer 'back-witness'
    $backBefore = Get-PlaybackState 'back-witness'
    $backFromPlayback = ($null -ne $backBefore) -and (-not ([string]::IsNullOrWhiteSpace($backBefore.videoId)))
    Log "BACK_FROM_PLAYBACK=$(Format-Bool $backFromPlayback)"
    if ($backFromPlayback) {
        Log "BACK_FROM_VIDEO=$($backBefore.videoId)"
        Key 'KEYCODE_BACK'
        Start-Sleep -Seconds 4
        Capture-PlayingState 'back-returns-to-library'
        $backPlaying = PlayingNow
        Record 'back-returns-to-library' ($null -eq $backPlaying)
        $foregroundAfterBack = ForegroundPackage
        if ($foregroundAfterBack -ne $pkg) { Start-Sleep -Seconds 3; $foregroundAfterBack = ForegroundPackage }
        Record 'back-foreground-is-app' ($foregroundAfterBack -eq $pkg) "foreground=$foregroundAfterBack"
        # A dump is the only way to see what is actually on screen. Taken after the API reads above,
        # because repeated uiautomator dumps can destabilise the app's embedded server.
        Dump '07-library-again'
        $backDump = ''
        $backDumpPath = Join-Path $out '07-library-again.xml'
        if (Test-Path $backDumpPath) { $backDump = (Get-Content $backDumpPath -Raw) }
        # The library is the app's own browsing UI. Since W6 that is either the catalogue home screen -
        # which carries the app title and the Refresh button - or the screen of the sub-category the
        # video was opened from, which carries the app title and a Back button. Both are the library;
        # BACK returning to the container the child came from is the corrected behaviour, not a leak.
        $libraryOnScreen = ($backDump -match 'SafeTube for Kids') -and
            (($backDump -match 'Refresh') -or ($backDump -match 'content-desc="Back"'))
        Record 'back-returned-to-library-ui' $libraryOnScreen
        $backReturned = ($foregroundAfterBack -eq $pkg) -and $libraryOnScreen -and ($null -eq $backPlaying)
        Log "BACK_RETURNED_TO_LIBRARY=$(Format-Bool $backReturned)"
        Log "FOREGROUND_AFTER_BACK=$foregroundAfterBack"
        Shot '07-library-again'
    } else {
        Log 'BACK_RETURNED_TO_LIBRARY=false'
        Log "FOREGROUND_AFTER_BACK=$(ForegroundPackage)"
        Record 'back-returns-to-library' $false 'no approved video was playing, so BACK could not be witnessed from playback'
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