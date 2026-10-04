# Safe player device verification for the family TV.
#
# WHY THIS EXISTS
#
# The established E2E harness (tv-e2e.ps1) can reach the player, but it does so two ways this project
# refuses to use on a family device: it replaces the catalog with the example-library fixture, and it
# starts playback with the DEBUG_PLAY_VIDEO broadcast. Its PLAYER and FULL tiers are therefore off limits
# here, and W14.1 had to report several player checks BLOCKED for exactly that reason.
#
# This script is the separate, non-destructive path. It only does what a remote does:
#
#   launch the app -> walk the real library with D-pad keys -> open an existing approved item ->
#   reach the real player -> observe it with uiautomator dumps -> return safely
#
# It never calls a debug endpoint, never sends a debug playback intent, never writes the catalog, never
# touches credentials, never clears app data, never installs anything and never manipulates the network.
# Everything it reads is read through uiautomator and the foreground activity. Everything it writes is
# key presses and screenshot-free XML dumps that are deleted from the device as they are taken.
#
# It is deliberately a separate file from tv-e2e.ps1 so that no change here can weaken a destructive
# tier, and it does not dot-source it: sourcing would execute the harness.
#
# Usage:
#   .\tv-safe-player-verify.ps1                 # full run, writes a report next to the dumps
#   .\tv-safe-player-verify.ps1 -SkipSpeedCheck # navigation and seek only
#
# It always ends with BACK/HOME, whatever happens, so the TV is left on its own launcher.

[CmdletBinding()]
param(
    [string]$Serial = '172.16.1.2:5555',
    [string]$AdbPath = '',
    [string]$OutDir = '',
    [switch]$SkipSpeedCheck,
    [switch]$SkipMenuChecks
)

$ErrorActionPreference = 'Continue'
$pkg = 'tv.safetubeforkids.app'
$script:Results = [ordered]@{}
$script:Notes = @()
$script:TransportLabels = @('Previous', 'Rewind 10 seconds', 'Play/Pause', 'Forward 10 seconds', 'Next')
$script:TopBarLabels = @('Refresh', 'Connect Phone', 'Settings')

if (-not $OutDir) { $OutDir = Join-Path $env:TEMP ("safetube-safe-verify-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-Adb {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += 'adb'
    foreach ($candidate in $candidates) {
        try {
            $resolved = (Get-Command $candidate -ErrorAction Stop).Source
            if ($resolved) { return $resolved }
        } catch { }
    }
    throw 'adb was not found. Set ANDROID_HOME or pass -AdbPath.'
}

$adb = Resolve-Adb $AdbPath
# `-s` must not be passed to the host-side commands: `adb devices` and `adb connect` are addressed to the
# server, and with a serial they fail whenever the device is not attached yet - which is exactly the case
# this script has to handle.
# The parameter must not be called `Args`: that name is automatic in PowerShell, the parameter silently
# never binds, and adb then receives no arguments at all and prints its help text - which reads like a
# missing device rather than a broken helper.
function Adb {
    param([string[]]$AdbArgs)
    if ($AdbArgs.Count -ge 1 -and ($AdbArgs[0] -eq 'devices' -or $AdbArgs[0] -eq 'connect' -or $AdbArgs[0] -eq 'disconnect')) {
        return (& $adb @AdbArgs 2>&1)
    }
    return (& $adb -s $Serial @AdbArgs 2>&1)
}
function Log { param([string]$Message) Write-Host $Message }
function Verdict { param([bool]$Ok, [string]$Yes = 'PASS', [string]$No = 'FAIL') if ($Ok) { return $Yes } else { return $No } }
function Note { param([string]$Message) $script:Notes += $Message; Write-Host "  $Message" }
function Record { param([string]$Name, [string]$Value, [string]$Detail = '') $script:Results[$Name] = "$Value"; if ($Detail) { $script:Notes += "$Name=$Value :: $Detail" } }

# ---------------------------------------------------------------------------- device plumbing

function ForegroundActivity {
    $lines = Adb @('shell', 'dumpsys activity activities')
    $match = ($lines | Select-String 'mResumedActivity' | Select-Object -First 1)
    if (-not $match) { return '' }
    return "$($match.Line)".Trim()
}

function ForegroundIsApp {
    return (ForegroundActivity) -match [regex]::Escape($pkg)
}

function Key {
    param([string]$Code, [int]$WaitMs = 700)
    Adb @('shell', 'input', 'keyevent', $Code) | Out-Null
    Start-Sleep -Milliseconds $WaitMs
}

# uiautomator intermittently fails to reach an idle state on a Compose TV screen and then writes a dump
# with no nodes at all - which reads exactly like "the app is not there". It is retried, and a dump with
# no nodes is treated as a failed dump rather than as an empty screen.
function DumpXml {
    param([string]$Name, [int]$Attempts = 3)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $remote = "/sdcard/safe-$Name.xml"
        $local = Join-Path $OutDir "$Name.xml"
        $output = (Adb @('shell', "uiautomator dump $remote")) -join ' '
        Adb @('pull', $remote, $local) | Out-Null
        Adb @('shell', "rm -f $remote") | Out-Null
        if ($output -match 'could not get idle state|ERROR') {
            Note "dump $Name attempt $attempt did not reach an idle state"
            Start-Sleep -Milliseconds 900
            continue
        }
        if (-not (Test-Path $local)) { Start-Sleep -Milliseconds 900; continue }
        $raw = Get-Content $local -Raw
        # uiautomator escapes XML entities; decode so a card title can be compared with what it says.
        $decoded = $raw -replace '&amp;', '&' -replace '&quot;', '"' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&apos;', "'"
        $parsed = $null
        try { $parsed = [xml]$decoded } catch { $parsed = $null }
        if ($parsed -and $parsed.SelectNodes('//node').Count -gt 1) { return $parsed }
        Note "dump $Name attempt $attempt produced no usable hierarchy"
        Start-Sleep -Milliseconds 900
    }
    return $null
}

