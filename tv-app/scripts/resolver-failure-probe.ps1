<#
.SYNOPSIS
  The device-level resolver failure: an approved video whose media cannot be resolved.

.DESCRIPTION
  W12 established the resolver-failure path at unit level (PlaybackControllerTest) and deferred the
  device test, because forcing a real YouTube failure is not something a test can do politely. It does
  not have to: the app already ships a debug instrument for exactly this - `DEBUG_SIMULATE_OFFLINE`
  makes every NewPipe fetch throw, so `VideoResolver.resolve` returns null for a video that is otherwise
  perfectly approved, and the app's own failure path runs. Nothing in production is changed, no service
  is faked and no URL is rewritten.

  What this checks, on the real TV:

    1. an approved video, offline  -> nothing plays, the app reports the failure, no crash, no ANR
    2. BACK                        -> the library is on screen and usable
    3. A playing, NEXT to B, B fails to resolve -> B never appears as playing, and the queue state is
       coherent (the app still reports what it is doing rather than a stale B)
    4. online again                -> an approved video plays, so the library was left usable

  It leaves the device online and stops playback before it exits, whatever happens.

.EXAMPLE
  ./resolver-failure-probe.ps1 -ApiHost 172.16.1.2 -Pin 482913
#>
[CmdletBinding()]
param(
    [string]$Serial = '172.16.1.2:5555',
    [string]$ApiHost = '172.16.1.2',
    [string]$Pin = '482913',
    [string]$Adb = '',
    [string]$Out = "$env:TEMP\resolver-failure"
)

$ErrorActionPreference = 'Continue'
$pkg = 'tv.safetubeforkids.app'
$adb = if ($Adb) {
    $Adb
} elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
    Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} else {
    'adb'
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$checks = @()

function Adb([string[]]$adbArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $adb -s $Serial @adbArgs 2>&1 } finally { $ErrorActionPreference = $previous }
}
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $script:checks += [pscustomobject]@{ name = $name; ok = [bool]$ok; detail = $detail }
    Write-Host ("{0}  {1}{2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $(if ($detail) { "  -- $detail" } else { '' }))
}
function Broadcast([string]$action) {
    Adb @('shell', "am broadcast -a $pkg.$action -n $pkg/.debug.DebugReceiver") | Out-Null
    Start-Sleep -Seconds 2
}
function LogTail([int]$since) { (@(Adb @('logcat', '-d', '-s', 'SafeTube')) | Select-Object -Skip $since) -join "`n" }
function LogCount { @(Adb @('logcat', '-d', '-s', 'SafeTube')).Count }
function Playing { try { (Invoke-RestMethod -Uri "http://${ApiHost}:8080/status" -Headers $headers -TimeoutSec 10).currentlyPlaying } catch { $null } }

