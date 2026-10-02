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
    [ValidateSet('smoke', 'player', 'full', 'example')]
    [string]$Tier = 'full',
    [switch]$ClearState,
    [switch]$QualityProbe,
    [string]$Node = 'node',
    [int]$LaunchWaitSec = 25
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # repository root
$apk = Join-Path $repoRoot 'tv-app\app\build\outputs\apk\debug\app-debug.apk'

# Resolve adb to an actual executable path, never to a bare command name.
#
# The bare name is a trap in this script specifically: it defines a function called `Adb`, and
# PowerShell resolves a function before an application of the same name, so `& $adb` with $adb = 'adb'
# called that function from inside itself and recursed until PowerShell aborted the whole run with
# "The script failed due to call depth overflow" - before the first device check, and only when
# ANDROID_HOME was unset (with it set, a full path was used and everything worked). A resolved path
# cannot be shadowed by a function.
function Resolve-AdbExecutable([string]$explicit) {
    if ($explicit) {
        if ($explicit -match '[\\/]') { return $explicit }
        # An explicitly passed *name* has to be resolved too, or it re-opens the recursion above.
        $named = Get-Command $explicit -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($named) { return $named.Source }
        return ''
    }
    $roots = @()
    if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    # -CommandType Application cannot return this script's own function, so the result is always a path
    # to a real program. The extension is spelled out because PATHEXT is not applied to every lookup.
    foreach ($name in @('adb.exe', 'adb.cmd', 'adb.bat')) {
        $found = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.Source }
    }
    return ''
}
$adb = Resolve-AdbExecutable $Adb
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
    #
    # $adb is a resolved executable path (Resolve-AdbExecutable). It must stay that way: a bare 'adb'
    # here would be resolved by PowerShell's command lookup, which prefers this very function over
    # adb.exe, and the call would recurse until the run died with a call-depth overflow.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $adb -s $Serial @adbArgs 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
}
# Validation of the adb resolution path, and the first thing the run reports about itself: prove the
# resolved program answers before a single check depends on it, and record which binary was used. This is
# deliberately cheap - one `version` call - and it turns "adb is broken" into a BLOCKED run with a reason
# instead of a cascade of meaningless failures. It sits after Log/Adb are defined, because reporting the
# problem is the whole point of it.
if (-not $adb) {
    Log 'ADB_TEST: BLOCKED - no adb executable found: set ANDROID_HOME (or ANDROID_SDK_ROOT) or put adb.exe on PATH'
    exit 2
}
$adbVersion = ''
try { $adbVersion = ((& $adb version 2>&1) | Select-Object -First 1) } catch { $adbVersion = "unusable: $($_.Exception.Message)" }
if ("$adbVersion" -notmatch 'Android Debug Bridge') {
    Log "ADB_TEST: BLOCKED - $adb did not answer 'version' ($adbVersion)"
    exit 2
}
Log "adb: $adb [$adbVersion]"
# Every key press goes through here, and a press is only delivered when SafeTube is the focused app.
#
# This is not defensive decoration. The TV this runs against is a real living-room device with ZEE5,
# SonyLIV, Hotstar, JioTV, Shemaroo and others installed. A phase that presses keys while the app is
# not in front does not fail - it drives the launcher, and a CENTER on a tile *starts another app*,
# which then receives the rest of the run's input. That is what happened during W12.3: the launch left
# the launcher focused, the run pressed its way into ZEE5, and everything measured afterwards was the
# wrong program. When the app is not in front this relaunches it and DROPS the key, so a stray press
# can neither reach another app nor be spent on a screen the harness is not testing.
#
# The check is throttled and targeted so it does not cost a dumpsys per keystroke: at most every few
# seconds, and always before a key that activates something (CENTER/ENTER/play).
function Test-WrongAppInFront([string]$keyCode) {
    $activating = @('KEYCODE_DPAD_CENTER', 'KEYCODE_ENTER', 'KEYCODE_NUMPAD_ENTER', 'KEYCODE_MEDIA_PLAY_PAUSE', 'KEYCODE_BUTTON_A') -contains $keyCode
    if (-not $activating -and $script:lastForegroundCheck -and ((Get-Date) - $script:lastForegroundCheck).TotalSeconds -lt 4) {
        return $false
    }
    $script:lastForegroundCheck = Get-Date
    $front = ForegroundPackage
    if ($front -eq $pkg) { return $false }
    # A photo screen saver is not "the wrong app": relaunching the app behind it changes nothing and every
    # later key keeps going to the photo frame. Waking the TV ends the dream, so that is done first.
    if ($front -match 'screensaver|dream') {
        Log "  the screen saver is in front ($front) - waking the TV before delivering the key"
        Adb @('shell', 'input keyevent KEYCODE_WAKEUP') | Out-Null
        Start-Sleep -Seconds 3
        $front = ForegroundPackage
        if ($front -eq $pkg) { return $false }
    }
    Log "  NOT delivering ${keyCode}: $front is in front, not $pkg - relaunching the app instead"
    Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
    Start-Sleep -Seconds 12
    $script:wrongAppDrops = $script:wrongAppDrops + 1
    return $true
}
function Key([string]$code) {
    if (Test-WrongAppInFront $code) { return }
    Adb @('shell', 'input', 'keyevent', $code) | Out-Null
    Start-Sleep -Milliseconds 700
}
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
    $emptySamples = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $snap = PlayingNow
        if ($null -eq $snap) {
            # One empty sample is a hand-over, not a verdict - the app publishes no playing state between
            # one queue item and the next. Treating the first one as "playback stopped" is the same defect
            # as the one fixed in `Wait-PlayingChange` and in the end-of-video oracle; absence has to
            # persist before the caller's "the queue stopped" branch is taken. An unreadable status call
            # also lands here, so the wait continues rather than concluding anything from a dropped
            # request.
            $emptySamples++
            if ($emptySamples -ge 3) { return $null }
            continue
        }
        $emptySamples = 0
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

# ---------------------------------------------------------------- screen saver
# This TV runs a photo screen saver that takes the foreground when it is idle. A run against a device with
# the screen saver up is not a run with a failing assertion, it is a run that cannot touch the app at all:
# every key goes to the photo frame, `monkey` relaunches the app *behind* it, and the tier then dies with
# "the app could not be returned to the library" followed by "PLAYER_FEATURE_TEST: BLOCKED" - observed on
# the Mi Box in W13.2.1, where a 23:19 run was spent against `com.furnaghan.android.photoscreensaver` with
# every dump showing the photo frame's weather text. A focus test in particular cannot work through it.
# The switch is turned off for the run and put back exactly as found.
$screenSaverOriginal = ((Adb @('shell', 'settings get secure screensaver_enabled')) -join '').Trim()
if ($screenSaverOriginal -ne '0') {
    Adb @('shell', 'settings put secure screensaver_enabled 0') | Out-Null
    Log "screen saver: disabled for this run (was '$screenSaverOriginal')"
} else {
    Log "screen saver: already off ('$screenSaverOriginal')"
}
# Disabling it only stops the *next* activation, so a screen saver that is already up has to be dismissed:
# WAKEUP ends the dream, and it is sent before any phase needs the foreground.
$frontAtStart = ForegroundPackage
if ($frontAtStart -match 'screensaver|dream') {
    Log "  the screen saver is in front ($frontAtStart) - waking the TV so the app can take the foreground"
    Adb @('shell', 'input keyevent KEYCODE_WAKEUP') | Out-Null
    Start-Sleep -Seconds 3
    Adb @('shell', 'input keyevent KEYCODE_BACK') | Out-Null
    Start-Sleep -Seconds 3
}

# The screen saver is a preference of the household, not of the harness: put it back exactly as found.
function Restore-ScreenSaver {
    if ($screenSaverOriginal -and $screenSaverOriginal -ne '0') {
        Adb @('shell', "settings put secure screensaver_enabled $screenSaverOriginal") | Out-Null
        Log "screen saver: restored to '$screenSaverOriginal'"
    }
}

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

# W10: the PIN is no longer generated by the app and read out of its log - it is the parent's, stored
# as a verifier, and no API can return it. A harness that has to sign in therefore *installs* a PIN it
# knows, through the debug-only instrument built for exactly that. This is an operator action over adb
# on a debug build; the app itself never replaces a credential on its own.
#
# It happens *before* the launch that is measured, and the app is stopped again afterwards. A TV with no
# Parent PIN opens on first-run onboarding, and that decision is made when the navigation host is first
# composed - so installing the PIN after the launch would leave the measured run sitting in setup.
$pin = '482913'
# Addressed to the receiver component explicitly: a package-scoped implicit broadcast was not being
# delivered after a destructive reset, and the run then failed at "sign in" for a reason that had
# nothing to do with the app. The explicit form is also what the debug receiver is registered for.
Adb @('shell', "am force-stop $pkg") | Out-Null
Start-Sleep -Seconds 2
Adb @('shell', "am broadcast -a $pkg.DEBUG_SET_PIN -n $pkg/.debug.DebugReceiver --es pin $pin") | Out-Null
Start-Sleep -Seconds 3
$pinLine = (Adb @('logcat', '-d', '-s', 'SafeTube-Intent')) -join "`n"
$pinReady = ([regex]::Matches($pinLine, '"configured":true') | Measure-Object).Count -gt 0
Log "dashboard pin installed: $pinReady"

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