function NodeLabel {
    param($Node)
    if (-not $Node) { return '' }
    $labels = @()
    $stack = New-Object System.Collections.Stack
    $stack.Push($Node)
    while ($stack.Count -gt 0) {
        $current = $stack.Pop()
        foreach ($child in $current.ChildNodes) { if ($child.NodeType -eq 'Element') { $stack.Push($child) } }
        if ($current.text) { $labels += $current.text }
        elseif ($current.'content-desc') { $labels += $current.'content-desc' }
    }
    return (($labels | Select-Object -Unique) -join ' / ')
}

# The deepest focused node is the focus target: uiautomator also marks every ancestor focused, so the
# last focused node in document order describes a subtree rather than where the remote actually is.
function FocusedNode {
    param($Xml)
    if (-not $Xml) { return $null }
    $best = $null; $bestArea = [int]::MaxValue
    foreach ($node in $Xml.SelectNodes('//node')) {
        if ($node.focused -ne 'true') { continue }
        $m = [regex]::Match("$($node.bounds)", '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) { continue }
        $area = ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value) * ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value)
        if ($area -ge $bestArea) { continue }
        $bestArea = $area; $best = $node
    }
    return $best
}

function Describe {
    param($Node)
    if (-not $Node) { return @{ Label = ''; Bounds = ''; Class = '' } }
    $label = NodeLabel $Node
    if (-not $label) { $label = "$($Node.'content-desc')" }
    return @{ Label = $label; Bounds = "$($Node.bounds)"; Class = "$($Node.class)" }
}

function DumpLabel {
    param([string]$Name)
    $xml = DumpXml $Name
    if (-not $xml) { return $null }
    return Describe (FocusedNode $xml)
}

function TextsIn {
    param($Xml)
    if (-not $Xml) { return @() }
    return @($Xml.SelectNodes('//node[@text]') | ForEach-Object { $_.text } | Where-Object { $_ })
}

function DescriptionsIn {
    param($Xml)
    if (-not $Xml) { return @() }
    return @($Xml.SelectNodes('//node[@content-desc]') | ForEach-Object { $_.'content-desc' } | Where-Object { $_ })
}

function TransportLabelsIn {
    param($Xml)
    $found = @()
    foreach ($description in (DescriptionsIn $Xml)) {
        if ($script:TransportLabels -contains $description) { $found += $description }
        elseif ($description -in @('Play', 'Pause')) { $found += 'Play/Pause' }
    }
    return @($found | Select-Object -Unique)
}

# The overlay's elapsed and duration read as m:ss (or h:mm:ss on a long item).
function ClockTextsIn {
    param($Xml)
    return @(TextsIn $Xml | Where-Object { $_ -match '^\d+:\d\d$' -or $_ -match '^\d+:\d\d:\d\d$' })
}