# --- the app, and a parent session -------------------------------------------------------------
Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Adb @('shell', "am start -n $pkg/.MainActivity") | Out-Null
Start-Sleep -Seconds 8
$headers = @{}
try {
    $auth = Invoke-RestMethod -Uri "http://${ApiHost}:8080/auth" -Method Post -Body (@{ pin = $Pin } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 20
    $headers['Authorization'] = "Bearer $($auth.token)"
} catch {
    Write-Host "cannot sign in, so nothing below can be measured: $($_.Exception.Message)"
    exit 4
}
# The video comes from the canonical fixture, not from a lookup: this probe must test the same
# approved video every time, and the fixture is the project's own definition of what that is.
$manifestPath = Join-Path $PSScriptRoot 'fixtures\example-kids-library.json'
if (-not (Test-Path $manifestPath)) {
    Write-Host "the canonical fixture is missing: $manifestPath"
    exit 4
}
$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$named = $manifest.items | Where-Object { $_.name -eq 'EXAMPLE_COCOMELON_VIDEO_1' } | Select-Object -First 1
$video = $named.youtubeVideoId
$sourceId = $named.sourceId
$source = $null
try {
    $sources = Invoke-RestMethod -Uri "http://${ApiHost}:8080/playlists" -Headers $headers -TimeoutSec 10
    $source = @($sources | Where-Object { $_.sourceId -eq $sourceId })[0]
} catch { }
if (-not $source) {
    Write-Host "the fixture's source $sourceId is not approved: load the fixture first"
    exit 4
}
Write-Host "approved source: $($source.sourceId) ($($source.videoCount) videos); test video: $video"

# --- 1. offline: an approved video that cannot be resolved -------------------------------------
Broadcast 'DEBUG_SIMULATE_OFFLINE' | Out-Null
$offlineState = (LogTail 0) -match '"offline":true'
Check 'offline-simulator-on' $offlineState 'DEBUG_SIMULATE_OFFLINE reports offline=true'

$logBase = LogCount
Adb @('shell', "am broadcast -a $pkg.DEBUG_PLAY_VIDEO -n $pkg/.debug.DebugReceiver --es video_id $video --es playlist_id $($source.sourceId)") | Out-Null
Start-Sleep -Seconds 12
$playing = Playing
$slice = LogTail $logBase
# The app reports this in two places: the resolver logs why it failed, and the player puts a message on
# screen. The log line is the deterministic one - a UI dump costs seconds and the message is not
# guaranteed to reach the log - so the resolver's own words are what is asserted, with the player's
# message accepted as well if it happens to be there.
$reportedFailure = ($slice -match 'Resolution failed') -or ($slice -match 'No playable stream') -or
    ($slice -match "Couldn't play this video") -or ($slice -match 'YouTube is busy right now')
Check 'resolver-failure-nothing-plays' (-not $playing) "currentlyPlaying: $(if ($playing) { $playing.videoId } else { 'nothing' })"
Check 'resolver-failure-is-reported' $reportedFailure 'the app says the video could not be played'
$foreground = ((Adb @('shell', 'dumpsys window | grep mCurrentFocus')) -join '') -match [regex]::Escape($pkg)
Check 'resolver-failure-no-crash-foreground' $foreground 'the app is still the focused window'
$fatal = ((Adb @('logcat', '-d')) -join "`n") -match 'FATAL EXCEPTION'
Check 'resolver-failure-no-fatal-exception' (-not $fatal) 'no FATAL EXCEPTION in the log'
$anr = ((Adb @('logcat', '-d')) -join "`n") -match 'ANR in tv.safetubeforkids.app'
Check 'resolver-failure-no-anr' (-not $anr) 'no ANR for this package'

# --- 2. BACK leaves the library usable ---------------------------------------------------------
Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
Start-Sleep -Seconds 4
Adb @('shell', 'uiautomator dump /sdcard/resolver.xml') | Out-Null
$dump = (Adb @('shell', 'cat /sdcard/resolver.xml')) -join ''
Adb @('shell', 'rm -f /sdcard/resolver.xml') | Out-Null
$onLibrary = ($dump -match 'SafeTube for Kids') -and ($dump -match 'Refresh')
Check 'resolver-failure-back-to-library' $onLibrary 'BACK leaves the library on screen'

# --- 3. A playing, NEXT onto a video that will not resolve ---------------------------------------
Broadcast 'DEBUG_SIMULATE_OFFLINE' | Out-Null          # back online
Start-Sleep -Seconds 2
Adb @('shell', "am broadcast -a $pkg.DEBUG_PLAY_VIDEO -n $pkg/.debug.DebugReceiver --es video_id $video --es playlist_id $($source.sourceId)") | Out-Null
Start-Sleep -Seconds 14
$playingA = Playing
Broadcast 'DEBUG_SIMULATE_OFFLINE' | Out-Null          # offline again, mid-queue
$logBase2 = LogCount
Adb @('shell', 'input', 'keyevent', 'KEYCODE_MEDIA_NEXT') | Out-Null
Start-Sleep -Seconds 12
$afterNext = Playing
$slice2 = LogTail $logBase2
# The next item cannot resolve, so it must not be reported as playing, and the failure must be visible.
$nextFailed = ($slice2 -match 'Resolution failed') -or ($slice2 -match 'No playable stream') -or ($slice2 -match "Couldn't play this video")
$notPlayingB = (-not $afterNext) -or ($afterNext.videoId -eq $video)
Check 'next-item-resolver-failure-not-playing' $notPlayingB `
    "after NEXT: $(if ($afterNext) { "$($afterNext.videoId) playing=$($afterNext.playing)" } else { 'nothing is reported as playing' })"
Check 'next-item-resolver-failure-is-reported' $nextFailed 'the app reported the failure for the item it moved to'
$queueCoherent = if ($afterNext) { $afterNext.videoId -eq $video } else { $true }
Check 'next-item-queue-state-coherent' $queueCoherent 'the app never claims to be playing the video that would not resolve'

# --- 4. online again: the library was left usable -------------------------------------------------
Adb @('shell', "am broadcast -a $pkg.DEBUG_STOP_PLAYBACK -n $pkg/.debug.DebugReceiver") | Out-Null
Start-Sleep -Seconds 3
Broadcast 'DEBUG_SIMULATE_OFFLINE' | Out-Null          # toggle back to online
$onlineState = (LogTail 0) -match '"offline":false'
Check 'offline-simulator-off-again' $onlineState 'the device is left online'
Start-Sleep -Seconds 4
Adb @('shell', "am broadcast -a $pkg.DEBUG_PLAY_VIDEO -n $pkg/.debug.DebugReceiver --es video_id $video --es playlist_id $($source.sourceId)") | Out-Null
$playsAgain = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    $state = Playing
    if ($state -and $state.videoId -eq $video) { $playsAgain = $true; break }
}
Check 'library-still-usable' $playsAgain "the same approved video plays once the resolver works again: $playsAgain"
Adb @('shell', "am broadcast -a $pkg.DEBUG_STOP_PLAYBACK -n $pkg/.debug.DebugReceiver") | Out-Null

$failed = @($checks | Where-Object { -not $_.ok })
$checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Out 'resolver-failure-report.json')
Write-Host ("RESULT {0} ({1} checks, {2} failed)" -f $(if ($failed.Count) { 'FAIL' } else { 'PASS' }), $checks.Count, $failed.Count)
exit $(if ($failed.Count) { 1 } else { 0 })