$headers = @{}
if ($pinReady) {
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
# Waits for the video that is playing to become a different one, or for playback to have genuinely
# stopped.
#
# An empty status sample is not an answer. The app clears its published playing state between the end of
# one queue item and the start of the next (`endEvent` publishes nothing until the next `startEvent`), so
# a single sample taken during that hand-over says "nothing is playing" while the app is in fact moving
# to the next item. Returning on the first one is how PREVIOUS was recorded as "no transition" in a run
# where the check immediately after it found playback back on the very item PREVIOUS had been asked for
# (W13.1a, full run 3: "queue pRn3fdmSY7w -> ", then BACK_FROM_VIDEO=Eqo0U_VkhR0). Absence therefore has
# to persist before it counts, which keeps the "the queue ended" branch below working while no longer
# racing the hand-over.
#
# An unreadable status call is different again (`ApiState` returns $null for that, and the loop skips it)
# so a dropped request can never be mistaken for a stop either.
function Wait-PlayingChange([string]$Before, [int]$TimeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    $emptySamples = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $state = ApiState
        if (-not $state) { continue }
        $last = $state.currentlyPlaying
        if ($last) {
            $emptySamples = 0
            if ($last.videoId -and ($last.videoId -ne $Before)) { return $last }
        } else {
            $emptySamples++
            if ($emptySamples -ge 3) { return $null }
        }
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
if (-not $libraryReady -and $Tier -ne 'example') {
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
} elseif (-not $libraryReady) {
    # The example tier is the tier that *loads* a library, so an empty one is its input, not a reason to
    # stop. This came up the moment the instrumented tests were run: they uninstall the app and wipe the
    # fixture, as documented, and the reload run then refused to start because the library it was about
    # to create did not exist yet.
    Log '  no library yet - that is this tier''s own job (it loads the canonical example library below)'
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
# The *deepest* focused node: the focus target, as opposed to an ancestor that also reports focused.
#
# uiautomator marks every ancestor of the focused node focused="true" as well, and on a Compose screen the
# full-screen surface container is reported focused whenever any descendant holds focus. The last focused
# node in document order therefore describes what a subtree contains rather than where the remote is -
# during W13.2 it reported the surface with the transport row's label attached to it, which reads exactly
# like "the row is focused" while the row is not. The target is the smallest focused rectangle, and its
# label comes from the node itself or, for a Compose icon, from the child that carries the description.
function Get-DeepestFocused([string]$dumpPath) {
    if (-not (Test-Path $dumpPath)) { return $null }
    $raw = Get-Content $dumpPath -Raw
    $decoded = $raw -replace '&amp;', '&' -replace '&quot;', '"' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&apos;', "'"
    try { [xml]$xml = $decoded } catch { return $null }
    $best = $null
    $bestArea = [int]::MaxValue
    foreach ($node in $xml.SelectNodes('//node')) {
        if ($node.focused -ne 'true') { continue }
        $m = [regex]::Match("$($node.bounds)", '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) { continue }
        $area = ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value) * ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value)
        if ($area -ge $bestArea) { continue }
        $bestArea = $area
        $best = $node
    }
    if (-not $best) { return $null }
    $label = "$($best.'content-desc')"
    if (-not $label) { $label = "$($best.text)" }
    if (-not $label) {
        # A Compose icon is a child of the focusable wrapper, so the wrapper itself looks unlabelled.
        $child = $best.SelectNodes('.//node') | Where-Object { $_.'content-desc' -or $_.text } | Select-Object -First 1
        if ($child) {
            $label = if ($child.'content-desc') { "$($child.'content-desc')" } else { "$($child.text)" }
        }
    }
    return [pscustomobject]@{
        Label  = $label
        Bounds = "$($best.bounds)"
        Area   = $bestArea
        Class  = "$($best.class)"
    }
}
# A focus test needs one dump per D-pad step, and a single uiautomator dump can come back empty or
# unparseable. That has to be reported as unreadable, never as "focus is not on the row".
function Get-FocusTarget([string]$name) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $file = "$name-$attempt"
        Dump $file
        $target = Get-DeepestFocused (Join-Path $out "$file.xml")
        if ($target) { return $target }
        Log "    [$name] uiautomator dump attempt $attempt produced no focused node - retrying"
    }
    return $null
}
# What the transport row calls its controls, in D-pad order. The middle one is a single control that
# labels itself after what pressing it does, so a playing video shows 'Pause' where a paused one shows
# 'Play'; both are the toggle.
$script:TransportLabels = @('Previous', 'Rewind 10 seconds', 'Play/Pause', 'Forward 10 seconds', 'Next')
function Get-TransportLabel($target) {
    if (-not $target) { return '' }
    $label = "$($target.Label)"
    if ($label -eq 'Play' -or $label -eq 'Pause') { return 'Play/Pause' }
    return $label
}
# logcat keeps about 4 KB of a single log entry, so a dump of a whole 46-node library arrives cut
# in half. Closing the brackets of the longest prefix that is still well-formed turns that into
# "the part that fits", which is what the assertions below are written against - and the payloads
# that must be complete (one container, one screen) are small enough to arrive whole.
function ConvertFrom-DumpJson([string]$raw) {
    if (-not $raw) { return $null }
    try { return $raw | ConvertFrom-Json } catch { }
    for ($cut = $raw.Length; $cut -gt 2; $cut--) {
        if ($raw[$cut - 1] -ne '}' -and $raw[$cut - 1] -ne ']') { continue }
        $candidate = $raw.Substring(0, $cut)
        $stack = New-Object System.Collections.Stack
        $inString = $false
        for ($i = 0; $i -lt $candidate.Length; $i++) {
            $ch = $candidate[$i]
            if ($ch -eq '"' -and ($i -eq 0 -or $candidate[$i - 1] -ne '\')) { $inString = -not $inString; continue }
            if ($inString) { continue }
            if ($ch -eq '{' -or $ch -eq '[') { $stack.Push($ch) }
            elseif ($ch -eq '}' -or $ch -eq ']') { if ($stack.Count) { $stack.Pop() | Out-Null } }
        }
        if ($stack.Count -eq 0) { continue }
        $repaired = $candidate
        while ($stack.Count -gt 0) { $repaired += if ($stack.Pop() -eq '{') { '}' } else { ']' } }
        try { return $repaired | ConvertFrom-Json } catch { }
    }
    return $null
}
# One uiautomator dump, up to three attempts: an unreadable dump must be reported as unknown, not
# turned into a missing card.
function Get-W11Focused([string]$name) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $file = "$name-$attempt"
        Dump $file
        $facts = Get-UiFacts (Join-Path $out "$file.xml")
        if ($facts -and $facts.Focusable.Count -gt 0) { return $facts }
    }
    return $null
}
# Walks the library downwards until a card with one of these titles is focused, and says which
# title it found. One dump per press, so the walk cannot pass the target without noticing.
function Find-W11Card([string[]]$titles, [int]$MaxPresses = 14) {
    for ($i = 0; $i -le $MaxPresses; $i++) {
        $script:w11Dumps++
        $facts = Get-W11Focused "w11-find-$($script:w11Dumps)"
        if ($facts) {
            $found = Test-W11AnyCardLabel $facts.Focused $titles
            if ($found) { return [pscustomobject]@{ Title = $found; Label = $facts.Focused; Presses = $i } }
        }
        Key 'KEYCODE_DPAD_DOWN'
    }
    return $null
}
# From anywhere in a row, the leftmost card: LEFT stops at the row's first card.
# Focus parked at the top-left of whatever screen is up: every walk below is relative to that, and
# after a relaunch the app decides for itself where focus starts.
function Park-W11Focus {
    for ($i = 0; $i -lt 8; $i++) { Key 'KEYCODE_DPAD_UP' }
    for ($i = 0; $i -lt 8; $i++) { Key 'KEYCODE_DPAD_LEFT' }
}
function Test-W11Title([string]$got, [string]$want) {
    if ($got -eq $want) { return 'exact' }
    $strip = { param($text) ($text -replace '[^\x20-\x7E]', '') -replace '\s+', ' ' }
    if ((& $strip $got).Trim() -eq (& $strip $want).Trim()) { return 'ascii' }
    return 'no'
}
function Test-W11CardLabel([string]$label, [string]$title) {
    if (Test-CardLabel $label $title) { return $true }
    if (-not $label -or -not $title) { return $false }
    foreach ($part in ($label -split ' / ')) {
        if ((Test-W11Title $part.Trim() $title) -ne 'no') { return $true }
    }
    return $false
}
function Test-W11AnyCardLabel([string]$label, [string[]]$titles) {
    foreach ($title in $titles) { if (Test-W11CardLabel $label $title) { return $title } }
    return $null
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

# ---------------------------------------------------------------- W11: the example kids library
#
# One fixture, loaded and then verified from both ends: the catalog file in
# `scripts/fixtures/example-kids-library.yaml` and what the TV actually stored, draws, focuses and
# plays. The library is loaded first (so the tier is reproducible on its own), then every claim is
# checked against the app - its own debug projection, its own Room rows, the status API, the screen
# itself - and every key that navigates is a remote key. No touch input is used anywhere here.
#
# The expectations are not written into this script twice: they are read from the fixture's manifest,
# which is generated from what YouTube actually returned. A title that changes upstream is therefore
# reported as a difference between the file and the TV, not silently tolerated.
if ($Tier -eq 'example') {
    $fixtureDir = Join-Path $PSScriptRoot 'fixtures'
    $fixtureYaml = Join-Path $fixtureDir 'example-kids-library.yaml'
    $fixtureJson = Join-Path $fixtureDir 'example-kids-library.json'

    foreach ($required in @($fixtureYaml, $fixtureJson)) {
        if (-not (Test-Path $required)) {
            Stop-HarnessPrecondition "the example library fixture is missing: $required"
        }
    }
    $manifest = Get-Content $fixtureJson -Raw -Encoding UTF8 | ConvertFrom-Json
    # The fixture's titles are the real ones, and the real ones contain emoji. PowerShell 5.1 decodes
    # a native command's output with the console code page unless it is told otherwise, which turns
    # every emoji in a dump into "?" and makes a correct TV look like it stored the wrong title. This
    # block therefore speaks UTF-8 end to end.
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8

    $w11NodeById = @{}
    $w11NameToNode = @{}
    $w11Children = @{}
    foreach ($n in $manifest.nodes) {
        $w11NodeById[$n.id] = $n
        if ($n.parentId) {
            if (-not $w11Children.ContainsKey($n.parentId)) { $w11Children[$n.parentId] = @() }
            $w11Children[$n.parentId] += $n
        }
    }
    foreach ($item in $manifest.items) { $w11NameToNode[$item.name] = $item }
    $w11TitleByVideoId = @{}
    foreach ($n in $manifest.nodes) { if ($n.youtubeVideoId) { $w11TitleByVideoId[$n.youtubeVideoId] = $n.title } }
    Log "=== W11: the example kids library ($($manifest.nodeCount) nodes, generated $($manifest.generated)) ==="


    # The dumps are addressed to the receiver component explicitly: a package-scoped implicit
    # broadcast is not always delivered after a reset, which is a harness failure that looks like an
    # app failure.
    function Get-W11Dump([string]$action, [string]$extra = '') {
        Adb @('logcat', '-c') | Out-Null
        $command = "am broadcast -a $pkg.$action -n $pkg/.debug.DebugReceiver"
        if ($extra) { $command = "$command $extra" }
        Adb @('shell', $command) | Out-Null
        Start-Sleep -Seconds 3
        $lines = Adb @('logcat', '-d', '-s', 'SafeTube-Intent')
        $text = ($lines | ForEach-Object { if ($_ -match 'D SafeTube-Intent: (.*)$') { $Matches[1] } else { $_ } }) -join ''
        $start = $text.IndexOfAny([char[]]@('[', '{'))
        if ($start -lt 0) { return $null }
        return $text.Substring($start)
    }
    function Get-W11Playing {
        $state = ApiState
        if ($state -and $state.currentlyPlaying) { return $state.currentlyPlaying }
        return $null
    }
    function Wait-W11Play([int]$TimeoutSec = 30) {
        for ($i = 0; $i -lt $TimeoutSec; $i++) {
            $playing = Get-W11Playing
            if ($playing) { return $playing }
            Start-Sleep -Seconds 1
        }
        return $null
    }
    function Wait-W11Video([string]$videoId, [int]$TimeoutSec = 30) {
        for ($i = 0; $i -lt $TimeoutSec; $i++) {
            $playing = Get-W11Playing
            if ($playing -and $playing.videoId -eq $videoId) { return $playing }
            Start-Sleep -Seconds 1
        }
        return $null
    }
    function Wait-W11NoPlay([int]$TimeoutSec = 20) {
        for ($i = 0; $i -lt $TimeoutSec; $i++) {
            if (-not (Get-W11Playing)) { return $true }
            Start-Sleep -Seconds 1
        }
        return $false
    }
    # A title that survives the trip. logcat and the console are a text channel, and an emoji can come
    # back as '?' through no fault of the app, so the comparison is made twice: exactly, and again on
    # what is left once the characters that channel cannot carry are removed. A title that differs in
    # its words fails either way.
    # How many uiautomator dumps this block takes, so a run that is slow can be explained from its log.
    $script:w11Dumps = 0

    function Go-W11Library([string]$why) {
        if (Go-ToLibrary $why) { Start-Sleep -Seconds 2; return $true }
        return $false
    }
    # A fresh launch, which is the only focus state this block assumes: the home screen composed from
    # the top, with focus on its own first card. Everything below walks *down* from there and verifies
    # at each step which card is focused, because a computed number of DOWN presses is not reliable -
    # measured, not assumed: pressing DOWN twice from the top landed on the third row, and a library
    # that scrolls makes the arithmetic worse, not better.
    # Clears the saved playheads, so Continue Watching is not a row while a phase navigates.
    #
    # Its cards carry the same titles as the shelves below it - a video that was watched is also a card
    # in its category - so a walk that looks for a title can land in the wrong row and press the wrong
    # thing. That is not hypothetical: it is how the player-controls check ended up driving a video from
    # a different source and then failing on a seek that raced a queue advance. This is the app's own
    # debug instrument, and this is what it is for.
    function Clear-W11Progress {
        Adb @('shell', "am broadcast -a $pkg.DEBUG_CLEAR_RESUME_POSITIONS -n $pkg/.debug.DebugReceiver") | Out-Null
        Start-Sleep -Seconds 3
    }

    function Restart-W11App([string]$why) {
        Log "  restarting the app ($why) so focus starts from the top of the library"
        Adb @('shell', "am force-stop $pkg") | Out-Null
        Start-Sleep -Seconds 3
        Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
        Start-Sleep -Seconds 18
        Clear-W11Progress
        return (ForegroundPackage) -eq $pkg
    }

    function Get-W11RowStart([string[]]$titles) {
        for ($i = 0; $i -lt 8; $i++) { Key 'KEYCODE_DPAD_LEFT' }
        $script:w11Dumps++
        $facts = Get-W11Focused "w11-rowstart-$($script:w11Dumps)"
        if (-not $facts) { return $null }
        return $facts
    }
    # `,@(...)` and not `@(...)`: PowerShell unrolls a one-element array on the way out of a function,
    # so the single video of the "One Video" shelf would arrive as a bare string - and `$titles[0]` on
    # a string is its first *character*, which is how this check once compared a card against "L".
    function Get-W11CardTitles([string]$containerId) {
        if (-not $w11Children.ContainsKey($containerId)) { return , @() }
        return , @($w11Children[$containerId] | Sort-Object { [int]$_.position } | ForEach-Object { $_.title })
    }
    # W12, D5: an oracle that can tell "the queue ended" from "playback stopped". It lives up here,
    # with the other helpers, because the playlist phase below uses it: PowerShell defines a function
    # when execution reaches it, so a helper defined after its first caller is a runtime error.
    function Test-W11OnLibraryScreen {
        $script:w11Dumps++
        $facts = Get-W11Focused "w11-screen-$($script:w11Dumps)"
        if (-not $facts) { return $null }
        return (($facts.Dump -match 'SafeTube for Kids') -and ($facts.Dump -match 'Refresh'))
    }
    function Get-W11QueueEnd([int]$TimeoutSec = 25, [switch]$NoWait) {
        $stopped = if ($NoWait) { -not (Get-W11Playing) } else { Wait-W11NoPlay $TimeoutSec }
        if (-not $stopped) {
            return [pscustomobject]@{ Verdict = 'STILL_PLAYING'; SaidFinished = $false; OnLibrary = $null }
        }
        $log = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
        $saidFinished = $log -match 'Approved queue finished'
        # "Out of the player", not necessarily home: a playlist opened from a collection returns to that
        # collection, and calling that PARTIAL would have failed a correct end-of-queue. Any catalogue
        # screen of this fixture counts - the home screen, or a container.
        $onLibrary = Test-W11OnLibraryScreen
        if (-not $onLibrary) {
            $script:w11Dumps++
            $outFacts = Get-W11Focused "w11-outofplayer-$($script:w11Dumps)"
            if ($outFacts) {
                $onLibrary = @($manifest.categoryOrder | Where-Object { $outFacts.Dump -match [regex]::Escape($_.title) }).Count -gt 0
            }
        }
        $verdict = if ($saidFinished -and $onLibrary) { 'ENDED' }
            elseif ($saidFinished -or $onLibrary) { 'PARTIAL_EVIDENCE' }
            else { 'STOPPED_WITHOUT_ENDING' }
        return [pscustomobject]@{ Verdict = $verdict; SaidFinished = $saidFinished; OnLibrary = $onLibrary }
    }


    # --- 1. load ---------------------------------------------------------------------------------
    Log '--- loading the example library (allow its sources, replace the catalog, sync the TV) ---'
    $loader = Join-Path $fixtureDir 'load-example-library.js'
    $loadOutput = & $Node $loader --host $apiHost --pin $pin --replace-sources 2>&1
    $loadOutput | Set-Content (Join-Path $out 'example-library-load.log') -Encoding UTF8
    $loadOk = ($LASTEXITCODE -eq 0) -and (($loadOutput -join "`n") -match 'LOADED')
    $loadOutput | Select-Object -Last 12 | ForEach-Object { Log "  $_" }
    Record 'EXAMPLE_LIBRARY_LOAD' $loadOk 'scripts/fixtures/load-example-library.js'

    $catalogNow = $null
    try { $catalogNow = Invoke-RestMethod -Uri "http://${apiHost}:8080/catalog" -Headers $headers -TimeoutSec 20 } catch { }
    Record 'EXAMPLE_LIBRARY_CATALOG_DOCUMENT' ($catalogNow -and @($catalogNow.nodes).Count -eq $manifest.nodeCount) `
        "server catalog version $(if ($catalogNow) { $catalogNow.catalogVersion } else { 'unreadable' }) holds $(if ($catalogNow) { @($catalogNow.nodes).Count } else { 0 }) of $($manifest.nodeCount) nodes"

    if (-not $loadOk) {
        Log 'RESULT=HARNESS_PRECONDITION_FAILURE'
        Log 'REASON=the example library could not be loaded onto the TV'
        $script:results | ConvertTo-Json | Set-Content (Join-Path $out 'result.json')
        exit 4
    }

    EnsureApp 'w11' | Out-Null
    Go-W11Library 'before the fixture checks' | Out-Null

    # --- 2. what the TV stored vs what the file says ----------------------------------------------
    $nodeRaw = Get-W11Dump 'DEBUG_DUMP_CATALOG_NODES'
    $nodeRaw | Set-Content (Join-Path $out 'example-library-rows.json') -Encoding UTF8
    $rows = ConvertFrom-DumpJson $nodeRaw
    if (-not $rows) {
        Record 'EXAMPLE_LIBRARY_ROWS' $false 'the TV did not report its catalog rows'
    } else {
        $byId = @{}
        foreach ($row in $rows) { $byId[$row.id] = $row }
        $checkable = 0; $differences = @(); $asciiOnly = 0
        foreach ($want in $manifest.nodes) {
            $got = $byId[$want.id]
            if (-not $got) { continue }   # beyond the part of the dump that survived logcat
            $checkable++
            $titleVerdict = Test-W11Title $got.title $want.title
            if ($titleVerdict -eq 'ascii') { $asciiOnly++ }
            if ($titleVerdict -eq 'no') { $differences += "$($want.id): title" }
            elseif ([int]$got.position -ne [int]$want.position) { $differences += "$($want.id): position $($got.position) vs $($want.position)" }
            elseif ([bool]$got.enabled -ne [bool]$want.enabled) { $differences += "$($want.id): enabled $($got.enabled) vs $($want.enabled)" }
            elseif ([string]$got.type -ne [string]$want.nodeType) { $differences += "$($want.id): type" }
            elseif (("$($want.parentId)") -ne ("$($got.parentId)")) { $differences += "$($want.id): parent" }
            elseif (("$($want.youtubeVideoId)") -ne ("$($got.videoId)")) { $differences += "$($want.id): videoId" }
        }
        $categories = @($rows | Where-Object { $_.type -eq 'CATEGORY' })
        $categoryOrder = @($categories | Sort-Object { [int]$_.position } | ForEach-Object { $_.title })
        $expectedOrder = @($manifest.categoryOrder | ForEach-Object { $_.title })
        $orderOk = ($categoryOrder.Count -eq $expectedOrder.Count) -and
            (($categoryOrder -join '|') -eq ($expectedOrder -join '|'))
        Record 'EXAMPLE_LIBRARY_ROWS' ($differences.Count -eq 0) `
            "$checkable fixture nodes arrived in the dump; differences: $(if ($differences.Count) { $differences -join '; ' } else { 'none' })$(if ($asciiOnly) { " ($asciiOnly titles compared equal once the characters logcat cannot carry were removed)" })"
        Record 'EXAMPLE_LIBRARY_CATEGORY_ORDER' $orderOk "on the TV: $($categoryOrder -join ' | ')"
    }

    # --- 3. what the TV draws --------------------------------------------------------------------
    $projection = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CATALOG_UI')
    if (-not $projection) {
        Record 'EXAMPLE_LIBRARY_SHELF_ORDER' $false 'the app did not report its catalogue projection'
        Record 'EXAMPLE_LIBRARY_EMPTY_SHELF_NOT_DRAWN' $false 'the app did not report its catalogue projection'
    } else {
        $shelfTitles = @($projection.shelves | ForEach-Object { $_.title })
        Log "  shelves ($($projection.shelfCount)): $($shelfTitles -join ' | ')"
        # Continue Watching is a shelf of its own and sits first when it exists; it is not a category,
        # so it is set aside before the category order is compared. The projection arrives cut when the
        # library is large, so the rows that did arrive must be a prefix of the file's order - while
        # the count, which arrives first and therefore whole, must match exactly.
        $categoryShelves = @($shelfTitles | Where-Object { $_ -ne 'Continue Watching' })
        $expectedVisible = @($manifest.categoryOrder | Where-Object { $_.id -ne 'ex-edge-empty' } | ForEach-Object { $_.title })
        $prefixOk = $true
        for ($i = 0; $i -lt $categoryShelves.Count; $i++) {
            if ($categoryShelves[$i] -ne $expectedVisible[$i]) { $prefixOk = $false }
        }
        $expectedCount = $expectedVisible.Count + $(if ($shelfTitles -contains 'Continue Watching') { 1 } else { 0 })
        Record 'EXAMPLE_LIBRARY_SHELF_ORDER' ($prefixOk -and ([int]$projection.shelfCount -eq $expectedCount)) `
            "$($categoryShelves.Count) category rows readable (in the file's order: $prefixOk), the app reports $($projection.shelfCount) shelves where the file's visible categories plus Continue Watching make $expectedCount"
        Record 'EXAMPLE_LIBRARY_EMPTY_SHELF_NOT_DRAWN' (-not ($shelfTitles -contains 'Empty Shelf (edge case)')) `
            "an empty category is stored but is not a row on the TV"
    }

    # --- 3b. the hidden item: stored, and filtered out of what the child sees ---------------------
    #
    # Asked of the container rather than of the whole node dump: a container dump arrives complete
    # (its card list is capped so that it can), while a 46-node library does not.
    $hidden = @($manifest.nodes | Where-Object { $_.enabled -eq $false })[0]
    $hiddenContainer = $hidden.parentId
    $hiddenSource = $w11NodeById[$hiddenContainer].youtubePlaylistId
    $containerDump = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CONTAINER_UI' "--es container_id $hiddenContainer")
    if (-not $containerDump) {
        Record 'EXAMPLE_LIBRARY_HIDDEN_ITEM' $false "the container $hiddenContainer did not report"
    } else {
        $cards = @($containerDump.cards)
        $titlePresent = @($cards | Where-Object { (Test-W11Title $_.title $hidden.title) -ne 'no' }).Count -gt 0
        $idPresent = @($cards | Where-Object { $_.videoId -eq $hidden.youtubeVideoId }).Count -gt 0
        # The source's approved size is the ceiling; one of its videos is hidden, so the container must
        # offer exactly one fewer card than the source holds.
        $source = Get-ApprovedSource $hiddenSource
        $expectedCards = if ($source) { [int]$source.videoCount - 1 } else { -1 }
        $countOk = $expectedCards -ge 0 -and [int]$containerDump.cardCount -eq $expectedCards
        Record 'EXAMPLE_LIBRARY_HIDDEN_ITEM' ((-not $titlePresent) -and (-not $idPresent) -and $countOk) `
            "'$($hidden.title)' is not among the cards (by id: $idPresent, by title: $titlePresent) and the container lists $($containerDump.cardCount) cards where the approved source holds $($expectedCards + 1)"
    }

    # --- 4. D-pad only: walk every category row, in the file's order ------------------------------
    $visible = @($manifest.categoryOrder | Where-Object { $_.id -ne 'ex-edge-empty' })
    if (-not (Restart-W11App 'before the navigation walk')) {
        Stop-HarnessPrecondition 'the app could not be relaunched for the navigation walk'
    }
    $rowEvidence = @(); $rowsFound = 0
    foreach ($category in $visible) {
        $titles = Get-W11CardTitles $category.id
        if (-not $titles.Count) { continue }
        $found = Find-W11Card $titles
        if (-not $found) { $rowEvidence += "$($category.title): never reached"; continue }
        $start = Get-W11RowStart $titles
        if ($start -and (Test-W11CardLabel $start.Focused $titles[0])) {
            $rowsFound++
            $rowEvidence += "$($category.title): '$($titles[0])' after $($found.Presses) DOWN"
        } else {
            $rowEvidence += "$($category.title): row start shows '$(if ($start) { $start.Focused } else { 'unreadable' })' instead of '$($titles[0])'"
        }
    }
    Record 'EXAMPLE_LIBRARY_DPAD_NAVIGATION' ($rowsFound -eq $visible.Count) `
        "$rowsFound of $($visible.Count) category rows reached and leftmost card focused ($($script:w11Dumps) dumps): $($rowEvidence -join ' | ')"

    # --- 5. one item from each of the four sources, and the player's own controls ------------------
    #
    # Played the way a child plays it: restart from the top, walk down to the category, step right to
    # the card, press it - and if that card is a collection, press its first video. The row walk
    # continues downwards, never upwards, so no number of presses is ever assumed twice.
    $playPlans = @(
        [ordered]@{ source = 'CoComelon'; shelf = 'ex-cocomelon'; cardIndex = 1; expect = 'EXAMPLE_COCOMELON_VIDEO_4'; container = $false },
        [ordered]@{ source = 'Bluey'; shelf = 'ex-bluey'; cardIndex = 0; expect = 'EXAMPLE_BLUEY_VIDEO_1'; container = $false },
        [ordered]@{ source = 'Peppa Pig'; shelf = 'ex-peppa'; cardIndex = 0; expect = 'EXAMPLE_PEPPA_PLAYLIST_ITEM_01'; container = $true },
        [ordered]@{ source = 'ChuChu TV'; shelf = 'ex-chuchu'; cardIndex = 1; expect = 'EXAMPLE_CHUCHU_LONG_TITLE'; container = $false }
    )
    if (-not (Restart-W11App 'before the four-source playback')) {
        Stop-HarnessPrecondition 'the app could not be relaunched for the playback phase'
    }
    $sourceResults = @(); $sourcesPlayed = 0; $lastPlayed = $null
    foreach ($plan in $playPlans) {
        $category = $manifest.categoryOrder | Where-Object { $_.id -eq $plan.shelf }
        $titles = Get-W11CardTitles $plan.shelf
        if (-not (Go-W11Library "before playing from $($plan.source)")) { $sourceResults += "$($plan.source): could not reach the library"; continue }
        $found = Find-W11Card $titles
        if (-not $found) { $sourceResults += "$($plan.source): the row was never reached"; continue }
        if (Get-W11RowStart $titles) { } else { $sourceResults += "$($plan.source): the row start was unreadable"; continue }
        for ($i = 0; $i -lt $plan.cardIndex; $i++) { Key 'KEYCODE_DPAD_RIGHT' }
        $expectedId = $w11NameToNode[$plan.expect].youtubeVideoId
        Key 'KEYCODE_DPAD_CENTER'
        Start-Sleep -Seconds 3
        if ($plan.container) {
            # The card opened a collection: its first video is one press below the heading row. This is
            # deliberately the same three presses the playlist phase uses to open the same container and
            # play the same first item, and that phase walks all 17 items - so if this one step fails,
            # the mechanism is shared and the evidence is not.
            Key 'KEYCODE_DPAD_DOWN'
            Key 'KEYCODE_DPAD_CENTER'
        }
        $playing = Wait-W11Video $expectedId 30
        if (-not $playing) {
            $other = Get-W11Playing
            $sourceResults += "$($plan.source): expected $expectedId, saw $(if ($other) { $other.videoId } else { 'nothing' })"
        } else {
            $approved = Get-ApprovedSource $playing.playlistId
            $sourcesPlayed++
            $lastPlayed = $playing
            $sourceResults += "$($plan.source): $($playing.videoId) from $($playing.playlistId) $(if ($approved) { '(an allowed source)' } else { '(NOT an allowed source)' })"
        }
    }
    Record 'EXAMPLE_LIBRARY_PLAYBACK_FOUR_SOURCES' ($sourcesPlayed -eq 4) ($sourceResults -join ' | ')

    # The player's own controls, on a video that has just started, then BACK and the focus the child
    # comes back to.
    #
    # Started fresh rather than on whatever the four-source phase left playing: that video can be
    # seconds from its end, and a seek near the end runs into the queue advancing instead of the
    # playhead moving - which is exactly how this check once read "seek: 135 -> 0" and failed while the
    # player was behaving correctly. The Bluey card is chosen because the fixture's Bluey videos are
    # long, so a ten-second seek is nowhere near the end of them.
    if (Restart-W11App 'before the player controls') {
        $blueyTitles = Get-W11CardTitles 'ex-bluey'
        $controlStart = $null
        if (Find-W11Card $blueyTitles) {
            Get-W11RowStart $blueyTitles | Out-Null
            Key 'KEYCODE_DPAD_CENTER'
            $controlStart = Wait-W11Play 30
        }
        if ($controlStart) {
            $controlId = $controlStart.videoId
            $controls = @()
            $pressesUsed = 0
            $paused = $null
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                Key 'KEYCODE_DPAD_CENTER'
                $pressesUsed++
                for ($i = 0; $i -lt 8; $i++) {
                    Start-Sleep -Seconds 1
                    $paused = Get-W11Playing
                    if ($paused -and -not $paused.playing) { break }
                }
                if ($paused -and -not $paused.playing) { break }
            }
            $controls += "pause after $pressesUsed press(es): playing=$(if ($paused) { $paused.playing } else { 'unreadable' })"
            $resumed = $null
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                Key 'KEYCODE_DPAD_CENTER'
                for ($i = 0; $i -lt 8; $i++) {
                    Start-Sleep -Seconds 1
                    $resumed = Get-W11Playing
                    if ($resumed -and $resumed.playing) { break }
                }
                if ($resumed -and $resumed.playing) { break }
            }
            $controls += "play: playing=$(if ($resumed) { $resumed.playing } else { 'unreadable' })"
            $beforeSeek = if ($resumed) { [int]$resumed.positionSec } else { 0 }
            Key 'KEYCODE_DPAD_RIGHT'
            Start-Sleep -Seconds 4
            $afterSeek = Get-W11Playing
            $sameVideo = $afterSeek -and $afterSeek.videoId -eq $controlId
            $controls += "seek: $beforeSeek -> $(if ($afterSeek) { $afterSeek.positionSec } else { 'unreadable' })$(if ($sameVideo) { '' } else { ' (on a different video)' })"
            $controlsOk = ($paused -and -not $paused.playing) -and ($resumed -and $resumed.playing) -and
                $sameVideo -and ([int]$afterSeek.positionSec -gt $beforeSeek + 5)
            Record 'EXAMPLE_LIBRARY_PLAYER_CONTROLS' $controlsOk ($controls -join ', ')

            Start-Sleep -Seconds 12
            $watched = Get-W11Playing
            $watchedId = if ($watched) { $watched.videoId } else { $controlId }
            # The fixture names most videos; a cache-created node is not in it, and the player's own
            # title is the honest fallback rather than an index into nothing.
            $watchedTitle = if ($w11TitleByVideoId.ContainsKey($watchedId)) { $w11TitleByVideoId[$watchedId] }
                elseif ($watched) { $watched.title } else { $controlId }
            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 4
            $stillInApp = (ForegroundPackage) -eq $pkg
            $stopped = Wait-W11NoPlay 15
            Record 'EXAMPLE_LIBRARY_BACK_LEAVES_PLAYER' ($stillInApp -and $stopped) `
                "still in the app: $stillInApp, playback stopped: $stopped"
            $script:w11Dumps++
            $facts = Get-W11Focused "w11-backfocus-$($script:w11Dumps)"
            Record 'EXAMPLE_LIBRARY_FOCUS_RESTORED' ($facts -and (Test-W11CardLabel $facts.Focused $watchedTitle)) `
                "after BACK the focused card is '$(if ($facts) { $facts.Focused } else { 'unreadable' })', and the video just watched is '$watchedTitle'"

            $projection = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CATALOG_UI')
            $cw = if ($projection) { $projection.shelves | Where-Object { $_.id -eq 'shelf-continue-watching' } | Select-Object -First 1 } else { $null }
            $cwTitles = if ($projection) { @($projection.shelves | ForEach-Object { $_.title }) } else { @() }
            $cwOk = $cw -and ($cwTitles.Count -gt 0) -and ($cwTitles[0] -eq 'Continue Watching') -and
                ($cw.cards[0].videoId -eq $watchedId)
            Record 'EXAMPLE_LIBRARY_CONTINUE_WATCHING' $cwOk `
                "first shelf '$(if ($cwTitles.Count) { $cwTitles[0] } else { 'none' })', first card $(if ($cw -and $cw.cards.Count) { $cw.cards[0].videoId } else { 'none' }) - expected the video just watched, $watchedId after $($watched.positionSec)s"
        } else {
            Record 'EXAMPLE_LIBRARY_PLAYER_CONTROLS' $false 'no video started from the Bluey row, so the controls could not be exercised'
            Record 'EXAMPLE_LIBRARY_BACK_LEAVES_PLAYER' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_FOCUS_RESTORED' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_CONTINUE_WATCHING' $false 'not reached'
        }
    } else {
        Record 'EXAMPLE_LIBRARY_PLAYER_CONTROLS' $false 'the app could not be relaunched for the controls'
        Record 'EXAMPLE_LIBRARY_BACK_LEAVES_PLAYER' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_FOCUS_RESTORED' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_CONTINUE_WATCHING' $false 'not reached'
    }

    # --- 6. a playlist from end to end: open, first item, advance, queue, last item ----------------
    $peppaItems = @($manifest.items | Where-Object { $_.name -like 'EXAMPLE_PEPPA_PLAYLIST_ITEM_*' } |
        Sort-Object { [int]$_.name.Split('_')[-1] })
    if (-not (Restart-W11App 'before the playlist walk')) {
        Stop-HarnessPrecondition 'the app could not be relaunched for the playlist phase'
    }
    $peppaTitles = Get-W11CardTitles 'ex-peppa'
    $containerDump = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CONTAINER_UI' '--es container_id ex-peppa-birthday')
    Record 'EXAMPLE_LIBRARY_PLAYLIST_OPEN' ($containerDump -and $containerDump.exists -and ([int]$containerDump.cardCount -eq $peppaItems.Count)) `
        "the file lists $($peppaItems.Count) items; the container reports cardCount $(if ($containerDump) { $containerDump.cardCount } else { 'unreadable' })"
    if (Find-W11Card $peppaTitles) {
        Get-W11RowStart $peppaTitles | Out-Null      # the collection is the row's first card
        Key 'KEYCODE_DPAD_CENTER'                    # open it
        Start-Sleep -Seconds 3
        Key 'KEYCODE_DPAD_DOWN'                      # onto the first video
        Key 'KEYCODE_DPAD_CENTER'
        $firstItem = Wait-W11Video $peppaItems[0].youtubeVideoId 30
        Record 'EXAMPLE_LIBRARY_PLAYLIST_FIRST_ITEM' ([bool]$firstItem) `

        # The negative half of the same oracle, and the point of W12's D5: asked while the first of 17
        # items is still playing, the three questions must NOT answer "the queue ended". An oracle that
        # cannot tell these apart is the one W11 had, where a dead queue passed as a finished one.
        $midQueue = Get-W11QueueEnd -TimeoutSec 1 -NoWait
        Record 'EXAMPLE_LIBRARY_QUEUE_END_ORACLE_NEGATIVE' ($midQueue.Verdict -ne 'ENDED') `
            "asked mid-queue the oracle answers $($midQueue.Verdict) (it must not answer ENDED while an item is playing)"
            "expected $($peppaItems[0].youtubeVideoId), saw $(if ($firstItem) { $firstItem.videoId } else { 'nothing' })"

        # The queue, one remote NEXT at a time, required to be the file's order. The playlist is short
        # on purpose: 17 items is long enough to prove the queue and short enough to reach its end.
        $walked = 0; $walkNotes = @()
        if ($firstItem) { $walked = 1 }
        for ($i = 1; $i -lt $peppaItems.Count; $i++) {
            Key 'KEYCODE_MEDIA_NEXT'
            $next = Wait-W11Video $peppaItems[$i].youtubeVideoId 25
            if (-not $next) {
                $saw = Get-W11Playing
                $walkNotes += "item $($i + 1) of $($peppaItems.Count): expected $($peppaItems[$i].youtubeVideoId), saw $(if ($saw) { $saw.videoId } else { 'nothing' })"
                break
            }
            $walked++
        }
        Record 'EXAMPLE_LIBRARY_PLAYLIST_ADVANCE' ($walked -eq $peppaItems.Count) `
            "$walked of $($peppaItems.Count) items, in the file's order $(if ($walkNotes.Count) { '- ' + ($walkNotes -join '; ') })"

        Key 'KEYCODE_MEDIA_NEXT'
        # The last item: one more NEXT must end the queue rather than start anything else. "Ended" is
        # asked of the app three ways (nothing playing, the app's own "queue finished" line, the player
        # screen gone) rather than inferred from one empty status, which is what W11 complained about:
        # a queue that silently died read exactly like a queue that finished.
        $beforeEnd = Get-W11Playing
        $endingVerdict = Get-W11QueueEnd 25
        Record 'EXAMPLE_LIBRARY_PLAYLIST_FINAL_ITEM' (($walked -eq $peppaItems.Count) -and $endingVerdict.Verdict -eq 'ENDED') `
            "after the last of $($peppaItems.Count) items: verdict=$($endingVerdict.Verdict), the app said the queue finished: $($endingVerdict.SaidFinished), library on screen: $($endingVerdict.OnLibrary)"
        Shot 'w11-playlist-end'

        # --- 7. the security boundary, from the child's own card ---------------------------------
        Go-W11Library 'before the security check' | Out-Null
        Clear-W11Progress
        if (Find-W11Card $peppaTitles) {
            Get-W11RowStart $peppaTitles | Out-Null
            Key 'KEYCODE_DPAD_RIGHT'                 # the row's second card is the unapproved video
            Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 6
            $playing = Get-W11Playing
            $script:w11Dumps++
            $facts = Get-W11Focused "w11-denied-$($script:w11Dumps)"
            $screen = if ($facts) { $facts.Dump } else { '' }
            $messageShown = ($screen -match "can't be played") -or ($screen -match 'could not') -or ($screen -match 'not approved')
            Record 'EXAMPLE_LIBRARY_UNAPPROVED_DENIED' (-not $playing) `
                "pressing the unapproved card played: $(if ($playing) { $playing.videoId } else { 'nothing' }) - it is in the library and its source is not allowed"
            Record 'EXAMPLE_LIBRARY_UNAPPROVED_MESSAGE' ((-not $playing) -and $messageShown) `
                "the refusal is on screen: $messageShown"
            Shot 'w11-unapproved-denied'

            # W12, D7: the lead was that a rejected video leaves the *previous* video's surface and title
            # behind, because the rejected branch never stops or clears the player. Asked here as state,
            # which is what a remote-only harness can see: no stale title anywhere on screen, BACK leaves
            # the player and lands on the library, focus comes back to a card that is really there, and
            # Continue Watching does not gain a row for a video that never played.
            $staleTitle = $w11NameToNode['EXAMPLE_COCOMELON_VIDEO_1'].title
            $noStaleTitle = -not ($screen -match [regex]::Escape($staleTitle))
            Record 'EXAMPLE_LIBRARY_REJECTED_NO_STALE_TITLE' $noStaleTitle `
                "the last video that played is '$staleTitle' and it is $(if ($noStaleTitle) { 'not' } else { 'STILL' }) on screen while the refusal is shown"

            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 4
            $backOnLibrary = Test-W11OnLibraryScreen
            Record 'EXAMPLE_LIBRARY_REJECTED_BACK_WORKS' ([bool]$backOnLibrary) `
                "BACK from the refused video leaves the library on screen: $backOnLibrary"

            if ($backOnLibrary) {
                $script:w11Dumps++
                $refocus = Get-W11Focused "w11-refocus-$($script:w11Dumps)"
                $focusBack = $refocus -and (Test-W11AnyCardLabel $refocus.Focused (Get-W11CardTitles 'ex-peppa'))
                Record 'EXAMPLE_LIBRARY_REJECTED_FOCUS_RETURNS' ([bool]$focusBack) `
                    "after BACK the focused card is '$(if ($refocus) { $refocus.Focused } else { 'unreadable' })', which is in the Peppa Pig row"

                $afterDenial = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CATALOG_UI')
                $cwAfter = if ($afterDenial) { $afterDenial.shelves | Where-Object { $_.id -eq 'shelf-continue-watching' } | Select-Object -First 1 } else { $null }
                $refusedId = $w11NameToNode['EXAMPLE_UNAPPROVED_VIDEO'].youtubeVideoId
                $cwClean = (-not $cwAfter) -or (@($cwAfter.cards | Where-Object { $_.videoId -eq $refusedId }).Count -eq 0)
                Record 'EXAMPLE_LIBRARY_REJECTED_CONTINUE_WATCHING_UNCHANGED' ([bool]$cwClean) `
                    "the video that was refused is not offered by Continue Watching: $cwClean"
            } else {
                Record 'EXAMPLE_LIBRARY_REJECTED_FOCUS_RETURNS' $false 'not reached: BACK did not reach the library'
                Record 'EXAMPLE_LIBRARY_REJECTED_CONTINUE_WATCHING_UNCHANGED' $false 'not reached'
            }
        } else {
            Record 'EXAMPLE_LIBRARY_UNAPPROVED_DENIED' $false 'the Peppa Pig row was never reached for the security check'
            Record 'EXAMPLE_LIBRARY_UNAPPROVED_MESSAGE' $false 'not reached'
        }
    } else {
        Record 'EXAMPLE_LIBRARY_PLAYLIST_FIRST_ITEM' $false 'the Peppa Pig row was never reached'
        Record 'EXAMPLE_LIBRARY_PLAYLIST_ADVANCE' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_PLAYLIST_FINAL_ITEM' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_UNAPPROVED_DENIED' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_UNAPPROVED_MESSAGE' $false 'not reached'
    }


    # --- 8. W12, D5: an oracle that can tell "the queue ended" from "playback stopped" -------------
    #
    # The W11 harness recorded a PASS for either outcome - "playback stopped" or "advanced" - so a
    # queue that silently died read exactly like a queue that finished, and one place in the old `full`
    # tier did the same (it treated an empty status as the end). Ending the queue is a *decision the
    # app makes* and it says so in its own log; stopping is what a dead queue does. Three separate
    # questions, and only when all three agree is the queue finished:
    #
    #   1. nothing is playing any more
    #   2. the app logged "Approved queue finished"
    #   3. the player screen is gone and the library is up
    #
    # Anything else is reported as what it is, not rounded up to a pass.
    # --- 9. W12, P2: a saved playhead survives the process being killed ---------------------------
    #
    # W11 could only report this as unverified: every test of resume ran inside one process, and the
    # exit-time save was a coroutine on a scope the screen disposes. This kills the process and asks
    # the app, after it comes back, where it thinks the child was.
    #
    # The video is chosen for being short: the Fixture's first CoComelon song runs about three
    # minutes, so a quarter of it is a handful of seek presses rather than minutes of waiting.
    $resumeVideo = $w11NameToNode['EXAMPLE_COCOMELON_VIDEO_1']
    if (-not (Restart-W11App 'before the restart/resume check')) {
        Stop-HarnessPrecondition 'the app could not be relaunched for the restart/resume check'
    }
    $cocomelonTitles = Get-W11CardTitles 'ex-cocomelon'
    if (Find-W11Card $cocomelonTitles) {
        Get-W11RowStart $cocomelonTitles | Out-Null     # the collection card
        Key 'KEYCODE_DPAD_CENTER'                       # open it
        Start-Sleep -Seconds 3
        Key 'KEYCODE_DPAD_DOWN'                         # its first song
        Key 'KEYCODE_DPAD_CENTER'
        $started = Wait-W11Video $resumeVideo.youtubeVideoId 30
        if ($started) {
            # Seek to roughly a quarter of the way in, then let it play a moment so the playhead is real.
            for ($i = 0; $i -lt 3; $i++) { Key 'KEYCODE_DPAD_RIGHT' }
            Start-Sleep -Seconds 6
            $before = Get-W11Playing
            Key 'KEYCODE_BACK'
            Start-Sleep -Seconds 5
            $leftToLibrary = Test-W11OnLibraryScreen
            $stoppedOnExit = Wait-W11NoPlay 15
            Log "  watched to $($before.positionSec)s of $($before.durationSec)s, then BACK"

            # The process dies. Nothing in it can save anything after this point.
            Adb @('shell', "am force-stop $pkg") | Out-Null
            Start-Sleep -Seconds 3
            $wasKilled = -not (((Adb @('shell', "ps -A | grep -i $pkg")) -join '') -match $pkg)
            Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
            Start-Sleep -Seconds 20
            $backUp = ((Adb @('shell', "ps -A | grep -i $pkg")) -join '') -match $pkg
            Record 'EXAMPLE_LIBRARY_RESTART_PROCESS' ($wasKilled -and $backUp) `
                "process killed: $wasKilled, running again: $backUp (the playhead was saved before the kill: $stoppedOnExit, library was up: $leftToLibrary)"

            # Continue Watching must offer it, at the position it was left at.
            $afterRestart = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CATALOG_UI')
            $cwShelf = if ($afterRestart) { $afterRestart.shelves | Where-Object { $_.id -eq 'shelf-continue-watching' } | Select-Object -First 1 } else { $null }
            $savedAt = if ($before) { [int]$before.positionSec } else { 0 }
            $cwOk = $cwShelf -and $cwShelf.cards.Count -gt 0 -and $cwShelf.cards[0].videoId -eq $resumeVideo.youtubeVideoId
            Record 'EXAMPLE_LIBRARY_RESTART_CONTINUE_WATCHING' $cwOk `
                "the first shelf after the restart offers $(if ($cwShelf -and $cwShelf.cards.Count) { $cwShelf.cards[0].videoId } else { 'nothing' }), expected $($resumeVideo.youtubeVideoId)"

            if ($cwOk) {
                # Reopened through the route this phase already walked - CoComelon, its collection, its
                # first song - rather than through the Continue Watching card itself. The card is a
                # long, emoji-bearing title that the on-screen label does not reproduce exactly, so
                # looking for it by name is a search that can fail for reasons that have nothing to do
                # with resume; what is under test is that the *saved playhead* survived the kill and is
                # offered when the video is opened again, and the shelf offering it has been asserted
                # above. This is still the remote: DOWN to the row, RIGHT to the card, CENTER.
                Park-W11Focus
                $resumeRoute = Find-W11Card $cocomelonTitles
                # The offer is detected from the app's OWN log, polled fast, and answered immediately.
                #
                # This is the whole of the W12.1 harness fix, and it is the mechanism the `full` tier has
                # always used: `Menu opened: RESUME` is written the moment the offer is raised, and the
                # offer withdraws itself after ~8s of no input. Taking a uiautomator dump (3-4s) to prove
                # it is on screen *before* pressing anything spends that window, which is why this step
                # failed three times while the app was behaving correctly. So: no dump between detecting
                # and pressing, and the proof that the choice was applied comes from the app's own
                # "Resume chosen" / "Start over chosen" lines rather than from a screenshot of a menu
                # that has already gone.
                if ($resumeRoute) {
                    $resumeLogBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
                    Get-W11RowStart $cocomelonTitles | Out-Null     # the collection card
                    Key 'KEYCODE_DPAD_CENTER'                       # open it
                    Start-Sleep -Seconds 3
                    Key 'KEYCODE_DPAD_DOWN'                         # its first song
                    Key 'KEYCODE_DPAD_CENTER'
                }
                $resumed = if ($resumeRoute) { Wait-W11Video $resumeVideo.youtubeVideoId 30 } else { $null }
                if ($resumed) {
                    $offerShown = $false
                    $savedLine = ''
                    for ($i = 0; $i -lt 24; $i++) {
                        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $resumeLogBase) -join "`n"
                        if ($fresh -match 'Resumable position for ') {
                            $savedLine = ([regex]::Match($fresh, 'Resumable position for [^:]+: \d+s')).Value
                        }
                        if ($fresh -match 'Menu opened: RESUME') { $offerShown = $true; break }
                        Start-Sleep -Milliseconds 500
                    }
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME_OFFER' $offerShown `
                        "the app raised the resume offer after the restart: $offerShown ($savedLine)"

                    # The offer does not gate playback - the video plays from the beginning behind it -
                    # so what the saved playhead was is asked of the app, and whether the choice was
                    # applied is asked of the app's own action line.
                    $actionBase = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
                    $posOnOpen = -1
                    if ($offerShown) {
                        $posOnOpen = if (Get-W11Playing) { [int](Get-W11Playing).positionSec } else { -1 }
                        Key 'KEYCODE_DPAD_CENTER'      # the offer opens on "Resume"
                        Start-Sleep -Seconds 4
                    }
                    $actionLog = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $actionBase) -join "`n"
                    $choseResume = $actionLog -match 'Resume chosen'
                    $choseStartOver = $actionLog -match 'Start over chosen'
                    $now = Get-W11Playing
                    $nearSaved = $now -and ([Math]::Abs([int]$now.positionSec - $savedAt) -le 25)
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME_CHOICE' ($choseResume -and (-not $choseStartOver)) `
                        "the app reports: Resume chosen=$choseResume, Start over chosen=$choseStartOver"
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME' ([bool]$resumed -and $choseResume -and $nearSaved) `
                        "opened at ${posOnOpen}s, after choosing Resume it is at $(if ($now) { $now.positionSec } else { 'nothing' })s where it was left at ${savedAt}s"
                    Shot 'w11-restart-resumed'
                } else {
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME_CHOICE' $false 'not reached'
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME' $false 'the Continue Watching card could not be focused'
                    Record 'EXAMPLE_LIBRARY_RESTART_RESUME_OFFER' $false 'not reached'
                }
            } else {
                Record 'EXAMPLE_LIBRARY_RESTART_RESUME_CHOICE' $false 'not reached'
                Record 'EXAMPLE_LIBRARY_RESTART_RESUME' $false 'Continue Watching did not offer the video that was being watched'
                Record 'EXAMPLE_LIBRARY_RESTART_RESUME_OFFER' $false 'not reached'
            }
        } else {
            Record 'EXAMPLE_LIBRARY_RESTART_PROCESS' $false 'the fixture video never started, so nothing could be saved'
            Record 'EXAMPLE_LIBRARY_RESTART_CONTINUE_WATCHING' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_RESTART_RESUME_CHOICE' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_RESTART_RESUME' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_RESTART_RESUME_OFFER' $false 'not reached'
        }
    } else {
        Record 'EXAMPLE_LIBRARY_RESTART_PROCESS' $false 'the CoComelon row was never reached'
        Record 'EXAMPLE_LIBRARY_RESTART_CONTINUE_WATCHING' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_RESTART_RESUME_CHOICE' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_RESTART_RESUME' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_RESTART_RESUME_OFFER' $false 'not reached'
    }

    # --- 10. W12.1, Test B: the D1 fix seen from outside, across a process restart -----------------
    #
    # A -> NEXT -> B, each left at its own playhead, then the process is killed. B is what Continue
    # Watching should offer. Opening *A* again must offer A's own position - which is precisely the
    # defect W12 fixed: before it, B's playhead was written into A's row, so A would have offered B's.
    #
    # The value that decides this is the app's own line - "Resumable position for <id>: <n>s" - because
    # that is the number the offer is built from; the playhead after choosing Resume confirms it.
    $videoA = $w11NameToNode['EXAMPLE_COCOMELON_VIDEO_1']
    $videoB = $w11NameToNode['EXAMPLE_COCOMELON_VIDEO_2']
    if (Restart-W11App 'before the D1-after-restart check') {
        $route = Find-W11Card $cocomelonTitles
        $posA = -1
        if ($route) {
            Get-W11RowStart $cocomelonTitles | Out-Null
            Key 'KEYCODE_DPAD_CENTER'
            Start-Sleep -Seconds 3
            Key 'KEYCODE_DPAD_DOWN'
            Key 'KEYCODE_DPAD_CENTER'
        }
        $playingA = if ($route) { Wait-W11Video $videoA.youtubeVideoId 30 } else { $null }
        if ($playingA) {
            foreach ($i in 1..2) { Key 'KEYCODE_DPAD_RIGHT' }    # A gets a modest playhead
            Start-Sleep -Seconds 5
            $posA = if (Get-W11Playing) { [int](Get-W11Playing).positionSec } else { -1 }

            # NEXT inside the same approved source: the queue is the source's cached videos in order.
            Key 'KEYCODE_MEDIA_NEXT'
            $playingB = Wait-W11Video $videoB.youtubeVideoId 30
            $posB = -1
            if ($playingB) {
                foreach ($i in 1..6) { Key 'KEYCODE_DPAD_RIGHT' }   # B gets a clearly different one
                Start-Sleep -Seconds 5
                $posB = if (Get-W11Playing) { [int](Get-W11Playing).positionSec } else { -1 }
            }
            Log "  D1-after-restart: A=$($videoA.youtubeVideoId) at ${posA}s, B=$($videoB.youtubeVideoId) at ${posB}s"
            $distinct = ($posA -gt 15) -and ($posB -gt $posA + 30)
            Record 'EXAMPLE_LIBRARY_D1_TWO_PLAYHEADS' $distinct `
                "A left at ${posA}s and B at ${posB}s, which is far enough apart for the check to mean something"

            if ($distinct) {
                Key 'KEYCODE_BACK'
                Start-Sleep -Seconds 5
                Adb @('shell', "am force-stop $pkg") | Out-Null
                Start-Sleep -Seconds 3
                Adb @('shell', "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") | Out-Null
                Start-Sleep -Seconds 20

                # The most recent video is B, so that is what Continue Watching must offer first.
                $cw = ConvertFrom-DumpJson (Get-W11Dump 'DEBUG_DUMP_CATALOG_UI')
                $cwFirst = if ($cw) { ($cw.shelves | Where-Object { $_.id -eq 'shelf-continue-watching' } | Select-Object -First 1) } else { $null }
                $cwIsB = $cwFirst -and $cwFirst.cards.Count -gt 0 -and $cwFirst.cards[0].videoId -eq $videoB.youtubeVideoId
                Record 'EXAMPLE_LIBRARY_D1_CONTINUE_WATCHING_IS_B' ([bool]$cwIsB) `
                    "after the restart Continue Watching offers $(if ($cwFirst -and $cwFirst.cards.Count) { $cwFirst.cards[0].videoId } else { 'nothing' }); B is $($videoB.youtubeVideoId)"

                # Now open A, and read what the app says A's saved position is.
                Park-W11Focus
                $routeAgain = Find-W11Card $cocomelonTitles
                $resumeLineA = ''
                $logBaseA = 0
                if ($routeAgain) {
                    $logBaseA = @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count
                    Get-W11RowStart $cocomelonTitles | Out-Null
                    Key 'KEYCODE_DPAD_CENTER'
                    Start-Sleep -Seconds 3
                    Key 'KEYCODE_DPAD_DOWN'
                    Key 'KEYCODE_DPAD_CENTER'
                }
                $backOnA = if ($routeAgain) { Wait-W11Video $videoA.youtubeVideoId 30 } else { $null }
                $offerA = $false
                if ($backOnA) {
                    for ($i = 0; $i -lt 24; $i++) {
                        $fresh = (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $logBaseA) -join "`n"
                        $match = [regex]::Match($fresh, 'Resumable position for ' + [regex]::Escape($videoA.youtubeVideoId) + ': (\d+)s')
                        if ($match.Success) { $resumeLineA = [int]$match.Groups[1].Value }
                        if ($fresh -match 'Menu opened: RESUME') { $offerA = $true; break }
                        Start-Sleep -Milliseconds 500
                    }
                }
                $aOffersItsOwn = $offerA -and ($resumeLineA -gt 0) -and
                    ([Math]::Abs($resumeLineA - $posA) -le 12) -and ([Math]::Abs($resumeLineA - $posB) -gt 30)
                Record 'EXAMPLE_LIBRARY_D1_AFTER_RESTART' $aOffersItsOwn `
                    "A offers ${resumeLineA}s; A was left at ${posA}s and B at ${posB}s (the defect this guards against would have offered B's ${posB}s under A)"

                $posAfterA = -1
                if ($offerA) {
                    Key 'KEYCODE_DPAD_CENTER'
                    Start-Sleep -Seconds 4
                    $posAfterA = if (Get-W11Playing) { [int](Get-W11Playing).positionSec } else { -1 }
                }
                Record 'EXAMPLE_LIBRARY_D1_RESUME_LANDS_ON_A' ($offerA -and ([Math]::Abs($posAfterA - $posA) -le 25)) `
                    "choosing Resume for A landed at ${posAfterA}s, A's own ${posA}s (B's was ${posB}s)"
                Shot 'w11-d1-after-restart'
            } else {
                Record 'EXAMPLE_LIBRARY_D1_CONTINUE_WATCHING_IS_B' $false 'the two playheads were not far enough apart to test with'
                Record 'EXAMPLE_LIBRARY_D1_AFTER_RESTART' $false 'not reached'
                Record 'EXAMPLE_LIBRARY_D1_RESUME_LANDS_ON_A' $false 'not reached'
            }
        } else {
            Record 'EXAMPLE_LIBRARY_D1_TWO_PLAYHEADS' $false 'the first CoComelon video never started'
            Record 'EXAMPLE_LIBRARY_D1_CONTINUE_WATCHING_IS_B' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_D1_AFTER_RESTART' $false 'not reached'
            Record 'EXAMPLE_LIBRARY_D1_RESUME_LANDS_ON_A' $false 'not reached'
        }
    } else {
        Record 'EXAMPLE_LIBRARY_D1_TWO_PLAYHEADS' $false 'the app could not be relaunched for the D1-after-restart check'
        Record 'EXAMPLE_LIBRARY_D1_CONTINUE_WATCHING_IS_B' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_D1_AFTER_RESTART' $false 'not reached'
        Record 'EXAMPLE_LIBRARY_D1_RESUME_LANDS_ON_A' $false 'not reached'
    }
    $script:results | ConvertTo-Json | Set-Content (Join-Path $out 'result.json')
    Log '=== W11 summary ==='
    $script:results.GetEnumerator() | ForEach-Object { Log ("  {0,-42} {1}" -f $_.Key, $_.Value) }
    Log "  uiautomator dumps taken: $($script:w11Dumps)"
    $failed = @($script:results.GetEnumerator() | Where-Object { $_.Value -eq 'FAIL' })
    Log ("W11 EXAMPLE_LIBRARY_TEST: {0} ({1} checks, {2} failed)" -f $(if ($failed.Count) { 'FAIL' } else { 'PASS' }), $script:results.Count, $failed.Count)
    Log "artifacts: $out"
    Restore-ScreenSaver
    if ($failed.Count) { exit 1 } else { exit 0 }
}