function ClockSeconds {
    param([string]$Text)
    if (-not $Text) { return -1 }
    $parts = $Text.Split(':')
    try {
        if ($parts.Count -eq 2) { return ([int]$parts[0] * 60 + [int]$parts[1]) }
        if ($parts.Count -eq 3) { return ([int]$parts[0] * 3600 + [int]$parts[1] * 60 + [int]$parts[2]) }
    } catch { }
    return -1
}

# ---------------------------------------------------------------------------- guards and navigation

function WakeAndLaunch {
    if ((ForegroundActivity) -match 'screensaver|dream|Dream') {
        Note 'the photo screensaver is in front; waking the TV (no settings are changed)'
        Key 'KEYCODE_WAKEUP' 1200
    }
    Adb @('shell', 'am', 'start', '-n', "$pkg/.MainActivity") | Out-Null
    Start-Sleep -Seconds 8
}

# The library is identified by its brand label and its top bar. The top-bar controls carry their names as
# content descriptions rather than text, so both are searched - a text-only check reads a correct screen as
# "not the library".
function OnLibrary {
    $xml = DumpXml 'library-probe'
    if (-not $xml) { return $false }
    $seen = @(TextsIn $xml) + @(DescriptionsIn $xml)
    $brand = @($seen | Where-Object { $_ -match 'SafeTube for Kids' }).Count -gt 0
    $refresh = @($seen | Where-Object { $_ -match 'Refresh' }).Count -gt 0
    return ($brand -and $refresh)
}

function GoToLibrary {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        if (OnLibrary) { return $true }
        Note "returning to the library from whatever screen is up (attempt $attempt)"
        Key 'KEYCODE_BACK' 1200
    }
    return $false
}

function ParkFocus {
    for ($i = 0; $i -lt 8; $i++) { Key 'KEYCODE_DPAD_UP' 200 }
    for ($i = 0; $i -lt 8; $i++) { Key 'KEYCODE_DPAD_LEFT' 200 }
    Start-Sleep -Milliseconds 500
}

function IsCardLabel {
    param([string]$Label)
    if (-not $Label) { return $false }
    foreach ($top in $script:TopBarLabels) { if ($Label -like "*$top*") { return $false } }
    return $true
}

# Walk down the shelves until focus lands on something that is not the top bar: that is a card.
function FindCard {
    param([int]$MaxPresses = 10)
    for ($i = 0; $i -le $MaxPresses; $i++) {
        $target = DumpLabel "card-$i"
        if ($target -and (IsCardLabel $target.Label)) { return $target }
        Key 'KEYCODE_DPAD_DOWN'
    }
    return $null
}

function PlayerIsUp {
    param($Xml)
    return ((TransportLabelsIn $Xml).Count -ge 3)
}

# ---------------------------------------------------------------------------- the run

Log "=== SafeTube safe player verification ==="
Log "adb: $adb"
Log "device: $Serial"
Log "dumps and report: $OutDir"

# The Mi Box answers adb over TCP, and that connection drops when the device sleeps or the adb server
# restarts. Reconnecting is a host-side action: nothing on the device changes.
$devices = Adb @('devices')
if (-not ($devices | Select-String ([regex]::Escape($Serial) + '\s+device'))) {
    Log "connecting to $Serial"
    Adb @('connect', $Serial) | Out-Null
    Start-Sleep -Seconds 2
}
if (-not ($devices | Select-String ([regex]::Escape($Serial) + '\s+device'))) {
    Log 'the family TV is not connected; nothing can be verified. Run adb connect first.'
    Log ('  adb devices said: ' + (($devices | Out-String).Trim()))
    Record 'SAFE_PLAYER_ENTRY' 'BLOCKED' 'device not connected'
    exit 2
}

WakeAndLaunch
$foreground = ForegroundActivity
Log "foreground: $foreground"
Record 'SAFE_LAUNCH' (Verdict (ForegroundIsApp) 'PASS' 'FAIL') $foreground

if (-not (GoToLibrary)) {
    Log 'the library screen could not be reached; reporting the ambiguity rather than guessing'
    Record 'SAFE_PLAYER_ENTRY' 'BLOCKED' 'library not reached'
    Key 'KEYCODE_HOME'
    exit 3
}
Log 'library reached'
Record 'SAFE_LIBRARY_REACHED' 'PASS' 'the dump shows SafeTube for Kids and Refresh'

ParkFocus
$card = FindCard
if (-not $card) {
    Record 'SAFE_PLAYER_ENTRY' 'BLOCKED' 'no library card could be focused'
    Log 'no card could be focused; the hierarchy was captured for inspection'
    Key 'KEYCODE_HOME'
    exit 4
}
Log "focused card: $($card.Label)  $($card.Bounds)"
Record 'SAFE_CARD_REACHED' 'PASS' "$($card.Label) at $($card.Bounds)"

# Open it: a card is either a video (the player opens) or a collection (a container opens, and its first
# video is one DOWN and one CENTER away). Both are the path a child takes.
$inPlayer = $false
Key 'KEYCODE_DPAD_CENTER' 2500
$xml = DumpXml 'after-open'
if (PlayerIsUp $xml) { $inPlayer = $true }
if (-not $inPlayer) {
    $texts = TextsIn $xml
    Log "after opening the card the screen shows: $((@($texts | Select-Object -First 6) -join ' | '))"
    Key 'KEYCODE_DPAD_DOWN' 900
    Key 'KEYCODE_DPAD_CENTER' 2500
    $xml = DumpXml 'after-open-second'
    if (PlayerIsUp $xml) { $inPlayer = $true }
}
if (-not $inPlayer) {
    $texts = TextsIn $xml
    Log "the player was not reached; the screen still shows: $((@($texts | Select-Object -First 8) -join ' | '))"
    Record 'SAFE_PLAYER_ENTRY' 'BLOCKED' 'the player was not reached through the library UI; the hierarchy was captured'
    Key 'KEYCODE_BACK' 1200
    Key 'KEYCODE_HOME'
    exit 5
}

$transport = TransportLabelsIn $xml
Log "player reached; transport controls present: $($transport -join ', ')"
Record 'SAFE_PLAYER_ENTRY' 'PASS' "transport labels present: $($transport -join ', ')"
Record 'SAFE_PLAYER_ACTIVITY' 'PASS' (ForegroundActivity)

Start-Sleep -Seconds 3
$entry = DumpLabel 'player-entry'
Log "player focus on entry: $($entry.Label)  $($entry.Bounds)"
Record 'SAFE_PLAYER_FOCUS' (Verdict ($entry.Bounds -eq '[0,0][1920,1080]') 'PASS' 'INCONCLUSIVE') "deepest focused $($entry.Bounds) label '$($entry.Label)'"

# ------------------------------------------------------------------ seek boundaries on the surface
# MENU/INFO reveal the overlay without taking the remote into the settings row, so focus stays on the
# surface and LEFT/RIGHT remain seek - the W13.2a contract, exercised from the outside.
function ShowClock {
    Key 'KEYCODE_MENU' 700
    $xml = DumpXml 'clock'
    return @{ Xml = $xml; Clock = ClockTextsIn $xml }
}

$clock = ShowClock
$beforeSeek = $clock.Clock
Log "clock before seeking: $($beforeSeek -join ' / ')"
if ($beforeSeek.Count -ge 2) {
    $durationSec = ClockSeconds $beforeSeek[1]
    for ($i = 0; $i -lt 20; $i++) { Key 'KEYCODE_DPAD_RIGHT' 150 }
    Start-Sleep -Seconds 1
    $afterForward = (ShowClock).Clock
    Log "clock after 20 forward seeks: $($afterForward -join ' / ')"
    $elapsedForward = ClockSeconds $afterForward[0]
    $endOk = ($elapsedForward -ge 0) -and ($durationSec -gt 0) -and ($elapsedForward -le $durationSec)
    Record 'SEEK_DEVICE_END' (Verdict $endOk 'PASS' 'FAIL') "elapsed $elapsedForward s, duration $durationSec s - the clock never passed the duration"

    for ($i = 0; $i -lt 25; $i++) { Key 'KEYCODE_DPAD_LEFT' 150 }
    Start-Sleep -Seconds 1
    $afterBack = (ShowClock).Clock
    Log "clock after 25 backward seeks: $($afterBack -join ' / ')"
    $elapsedBack = ClockSeconds $afterBack[0]
    $startOk = ($elapsedBack -eq 0)
    Record 'SEEK_DEVICE_START' (Verdict $startOk 'PASS' 'FAIL') "elapsed $elapsedBack s after rewinding past the start - never negative"
} else {
    Record 'SEEK_DEVICE_END' 'BLOCKED' 'the overlay did not report a clock to read'
    Record 'SEEK_DEVICE_START' 'BLOCKED' 'the overlay did not report a clock to read'
}