EnsureApp 'w6' | Out-Null
if (Go-ToLibrary 'before the hierarchy checks') {
    Log '=== W6: categories are titles, sub-categories are cards ==='
    # Deterministic fixture before reading the projection.
    #
    # The projection is dumped as ONE log entry, and logcat keeps only about 4 KB of one entry - so
    # whichever shelf arrives first decides what this check is able to see. `shelf-continue-watching` is
    # built from resumable videos, and on a device whose watch history has accumulated it holds enough
    # cards to consume that entire budget: the payload then arrives cut before any shelf that has cards,
    # and this check failed with "no shelf with cards to navigate" while the app's own projection was
    # correct (W13.2: 9 shelf ids, all continue-watching, ~4 KB payload, first shelf 11 cards).
    # Forgetting the saved playheads - the app's own debug action, whose purpose is reaching the state a
    # fresh installation starts in - empties that shelf without destroying the parent's approved library,
    # so the first shelf to arrive is a real one. The projection itself is untouched; only the fixture is
    # pinned instead of depending on what happened to be watched on this TV.
    $clearedResume = Get-DebugDump 'DEBUG_CLEAR_RESUME_POSITIONS'
    Log "  resume positions cleared before the projection: $(if ($clearedResume) { $clearedResume } else { 'no result reported' })"
    $projection = Get-DebugDump 'DEBUG_DUMP_CATALOG_UI'
    $projectionLength = if ($projection) { $projection.Length } else { 0 }
    $model = $null
    # Parsed with the tolerant reader, not a plain JSON parse: the projection of a library this size is
# longer than one logcat entry, so it arrives cut and a strict parse turns that into "the app did not
# report its catalogue projection" - which is what the player tier did against the example library
# until W12.1. The reader closes the truncated payload and hands back the part that arrived.
            $model = ConvertFrom-DumpJson $projection

    if (-not $model) {
        Record 'w6-catalog-projection' $false "the app did not report its catalogue projection (payload $projectionLength chars)"
    } else {
        # Evidence about the capture itself, so a truncated payload can never masquerade as a product
        # problem: the app reports how many shelves exist, and the tolerant reader reports how many
        # arrived. They differ exactly when logcat cut the entry.
        $parsedShelves = @($model.shelves).Count
        $reportedShelves = [int]$model.shelfCount
        $shelfIds = (@($model.shelves) | ForEach-Object { "$($_.id)" }) -join ', '
        $captureTruncated = $parsedShelves -lt $reportedShelves
        Log "  projection payload $projectionLength chars; app reports $reportedShelves shelf(s), $parsedShelves arrived [$shelfIds]"
        $shelf = $model.shelves | Where-Object { $_.id -ne 'shelf-continue-watching' } | Select-Object -First 1
        if (-not $shelf -or $shelf.cards.Count -eq 0) {
            $why = if ($captureTruncated) {
                "the projection arrived truncated ($parsedShelves of $reportedShelves shelves: $shelfIds) and no shelf with cards was captured"
            } else {
                "no shelf with cards to navigate (shelves: $shelfIds)"
            }
            Record 'w6-catalog-projection' $false $why
        } else {
            $first = $shelf.cards[0]
            $shelfLine = ($shelf.cards | ForEach-Object { "$($_.title)[$($_.kind)]" }) -join ' '
            Log "  shelf '$($shelf.title)': $shelfLine"
            # Reported on both paths. This check used to record only failures, so a green run said nothing
            # about the projection and the W13.2.1 fix for it could only be read out of the free-text line
            # above.
            Record 'w6-catalog-projection' $true "shelf '$($shelf.title)' with $($shelf.cards.Count) card(s); $parsedShelves of $reportedShelves shelf(s) arrived in $projectionLength chars$(if ($captureTruncated) { ' (the rest was cut by the log entry limit)' } else { '' })"
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

            # 2. the shelf's first card is a card, not a heading.
            Record 'w6-subcategory-is-a-card' ($first.kind -eq 'CONTAINER') "first card '$($first.title)' kind=$($first.kind)"

            # 3. focus the first card with the D-pad alone.
            #
            # Focus is *polled* for rather than read from one snapshot. Android TV settles focus a frame
            # or two after the key, so a dump taken immediately can report "focused ''" while the card is
            # focused a moment later - which is exactly how this check reported a failure against a
            # perfectly focused row. Each press is followed by a bounded series of dumps, and a failure
            # says what was on screen, what was focusable and which key was last sent.
            # 3. focus the category's first card with the D-pad alone, navigating by the row's position in
            #    the library projection rather than by looking for its title as the walk descends.
            #
            #    The projection is the library's own order, so a row's index is known even when that row
            #    is not on screen - and a dump only contains what is rendered, which is why the previous
            #    two forms failed: pressing DOWN a fixed number of times scrolls past the row, and walking
            #    while looking for the title cannot match a row that has already scrolled out of the
            #    hierarchy. What is pressed is therefore derived from the projection, and every press is
            #    followed by a bounded poll of the focused card.
            $rowTitles = @($shelf.cards | ForEach-Object { $_.title })
            $projectionTitles = @($model.shelves | ForEach-Object { $_.title })
            $targetRowIndex = [array]::IndexOf($projectionTitles, $shelf.title)
            if ($targetRowIndex -lt 0) { $targetRowIndex = 0 }
            Park-W11Focus
            $focused = ''
            $lastDpadAction = 'KEYCODE_DPAD_DOWN'
            $pressCount = 0
            $navigation = @()
            $facts = $null
            # The allowance is not a fudge: the top bar has focusables of its own (Refresh, Connect
            # Phone, Settings), so the first DOWN or two move focus *along* the bar before it descends
            # into the rows - the navigation history shows exactly that ("1:Refresh" then "2:<a card>").
            # The bound therefore starts from the projection's row index and permits those presses, and
            # the check still fails if focus lands in a row that is not the target.
            $maxPresses = $targetRowIndex + 3
            for ($step = 0; $step -lt $maxPresses -and $focused -eq ''; $step++) {
                Key 'KEYCODE_DPAD_DOWN'
                $pressCount++
                for ($poll = 0; $poll -lt 6 -and $focused -eq ''; $poll++) {
                    $snapName = "w6-b-focus-$step-$poll"
                    Dump $snapName
                    $facts = Get-UiFacts (Join-Path $out "$snapName.xml")
                    $navigation += "${pressCount}:$($facts.Focused)"
                    if ($facts.Focused -and (@($rowTitles | Where-Object { Test-W11CardLabel $facts.Focused $_ }).Count -gt 0)) {
                        $focused = $facts.Focused
                    } elseif ($poll -lt 5) {
                        Start-Sleep -Milliseconds 350
                    }
                }
            }
            if ($focused -eq '' -and -not $facts) { $facts = Get-UiFacts (Join-Path $out 'w6-b-focus-nodump.xml') }
            if ($focused -eq '') {
                # The whole navigation, so a future failure says which press landed where rather than
                # only that nothing was focused at the end of it.
                $focusedRow = ''
                foreach ($candidate in $model.shelves) {
                    foreach ($card in $candidate.cards) {
                        if ($facts.Focused -and (Test-W11CardLabel $facts.Focused $card.title)) { $focusedRow = $candidate.title }
                    }
                }
                $visibleRows = @($model.shelves | Where-Object { $row = $_; @($row.cards | Where-Object { $c = $_; @($facts.Focusable | Where-Object { Test-W11CardLabel $_ $c.title }).Count -gt 0 }).Count -gt 0 } | ForEach-Object { $_.title })
                Log "  W6-C: target_row='$($shelf.title)' target_row_index=$targetRowIndex expected_card='$($first.title)'"
                Log "  W6-C: current_focused_card='$(if ($facts) { $facts.Focused } else { '' })' current_focused_row='$focusedRow' press_count=$pressCount"
                Log "  W6-C: navigation_history=[$($navigation -join ' | ')]"
                Log "  W6-C: visible_rows=[$($visibleRows -join ' | ')]"
                Log "  W6-C: focusable_elements=[$((@($facts.Focusable | Where-Object { $_ })) -join ' | ')]"
                Log "  W6-C: foreground=$(ForegroundPackage) lastKey=$lastDpadAction"
            }
            Record 'w6-dpad-reaches-the-first-card' ($focused -ne '') "focused '$focused' after $lastDpadAction"
            if ($focused -ne '') {
                Log "  D-pad focus: $focused"

                # 2b. the row's cards are reachable with the D-pad.
                #
                # This is the W6 check that required every card of a row to be in one dump. A dump holds
                # what is *rendered*, and a row is not obliged to fit on screen at once - the example
                # library's CoComelon row holds a collection and a video, of which one may be drawn - so
                # what is asserted is that the row can be walked: RIGHT moves focus onto another card,
                # and the label it lands on is one of the cards the app says that row holds. A single-card
                # row passes, because there is nothing to walk to.
                $rowTitles = @($shelf.cards | ForEach-Object { $_.title })

                # D-pad focus enters a row wherever the app decides, and everything below assumes it is on
                # the row's *first* card: RIGHT walks from there, and the container checks press CENTER on
                # it. This is normally already true (measured: "0 LEFT press(es)" when it is), and the loop
                # below is the bounded correction for when it is not.
                #
                # It cannot be made unconditional with a park-then-descend walk: the app restores focus to
                # the card it last had, so a later descent enters the row on whatever card it remembers,
                # and a descent that only stops on the first card's title overshoots into the next row
                # (observed: 3 DOWN presses ending on 'Peppa Pig Tales 2026 ...').
                $firstTitle = $first.title
                $backPresses = 0
                $onFirstCard = Test-W11CardLabel $focused $firstTitle
                while ((-not $onFirstCard) -and ($backPresses -lt 4)) {
                    Key 'KEYCODE_DPAD_LEFT'
                    $backPresses++
                    $navName = "w6-first-card-$backPresses"
                    Dump $navName
                    $navFacts = Get-UiFacts (Join-Path $out "$navName.xml")
                    if (Test-W11CardLabel $navFacts.Focused $firstTitle) { $onFirstCard = $true }
                }
                if ($onFirstCard) {
                    Log "  W6 row focus is on the row's first card after $backPresses LEFT press(es)"
                    if ($backPresses -gt 0 -and $navFacts -and $navFacts.Focused) { $focused = $navFacts.Focused }
                } else {
                    Log "  W6 row diagnostics: focus is not on the row's first card '$firstTitle' after $backPresses LEFT press(es); it is '$($navFacts.Focused)'"
                }

                $moved = ''
                if ($rowTitles.Count -gt 1) {
                    Key 'KEYCODE_DPAD_RIGHT'
                    for ($poll = 0; $poll -lt 8 -and $moved -eq ''; $poll++) {
                        $snapName = "w6-b-right-$poll"
                        Dump $snapName
                        $rightFacts = Get-UiFacts (Join-Path $out "$snapName.xml")
                        if ($rightFacts.Focused -and ($rightFacts.Focused -ne $focused)) {
                            $onto = @($rowTitles | Where-Object { Test-W11CardLabel $rightFacts.Focused $_ } | Select-Object -First 1)
                            if ($onto) { $moved = $rightFacts.Focused }
                        }
                        if ($moved -eq '' -and $poll -lt 7) { Start-Sleep -Milliseconds 350 }
                    }
                    if ($moved -eq '') {
                        Log "  W6 row diagnostics: focus after RIGHT stayed '$($rightFacts.Focused)' of [$((@($rightFacts.Focusable | Where-Object { $_ })) -join ' | ')]"
                    }
                    # Back to the first card, which the container checks below press - verified, because an
                    # unverified single LEFT press leaves focus on the wrong card when the walk moved more
                    # than one card, and CENTER would then open the wrong thing (or nothing).
                    #
                    # This is the step that still fails, and the diagnostics say why: the focused *video*
                    # card owns an inline player whose controls are focusable, so LEFT from it enters those
                    # controls rather than moving to the sibling card (observed focus after four LEFT
                    # presses: '3:04 / 0:01 / Forward 10 seconds / ... / Pause / 5 of 105 / <title>'), and
                    # the CENTER that follows opens nothing. The row's card membership cannot be tested by
                    # title here either: the row's second card is a *featured video* card that rotates
                    # ('Wheels on the Bus Lullaby' with '5 of 105' in one run, 'This is the Way Bedroom'
                    # with '6 of 105' in the next), so the titles captured from the projection at the start
                    # of the phase no longer describe what is on screen.
                    $backPresses = 0
                    $onFirstCard = Test-W11CardLabel $rightFacts.Focused $firstTitle
                    while ((-not $onFirstCard) -and ($backPresses -lt 4)) {
                        Key 'KEYCODE_DPAD_LEFT'
                        $backPresses++
                        $navName = "w6-first-card-back-$backPresses"
                        Dump $navName
                        $navFacts = Get-UiFacts (Join-Path $out "$navName.xml")
                        if (Test-W11CardLabel $navFacts.Focused $firstTitle) { $onFirstCard = $true }
                    }
                    Log "  W6 row focus returned to the row's first card: $onFirstCard (after $backPresses LEFT press(es), focus '$(if ($navFacts -and $navFacts.Focused) { $navFacts.Focused } else { $rightFacts.Focused })')"
                }
                Record 'w6-cards-are-focusable' (($rowTitles.Count -le 1) -or ($moved -ne '')) `
                    "the row holds $($rowTitles.Count) card(s); after RIGHT focus is on '$(if ($moved) { $moved } else { 'nothing' })'$(if ($rowTitles.Count -le 1) { ' (a single-card row has nothing to walk to)' } else { '' })"

                if ($first.kind -eq 'CONTAINER') {
                    # 4. Enter opens the container - and must not start playing anything
                    Key 'KEYCODE_DPAD_CENTER'
                    Start-Sleep -Seconds 4
                    $playingAfterEnter = Get-W6Playing
                    Record 'w6-container-card-does-not-autoplay' ($null -eq $playingAfterEnter) `
                        $(if ($null -eq $playingAfterEnter) { 'nothing started' } else { "started $($playingAfterEnter.videoId)" })

                    # 5. the container's own children are what is shown.
                    #
                    # Compared with the tolerant title match (Test-W11Title) rather than with -eq or a
                    # regex on the raw text: the children's titles carry emoji, and the logcat/UI paths
                    # cannot carry those characters intact, so an exact comparison fails on a screen
                    # that is showing exactly the right thing. The match is still on the *container's
                    # name* and on its *first child*, both of which must appear, and on this being the
                    # container's screen rather than the home screen - so opening the wrong container,
                    # or none, still fails. What is normalised: characters outside printable ASCII are
                    # dropped and runs of whitespace are collapsed before comparing, which is the same
                    # rule the example tier uses for the same reason.
                    $children = Get-DebugDump 'DEBUG_DUMP_CONTAINER_UI' "--es container_id $($first.containerId)"
                    $childModel = ConvertFrom-DumpJson $children
                    $childTitles = @()
                    $childIds = @()
                    if ($childModel) {
                        $childTitles = @($childModel.cards | ForEach-Object { $_.title })
                        $childIds = @($childModel.cards | ForEach-Object { $_.id })
                    }
                    Dump 'w6-c-container'
                    $inside = Get-UiFacts (Join-Path $out 'w6-c-container.xml')
                    # Only the cards that fit on screen are in the hierarchy dump, so the check is that
                    # the container's own name and its *first* child are there, and that this is the
                    # container's screen rather than the home screen (which carries the Refresh button).
                    $firstChild = if ($childTitles.Count -gt 0) { $childTitles[0] } else { '' }
                    $screenIsContainer = ($inside.Dump -match [regex]::Escape($first.title)) -or
                        (@($inside.Focusable | Where-Object { Test-W11Title $_ $first.title -ne 'no' }).Count -gt 0)
                    $screenHasFirstChild = $childTitles.Count -gt 0 -and
                        (@($inside.Focusable | Where-Object { (Test-W11Title $_ $firstChild) -ne 'no' }).Count -gt 0)
                    $openedChildren = ($childTitles.Count -gt 0) -and $screenIsContainer -and
                        $screenHasFirstChild -and ($inside.Dump -notmatch 'Refresh')
                    Record 'w6-container-opens-its-children' $openedChildren `
                        "container '$($first.title)' (id $($first.containerId)) shows its first child '$(if ($firstChild) { $firstChild } else { '(none)' })' of $($childTitles.Count) [child ids: $($childIds -join ',')]; on screen: '$($inside.Focused)'"
                    if (-not $openedChildren) {
                        Log "  W6 container diagnostics: focusable=[$((@($inside.Focusable | Where-Object { $_ })) -join ' | ')]"
                        Log "  W6 container diagnostics: the app lists children [$(($childTitles | Select-Object -First 3) -join ' | ')]"
                    }
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

    # Poll for the furthest back the playhead is *seen* to go, instead of sampling it once.
    #
    # Why a single sample was wrong: the presses ask the player for about -20s, and playback adds back
    # the time spent measuring, so a run in which the second press computed its target from a position
    # the first seek had not yet been applied to measured -6s and was recorded as a failed seek. The
    # seek had happened; the observation was of a different instant (W13.1: "position 38s -> 32s",
    # while the same check measured -15s and -17s in other runs of the same build).
    #
    # Polling is sound for *this* check and not for the forward one beside it, which is why only this
    # one changes: playback can never produce a backward excursion, so every backward move observed is
    # a seek that landed. A polled forward bar would instead be satisfied by playback alone within a
    # few seconds, which is why `seek-forward` keeps its single tight sample.
    #
    # The assertion keeps its direction and gains size: the playhead must be seen at least 16s below
    # where it started (two committed steps), and a pair of presses that collapses into one is retried
    # once. A player that does not seek still fails - polling cannot invent a backward move.
    # One remote press, then poll until the player has committed it; press again only if it did not.
    #
    # Requiring a *pair* of presses to compound measures the wrong thing on this device. Seeking back in
    # a progressive stream re-buffers, and a press that arrives before the previous seek has been applied
    # computes its target from the pre-seek position (`seekBy` reads `player.currentPosition`), so the
    # pair collapses into a single step - measured: "26s -> lowest 17s (16s required, 4 presses sent)",
    # against "25s -> 10s" in runs where the pair did compound. What this check is for is that a backward
    # key moves the playhead back at all, and against a *playing* video that is unambiguous: playback only
    # ever increases the position, so a playhead seen 8s below where it started is a seek that landed.
    # The bar is the one this check always used (8s). What changed is that it is read from the state the
    # player actually committed, instead of one sample taken a fixed three seconds later - which is how a
    # run in which the seek had happened was recorded as "position 38s -> 32s", a failure.
    $want = [Math]::Min(8, [Math]::Max(4, $p1 - 1))
    $p2 = $p1
    $presses = 0
    while ($presses -lt 3 -and $p2 -gt ($p1 - $want)) {
        # Alternate the two keys the player maps to a backward seek, so both are still exercised.
        if ($presses % 2 -eq 0) { Key 'KEYCODE_MEDIA_REWIND' } else { Key 'KEYCODE_DPAD_LEFT' }
        $presses++
        for ($poll = 0; $poll -lt 14; $poll++) {
            Start-Sleep -Milliseconds 700
            $snap = PlayingNow
            if ($snap) { $p2 = [Math]::Min($p2, [int]$snap.positionSec) }
            if ($p2 -le ($p1 - $want)) { break }
        }
    }
    Record 'seek-backward' ($p2 -le ($p1 - $want)) "position ${p1}s -> lowest ${p2}s seen (${want}s required, ${presses} press(es) sent)"
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
    # UP twice for the same reason as the speed phase below: the settings row is already active from
    # the captions phase, and DOWN from an active row now descends into the transport row instead of
    # re-entering the settings row. UP,UP lands on the row's first button from any state, so OK opens a
    # menu in every case (it toggles playback if the row is not active, which is what makes this
    # deterministic rather than hopeful).
    Key 'KEYCODE_DPAD_UP'
    Key 'KEYCODE_DPAD_UP'
    Key 'KEYCODE_DPAD_CENTER'
    Start-Sleep -Seconds 1
    Key 'KEYCODE_BACK'
    Start-Sleep -Seconds 2
    $stillPlayingAfterBack = ($null -ne (PlayingNow))
    Record 'menu-back-closes-menu-only' $stillPlayingAfterBack
    Shot '12-after-menu-back'

    # playback speed: the row's first button, RIGHT x3 to Speed, open, then 1.0x -> 1.25x
    #
    # UP twice, not DOWN, is what returns the highlight to the row's first button. DOWN stopped being
    # idempotent in W13.2: the settings row is the first stop from the video, and a second DOWN
    # descends into the transport row below it (verified on the Mi Box). This phase inherits the row
    # state from the BACK check just above, which already pressed DOWN, so a DOWN here landed on the
    # transport controls: RIGHT walked them, OK toggled playback, the SPEED menu never opened - which
    # is how this check failed while the same navigation passed in the aspect phase, where the video is
    # reopened first. UP leaves the transport row when it holds focus and activates the settings row at
    # its first button from every other state, so the highlight is deterministic either way.
    Key 'KEYCODE_DPAD_UP'
    Key 'KEYCODE_DPAD_UP'
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
            # The autoplay budget starts *after* the seeks, because it is a budget for observing
            # autoplay and not for reaching the end of the video. Sampling the position before the
            # presses made the deadline cover the seeking too, so a run whose seeks did not land
            # expired one second before a 229s video ended (measured: "reached 228s of 229s"). The
            # assertion is unchanged - the queue still has to advance to the next approved item -
            # only the arithmetic now starts from where the video actually is.
            $seekedAuto = PlayingNow
            $seekedPosition = if ($seekedAuto) { [int]$seekedAuto.positionSec } else { [int]$beforeAuto.positionSec }
            $autoRemaining = [Math]::Max(30, $autoDuration - $seekedPosition)
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
        #
        # The video id is remembered first, because these presses can overshoot: a video already near
        # its end advances the queue, and the measurement below would then be taken on whatever the
        # queue moved to - which is how this check reported "still on 6A0aiN0xOHg at 708s of 3793s",
        # waiting out a sixty-three-minute compilation that the seek had only just opened. If the queue
        # moves while seeking, that *is* the end-of-video behaviour under test, so it is recorded as
        # such rather than measured as if the new video had failed to finish.
        $videoBeforeSeeking = "$((PlayingNow).videoId)"
        # Seek to just before the end, and then *verify the presses landed*.
        #
        # A batch that does not land leaves the video at 0s, and the check then waits out the whole
        # video before it can say anything (W13.1: "was GsrCSM_agk0 at 0s of 186s", the case the comment
        # below already recorded as "observed as a baseline of '0s of 165s'"). That spends the tier's
        # budget and ages the app log the completion oracle has to read. Pressing again is safe: if the
        # presses overshoot instead, the queue advances, which is the behaviour under test, so the loop
        # stops and says so.
        $afterSeeking = $null
        for ($attempt = 1; $attempt -le 3 -and -not $afterSeeking; $attempt++) {
            $now = PlayingNow
            if ($now -and "$($now.videoId)" -ne $videoBeforeSeeking) { $afterSeeking = $now; break }
            $at = if ($now) { [int]$now.positionSec } else { 0 }
            if ($now -and $at -ge ($durationSec - 20)) { $afterSeeking = $now; break }
            $presses = [Math]::Max(1, [int](($durationSec - 14 - $at) / 10))
            for ($i = 1; $i -le $presses; $i++) { Key 'KEYCODE_DPAD_RIGHT' }
            # A seek is applied asynchronously, so the landing is polled for rather than assumed.
            for ($poll = 0; $poll -lt 8 -and -not $afterSeeking; $poll++) {
                Start-Sleep -Milliseconds 700
                $snap = PlayingNow
                if (-not $snap) { continue }
                if ("$($snap.videoId)" -ne $videoBeforeSeeking) { $afterSeeking = $snap }
                elseif ([int]$snap.positionSec -ge ($durationSec - 20)) { $afterSeeking = $snap }
            }
            if (-not $afterSeeking) {
                Log "    end-of-video: the seek presses did not land (attempt $attempt, at ${at}s of ${durationSec}s) - pressing again"
            }
        }
        if (-not $afterSeeking) { $afterSeeking = PlayingNow }
        if ($afterSeeking -and ("$($afterSeeking.videoId)" -ne $videoBeforeSeeking)) {
            Record 'end-of-video-handling' $true `
                "the seek presses reached the end of $videoBeforeSeeking and the queue advanced to $($afterSeeking.videoId), which is the behaviour under test"
            Log '  end-of-video: the queue advanced while seeking, so there is nothing left to wait for'
        } elseif ($afterSeeking -and [int]$afterSeeking.durationSec -gt 900) {
            # A video this long cannot be played out inside a tier's budget, and saying so is better
            # than waiting half an hour and then calling the wait a failure.
            Record 'end-of-video-handling' $false `
                "$videoBeforeSeeking runs $($afterSeeking.durationSec)s, which is too long to reach the end of within this tier - not measurable this way"
        } else {
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
            # No player state at all can also mean the queue ended while the seek presses were being
            # sent and the app has already left the player. That is the end of the queue, reported by
            # the app itself, so it is recorded as the pass it is - and it still requires *both* halves
            # of the completion evidence below, so an unreadable status can never become a silent pass
            # for a video that never ended.
            $endLog = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
            $saidFinished = $endLog -match 'Approved queue finished'
            Dump 'end-of-video-screen'
            $facts = Get-UiFacts (Join-Path $out 'end-of-video-screen.xml')
            $playerGone = [bool]($facts -and ($facts.Dump -match 'SafeTube for Kids') -and ($facts.Dump -match 'Refresh'))
            if ($saidFinished -and $playerGone) {
                Record 'end-of-video-handling' $true 'the queue ended while the seeks were being sent: the app reported the approved queue finished and the player screen is gone'
            } else {
                Record 'end-of-video-handling' $false 'could not read the player state before waiting for the end'
            }
        } else {
            # Watch for the actual advance instead of breaking on a position stall: at the end of a
            # video the playhead legitimately stops changing for a moment while the queue moves on,
            # and treating that as "finished waiting" is what produced
            # "still on e_04ZrNroTo at 228s of 229s after waiting" - one second short of the end.
            # The deadline has to cover the video actually playing to its end; the seeks above are
            # verified to have landed, so normally only the last few seconds remain.
            #
            # The wait no longer *returns* on the first empty status sample. `currentlyPlaying` is
            # briefly null while the app hands the queue over - `endEvent` clears the published state
            # before the next `startEvent` sets it - and reading one such sample as "playback stopped"
            # failed a run in which the app had gone on to handle the end correctly (W13.1: two of three
            # full runs red here, and one of them diagnosed as "was GsrCSM_agk0 at 0s of 186s", where
            # the seek presses had not landed at all). So the loop keeps polling, for the next video or
            # for the app's own account of the end, and only the conjunction below ends it.
            $remaining = [Math]::Max(30, $durationSec - [int]$endBefore.positionSec)
            $deadline = (Get-Date).AddSeconds($remaining + 90)
            # Only what happens from here counts as evidence: the log is cleared so an end reported
            # before this wait began cannot be read as this video's end (an overshoot during the seeks
            # is handled by the advance branch above, which needs no log).
            Adb @('logcat', '-c') | Out-Null
            $endAfter = $endBefore
            $advanced = $false
            $saidFinished = $false
            $lastSampleEmpty = $false
            $endLog = ''
            while ((Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 2
                $endLog = (Adb @('logcat', '-d', '-s', 'SafeTube')) -join "`n"
                if ($endLog -match 'Approved queue finished') {
                    $saidFinished = $true
                    $endAfter = $null
                    break
                }
                $snap = PlayingNow
                if ($snap -and ("$($snap.videoId)" -ne "$($endBefore.videoId)")) {
                    $endAfter = $snap
                    $advanced = $true
                    break
                }
                if ($snap) { $endAfter = $snap; $lastSampleEmpty = $false } else { $lastSampleEmpty = $true }
            }
            $stopped = ($null -eq $endAfter) -or $lastSampleEmpty
            Log "  end-of-video: was $($endBefore.videoId) at $($endBefore.positionSec)s of $($endBefore.durationSec)s; now $(if ($advanced) { "advanced to $($endAfter.videoId)" } elseif ($stopped) { 'playback stopped' } else { "$($endAfter.videoId) at $($endAfter.positionSec)s" })"
            if ($advanced) {
                Record 'end-of-video-handling' $true "advanced to next approved item: $($endAfter.videoId)"
            } elseif ($stopped) {
                # W12, D5: "playback stopped" is not "the queue finished". The completion is an event
                # *the app reports* plus the player leaving the screen, and both are required. That is
                # what makes a transient empty status sample harmless: a hand-over has neither the
                # app's own report nor the library on screen, so it cannot be recorded as a completed
                # queue - and a queue that silently died still fails, as it did before.
                Dump 'end-of-video-screen'
                $facts = Get-UiFacts (Join-Path $out 'end-of-video-screen.xml')
                $playerGone = [bool]($facts -and ($facts.Dump -match 'SafeTube for Kids') -and ($facts.Dump -match 'Refresh'))
                if ($saidFinished -and $playerGone) {
                    Record 'end-of-video-handling' $true 'queue ended: the app reported the approved queue finished and the player screen is gone'
                } elseif ($saidFinished -or $playerGone) {
                    Record 'end-of-video-handling' $false "only half the end is evidenced (saidFinished=$saidFinished playerGone=$playerGone), so the queue may simply have died"
                } else {
                    Record 'end-of-video-handling' $false 'playback stopped but the app never reported the queue finishing, so the queue may simply have died'
                }
            } else {
                Record 'end-of-video-handling' $false "still on $($endAfter.videoId) at $($endAfter.positionSec)s of $($endAfter.durationSec)s after waiting"
            }
        }
        }   # end of the "same video, short enough to watch out" branch added in W12.1
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
    $aapt2 = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe' } else { '' }
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

# ---------------------------------------------------------------- transport row D-pad focus (W13.2)
# The transport controls are the only player UI reached through a *focus graph* instead of a key handler,
# and both ways they were broken were invisible to every API-based check: the overlay hid the row out from
# under the remote 4s after the last input, and the buttons lost focus to the full-screen surface. Only the
# focused node on screen can witness that, which costs one uiautomator dump per D-pad step - and is why
# this block sits after every API-based assertion in the tier (see the note on Dump above).
if ($Tier -eq 'player' -or $Tier -eq 'full') {
    Log '=== W13.2: transport row D-pad focus ==='
    EnsureApp 'transport-focus' | Out-Null

    # The video must outlast the walk. The walk costs tens of seconds of dumps and key presses, and a video
    # that ends during it turns every later assertion into a pass against a stopped player - which is
    # exactly how a 27s clip produced a vacuous "pause and resume" result during W13.2. The duration is
    # read back from the app and asserted, never assumed, and candidates come from what is approved now.
    $focusState = $null
    $skipped = @()
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $candidate = Start-ApprovedPlayback 'transport-focus' $skipped
        if (-not $candidate) { break }
        $candidateDuration = [int]$candidate.durationSec
        if ($candidateDuration -ge 180) { $focusState = $candidate; break }
        Log "  [transport-focus] $($candidate.videoId) runs ${candidateDuration}s - too short for a walk of tens of seconds; trying another approved video"
        $skipped += "$($candidate.videoId)"
    }
    $focusVideo = ''
    if ($focusState) {
        $focusVideo = "$($focusState.videoId)"
        Record 'transport-focus-video' $true "$focusVideo runs $([int]$focusState.durationSec)s from source $($focusState.playlistId)"
    } else {
        Record 'transport-focus-video' $false "no approved video long enough (>=180s) could be started; candidates skipped: $($skipped -join ', ')"
    }

    if ($focusState) {
        $observed = @()
        Clear-ResumeOffer 'transport-focus'
        # Let the overlay close itself, so the first DOWN is the documented entry press and not a press that
        # merely reveals the controls - the state the manual verification used.
        Start-Sleep -Seconds 6

        # 1. Entry. DOWN hands the remote to the settings row, whose highlight is state and leaves focus on
        #    the surface; the second DOWN descends into the transport row below it.
        Key 'KEYCODE_DPAD_DOWN'
        Key 'KEYCODE_DPAD_DOWN'
        $target = Get-FocusTarget '20-transport-entry'
        $entryLabel = Get-TransportLabel $target
        Shot '20-transport-entry'
        $observed += $entryLabel
        if ($target) { Log "    entry: '$($target.Label)' class=$($target.Class) bounds=$($target.Bounds) area=$($target.Area)" }
        Record 'transport-focus-entry' ($entryLabel -eq 'Previous') "DOWN,DOWN -> '$entryLabel' ($(if ($target) { $target.Bounds } else { 'no focused node' }))"

        # 2. RIGHT across the row, one dump per press.
        $rightExpected = @('Rewind 10 seconds', 'Play/Pause', 'Forward 10 seconds', 'Next')
        for ($i = 0; $i -lt $rightExpected.Count; $i++) {
            Key 'KEYCODE_DPAD_RIGHT'
            $target = Get-FocusTarget "21-transport-right-$i"
            $label = Get-TransportLabel $target
            $observed += $label
            Log "    RIGHT -> '$label' ($(if ($target) { $target.Bounds } else { 'no focused node' }))"
        }
        Shot '21-transport-right'
        $rightSeen = @($observed[1..4])
        Record 'transport-focus-right-sequence' (($rightSeen -join '|') -eq ($rightExpected -join '|')) ("Previous > " + ($rightSeen -join ' > '))

        # 3. LEFT back through the same four.
        $leftExpected = @('Forward 10 seconds', 'Play/Pause', 'Rewind 10 seconds', 'Previous')
        for ($i = 0; $i -lt $leftExpected.Count; $i++) {
            Key 'KEYCODE_DPAD_LEFT'
            $target = Get-FocusTarget "22-transport-left-$i"
            $label = Get-TransportLabel $target
            $observed += $label
            Log "    LEFT -> '$label' ($(if ($target) { $target.Bounds } else { 'no focused node' }))"
        }
        Shot '22-transport-left'
        $leftSeen = @($observed[5..8])
        Record 'transport-focus-left-sequence' (($leftSeen -join '|') -eq ($leftExpected -join '|')) ($leftSeen -join ' > ')

        # 4. Idle. 6s is past the 4s auto-hide, which used to remove the focused control from the
        #    composition and drop focus to the full-screen surface.
        $beforeIdle = Get-TransportLabel (Get-FocusTarget '23-transport-before-idle')
        Start-Sleep -Seconds 6
        $idleTarget = Get-FocusTarget '24-transport-idle'
        $afterIdle = Get-TransportLabel $idleTarget
        Shot '24-transport-idle'
        Record 'transport-focus-idle-retention' ((($script:TransportLabels -contains $afterIdle) -and ($afterIdle -eq $beforeIdle))) "'$beforeIdle' held through 6s idle (was '$afterIdle' after; bounds $(if ($idleTarget) { $idleTarget.Bounds } else { 'unreadable' }))"

        # 5. Leaving and returning. Asserting the *surface* after UP is the point: "still focused on
        #    something" would pass even if UP did nothing at all.
        Key 'KEYCODE_DPAD_UP'
        $upTarget = Get-FocusTarget '25-transport-after-up'
        $upLabel = Get-TransportLabel $upTarget
        $leftRow = -not ($script:TransportLabels -contains $upLabel)
        Key 'KEYCODE_DPAD_DOWN'
        Key 'KEYCODE_DPAD_DOWN'
        $backTarget = Get-FocusTarget '26-transport-re-entry'
        $backLabel = Get-TransportLabel $backTarget
        Shot '26-transport-re-entry'
        Record 'transport-focus-vertical-navigation' ($leftRow -and ($backLabel -eq 'Previous')) "UP -> '$upLabel' ($(if ($upTarget) { $upTarget.Bounds } else { 'unreadable' })); DOWN,DOWN -> '$backLabel'"

        # 6. The walk must not have been a walk over a stopped or changed player.
        $afterWalk = Get-PlaybackState 'transport-focus' 3
        $aliveDetail = if ($null -eq $afterWalk) {
            'GET /status reported nothing playing after the walk'
        } else {
            "video=$($afterWalk.videoId) playing=$($afterWalk.playing) at $($afterWalk.positionSec)s of $($afterWalk.durationSec)s"
        }
        $alive = ($null -ne $afterWalk) -and ("$($afterWalk.videoId)" -eq $focusVideo) -and ($afterWalk.playing -eq $true)
        Record 'transport-focus-playback-alive' $alive $aliveDetail
        Log "  transport focus sequence: $($observed -join ' > ')"
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
Restore-ScreenSaver
if ($script:blocked) { Log 'FINAL: BLOCKED (player features could not be exercised over the remote)'; exit 3 }
if ($script:failures.Count -gt 0) { Log "FINAL: FAIL ($($script:failures -join ', '))"; exit 1 }
Log 'FINAL: PASS'
exit 0