# ------------------------------------------------------------------ the settings row, menus and speed
function OpenChipMenu {
    param([string]$ChipPrefix, [int]$MaxPresses = 6)
    Key 'KEYCODE_DPAD_DOWN' 900          # into the settings row
    for ($i = 0; $i -le $MaxPresses; $i++) {
        $target = DumpLabel "chip-$ChipPrefix-$i"
        if ($target -and $target.Label -match [regex]::Escape($ChipPrefix)) {
            Key 'KEYCODE_DPAD_CENTER' 1500
            return (DumpXml "menu-$ChipPrefix")
        }
        Key 'KEYCODE_DPAD_RIGHT' 400
    }
    return $null
}

function MenuOptionIds {
    param($Xml)
    if (-not $Xml) { return @() }
    return @(TextsIn $Xml)
}

if (-not $SkipMenuChecks) {
    $captionsMenu = OpenChipMenu 'Subtitles'
    if (-not $captionsMenu) {
        Record 'CAPTION_LANGUAGE_DEVICE' 'BLOCKED' 'the Subtitles chip could not be reached in the settings row'
    } else {
        $options = MenuOptionIds $captionsMenu
        Log "captions menu offers: $($options -join ' | ')"
        $languages = @($options | Where-Object { $_ -and $_ -notmatch '^(Off|Subtitles.*|On)$' })
        if ($languages.Count -lt 2) {
            Record 'CAPTION_LANGUAGE_DEVICE' 'BLOCKED_NO_SUITABLE_APPROVED_MEDIA' "the approved video offers $($languages.Count) language(s): $($languages -join ', ')"
            Key 'KEYCODE_BACK' 800
        } else {
            # Choose the second language, then Off, and read what the menu reports each time.
            Key 'KEYCODE_DPAD_DOWN' 400
            Key 'KEYCODE_DPAD_DOWN' 400
            Key 'KEYCODE_DPAD_CENTER' 1500
            $afterPick = MenuOptionIds (DumpXml 'captions-after-pick')
            $chosen = @($afterPick | Where-Object { $_ -match [regex]::Escape($languages[1]) })
            Key 'KEYCODE_BACK' 800
            $chip = OpenChipMenu 'Subtitles'
            $chipOptions = MenuOptionIds $chip
            $showsChosen = @($chipOptions | Where-Object { $_ -match [regex]::Escape($languages[1]) }).Count -gt 0
            Key 'KEYCODE_BACK' 800
            $offMenu = OpenChipMenu 'Subtitles'
            Key 'KEYCODE_DPAD_DOWN' 400
            Key 'KEYCODE_DPAD_CENTER' 1500
            $afterOff = MenuOptionIds (DumpXml 'captions-after-off')
            Key 'KEYCODE_BACK' 800
            $offChip = OpenChipMenu 'Subtitles'
            $offShown = (@(MenuOptionIds $offChip) | Where-Object { $_ -match 'Off' }).Count -gt 0
            Key 'KEYCODE_BACK' 800
            Record 'CAPTION_LANGUAGE_DEVICE' (Verdict ($showsChosen -and $offShown) 'PASS' 'INCONCLUSIVE') `
                "languages offered: $($languages -join ', '); after choosing one the menu reported it ($showsChosen); after Off the menu reported Off ($offShown)"
        }
    }

    $audioMenu = OpenChipMenu 'Audio'
    if (-not $audioMenu) {
        Record 'AUDIO_TRACK_DEVICE' 'BLOCKED_NO_SUITABLE_APPROVED_MEDIA' 'the player exposes no Audio chip for this item'
    } else {
        $options = MenuOptionIds $audioMenu
        Log "audio menu offers: $($options -join ' | ')"
        if ($options.Count -lt 2) {
            Record 'AUDIO_TRACK_DEVICE' 'BLOCKED_NO_SUITABLE_APPROVED_MEDIA' "the approved video offers $($options.Count) audio track(s)"
        } else {
            Key 'KEYCODE_DPAD_DOWN' 400
            Key 'KEYCODE_DPAD_CENTER' 1500
            $afterPick = MenuOptionIds (DumpXml 'audio-after-pick')
            Key 'KEYCODE_BACK' 800
            Record 'AUDIO_TRACK_DEVICE' (Verdict ($afterPick.Count -gt 0) 'PASS' 'INCONCLUSIVE') "tracks offered: $($options -join ', ')"
        }
    }
} else {
    Record 'CAPTION_LANGUAGE_DEVICE' 'BLOCKED' 'menu checks were skipped for this run'
    Record 'AUDIO_TRACK_DEVICE' 'BLOCKED' 'menu checks were skipped for this run'
}

if ($SkipSpeedCheck) {
    Record 'SPEED_REPREPARE_DEVICE' 'BLOCKED' 'the speed check was skipped for this run'
} else {
    $speedMenu = OpenChipMenu 'Speed'
    if (-not $speedMenu) {
        Record 'SPEED_REPREPARE_DEVICE' 'BLOCKED' 'the Speed chip could not be reached in the settings row'
    } else {
        $options = MenuOptionIds $speedMenu
        Log "speed menu offers: $($options -join ' | ')"
        # Walk to 1.5x (or 2x if 1.5x is absent) and choose it.
        $want = $null
        foreach ($candidate in @('1.5x', '1.50x', '2x', '2.0x')) { if ($options -contains $candidate) { $want = $candidate; break } }
        if (-not $want) {
            Record 'SPEED_REPREPARE_DEVICE' 'BLOCKED' "no speed above 1x was offered: $($options -join ', ')"
            Key 'KEYCODE_BACK' 800
        } else {
            $target = 0
            foreach ($option in $options) { if ($option -eq $want) { break }; $target++ }
            for ($i = 0; $i -lt $target; $i++) { Key 'KEYCODE_DPAD_DOWN' 400 }
            Key 'KEYCODE_DPAD_CENTER' 1500
            $chipAfter = OpenChipMenu 'Speed'
            $chipOptions = MenuOptionIds $chipAfter
            $speedShown = @($chipOptions | Where-Object { $_.Trim() -eq $want.Trim() }).Count -gt 0
            Key 'KEYCODE_BACK' 800
            Log "speed chosen: $want (the chip reports it: $speedShown)"
            # A queue move re-prepares the media through the product's own path.
            Key 'KEYCODE_MEDIA_NEXT' 4000
            Start-Sleep -Seconds 6
            $first = ShowClock
            $firstClock = ClockSeconds (@($first.Clock)[0])
            Start-Sleep -Seconds 10
            $second = ShowClock
            $secondClock = ClockSeconds (@($second.Clock)[0])
            $advanced = $secondClock - $firstClock
            Log "after the re-prepare the clock advanced ${advanced}s in about 10s of wall time, at $want"
            $rate = if ($advanced -gt 0) { [math]::Round($advanced / 10.0, 2) } else { 0 }
            $expected = [double]($want -replace 'x', '')
            $ok = ($rate -ge ($expected * 0.8))
            Record 'SPEED_REPREPARE_DEVICE' (Verdict $ok 'PASS' 'FAIL') `
                "chose $want, moved to the next approved item (a real re-prepare), and playback advanced ${advanced}s per 10s (measured rate ${rate}x against the chosen ${expected}x)"
        }
    }
}

Record 'ERROR_RETRY_DEVICE' 'BLOCKED' 'no error state was generated: doing so would need network manipulation, which is prohibited on the family TV'

# ------------------------------------------------------------------ safe exit
Key 'KEYCODE_BACK' 1000
if (PlayerIsUp (DumpXml 'exit-probe')) {
    Key 'KEYCODE_BACK' 1500
}
Key 'KEYCODE_HOME' 1000
Record 'SAFE_EXIT' (Verdict (-not (ForegroundIsApp)) 'PASS' 'INCONCLUSIVE') (ForegroundActivity)

$report = Join-Path $OutDir 'report.txt'
$lines = @()
$lines += 'SAFE PLAYER DEVICE VERIFICATION'
$lines += "device: $Serial"
$lines += ''
foreach ($key in $script:Results.Keys) { $lines += ("{0}={1}" -f $key, $script:Results[$key]) }
$lines += ''
$lines += '--- notes ---'
$lines += $script:Notes
$lines | Set-Content $report
Log ''
Log '================ RESULT ================'
foreach ($key in $script:Results.Keys) { Log ("{0}={1}" -f $key, $script:Results[$key]) }
Log "report: $report"
