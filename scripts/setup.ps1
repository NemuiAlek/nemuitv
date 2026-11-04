# One-time NemuiTV setup from your computer (Windows): installs the app on a Google TV over Wi-Fi and turns on the
# Home-button takeover and Now Playing. Google's own home screen is never disabled or changed.
# Usage: scripts\setup.ps1 [-Apk PATH] [-Serial ADDRESS]      Undo: scripts\undo.ps1
param(
    [string]$Apk = '',
    [string]$Serial = '',
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'One-time NemuiTV setup from your computer (Windows): installs the app on a Google TV over Wi-Fi and turns on the'
    Write-Host "Home-button takeover and Now Playing. Google's own home screen is never disabled or changed."
    Write-Host 'Usage: scripts\setup.ps1 [-Apk PATH] [-Serial ADDRESS]      Undo: scripts\undo.ps1'
    exit 0
}

# ---- arguments (resolved before _common.ps1 moves us to the project folder) ----
if ($Apk -ne '') {
    if (-not [System.IO.Path]::IsPathRooted($Apk)) { $Apk = Join-Path (Get-Location).Path $Apk }
}
. "$PSScriptRoot\_common.ps1"
$ScriptDir = $PSScriptRoot
$TvSvc = "$Pkg/$Pkg.takeover.HomeTakeoverService"
$Listener = "$Pkg/$Pkg.nowplaying.NowPlayingListener"
$ListenerShort = "$Pkg/.nowplaying.NowPlayingListener"
$SvcShort = "$Pkg/.takeover.HomeTakeoverService"

function Say { param([string]$m) Write-Host $m }
function Step { param([string]$n, [string]$m) Write-Host ''; Write-Host "[$n] $m" }
function Fail { param([string]$m) Stop-Script "`nStopped: $m" }
function Ask {
    param([string]$Prompt)
    try { return (Read-Host $Prompt) } catch { return '' }
}
function Invoke-Run {
    param([string[]]$ShellArgs)
    $o = Invoke-Adb (@('shell') + $ShellArgs)
    $text = $ShellArgs -join ' '
    if ($global:AdbExit -ne 0) { Fail "adb shell $text failed:`n$o" }
    if ($o -match 'exception|error|not allowed|unknown') { Fail "adb shell $text failed:`n$o" }
}

# ---- 1. adb ----
Step '1/6' 'Looking for adb (the tool that talks to your TV)'
$onPath = Get-Command adb -ErrorAction SilentlyContinue
$pathOk = $false
if ($onPath) {
    if ((Get-AdbMajor 'adb') -ge 30) { $pathOk = $true }
    else {
        Say "The adb on this computer is too old for wireless pairing (version $(Get-AdbVersionText 'adb')): $($onPath.Source)"
    }
}
$localAdb = "$ScriptDir\platform-tools\adb.exe"
if ((Test-Path -LiteralPath $localAdb -PathType Leaf) -and ((Get-AdbMajor $localAdb) -ge 30)) {
    $global:AdbBin = $localAdb; Say "Using $($global:AdbBin)"
} elseif ($pathOk) {
    $global:AdbBin = 'adb'; Say "Using adb from your PATH: $($onPath.Source)"
} else {
    $Url = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
    if ($onPath) { Say 'I need a newer adb.' } else { Say "adb wasn't found on this computer." }
    Say "I can download Google's official platform-tools from:"
    Say "  $Url"
    Say "into $ScriptDir\platform-tools\ (nothing is installed system-wide)."
    $ans = Ask 'Download it now? (y/N)'
    if ($ans -notmatch '^(y|yes)$') {
        Fail 'adb is needed. Install it from developer.android.com (search "SDK Platform Tools") and run this again.'
    }
    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ('nemuitv-' + [guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $tmpDir -Force)
    try {
        Say 'Downloading...'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $ProgressPreference = 'SilentlyContinue'   # the progress bar makes Windows PowerShell 5.1 downloads very slow
            Invoke-WebRequest -Uri $Url -OutFile "$tmpDir\pt.zip" -UseBasicParsing
        } catch {
            Fail 'the download failed. Check your internet connection and run this again.'
        }
        if (Test-Path -LiteralPath "$ScriptDir\platform-tools") { Remove-Item -LiteralPath "$ScriptDir\platform-tools" -Recurse -Force }
        try {
            Expand-Archive -LiteralPath "$tmpDir\pt.zip" -DestinationPath $ScriptDir -Force
        } catch {
            Fail "couldn't unpack the download."
        }
        if (-not (Test-Path -LiteralPath "$ScriptDir\platform-tools\adb.exe" -PathType Leaf)) { Fail "the download didn't contain adb." }
        $global:AdbBin = "$ScriptDir\platform-tools\adb.exe"; Say "Downloaded to $ScriptDir\platform-tools\"
    } finally {
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ---- find the APK now so a mistake shows up before we touch the TV ----
if ($Apk -eq '') {
    # Look in the project folder first (the zip's top folder); only if nothing is there, in its parent.
    $found = @()
    $tiers = @(
        @($ScriptDir, (Get-Location).Path),
        @((Split-Path -Parent (Get-Location).Path))
    )
    foreach ($tier in $tiers) {
        foreach ($d in $tier) {
            $hits = @(Get-ChildItem -LiteralPath $d -Filter 'NemuiTV-*.apk' -File -ErrorAction SilentlyContinue)
            foreach ($f in $hits) {
                $dup = $false
                foreach ($g in $found) { if ($g -ieq $f.FullName) { $dup = $true } }
                if (-not $dup) { $found += $f.FullName }
            }
        }
        if ($found.Count -gt 0) { break }
    }
    if ($found.Count -eq 0) { Fail 'no NemuiTV-*.apk found next to this folder. Download it from the GitHub release, or pass -Apk PATH.' }
    # Several copies that are byte-for-byte the same are fine: use the first.
    $firstHash = (Get-FileHash -LiteralPath $found[0] -Algorithm SHA256).Hash
    foreach ($f in $found) {
        if ((Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash -ne $firstHash) {
            [Console]::Error.WriteLine('Found more than one APK:')
            foreach ($x in $found) { [Console]::Error.WriteLine("  $x") }
            Fail 'they differ. Pick one with -Apk PATH.'
        }
    }
    $Apk = $found[0]
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { Fail "APK not found: $Apk" }

# ---- 2. connect ----
Step '2/6' 'Connecting to your TV'
if ($global:AdbSerial) {
    Say "Using device $($global:AdbSerial)."
} else {
    $pick = Select-Device
    if ($pick -eq 'ok') {
        Say "One device is already connected ($($global:AdbSerial)), skipping pairing."
    } elseif ($pick -eq 'many') {
        [Console]::Error.WriteLine('More than one device is connected:')
        [Console]::Error.WriteLine($global:PickList)
        Fail 'run this again with -Serial ADDRESS for the TV you want.'
    } else {
        Write-Host 'No device is connected yet. On the TV:'
        Write-Host '  1. Settings > System > About, then press OK on "Android TV OS build" 7 times (Developer options turn on).'
        Write-Host '  2. Settings > System > Developer options > turn on "Wireless debugging".'
        Write-Host '  3. Choose "Pair device with pairing code". The TV shows an address (IP:port) and a 6-digit code.'
        Write-Host "The pairing popup shows an address and a 6-digit code (use these first). After pairing, the Wireless debugging screen"
        Write-Host "shows 'IP address & Port' (a different port) - that's the connect address."
        $pairAddr = (Ask 'Pairing address (IP:port shown on the TV)').Trim()
        if ($pairAddr -eq '') { Fail 'no pairing address entered.' }
        $pairCode = (Ask 'Pairing code (6 digits)').Trim()
        if ($pairCode -eq '') { Fail 'no pairing code entered.' }
        $out = Invoke-Adb @('pair', $pairAddr, $pairCode) -NoSerial -NoLost
        if ($out -notmatch '(?i)success') { Fail "pairing didn't work: $out`nCheck the address and code on the TV (the code changes each time) and try again." }
        Say 'Paired.'
        Say "Now go back one screen on the TV: Wireless debugging shows 'IP address & Port' (a different port than before)."
        $connAddr = (Ask 'Connect address (IP:port)').Trim()
        if ($connAddr -eq '') { Fail 'no connect address entered.' }
        $out = Invoke-Adb @('connect', $connAddr) -NoSerial -NoLost
        if ($out -notmatch '(?i)connected to') { Fail "couldn't connect: $out" }
        $global:AdbSerial = $connAddr; Say "Connected to $($global:AdbSerial)."
        Say "If the TV asks 'Allow USB debugging?', choose Always allow."
    }
}
Assert-Device
$state = (Invoke-Adb @('get-state') -NoLost).Trim()
if ($state -cne 'device') { Fail "the TV isn't ready (state: $state). If it says unauthorized, accept the prompt on the TV." }

# ---- 3. install ----
Step '3/6' 'Installing NemuiTV'
Say "APK: $Apk"
$out = Invoke-Adb @('install', '-r', $Apk)
if ($global:AdbExit -ne 0) { Fail "install failed:`n$out" }
if ($out -cnotmatch 'Success') { Fail "install failed:`n$out" }
if (-not (Test-Pkg $Pkg)) { Fail "installed, but the TV doesn't list $Pkg." }
Say 'Installed.'

# ---- 4. grants ----
Step '4/6' 'Giving NemuiTV the permissions it needs'
Invoke-Run @('pm', 'grant', $Pkg, 'android.permission.WRITE_SECURE_SETTINGS')
Invoke-Run @('appops', 'set', $Pkg, 'WRITE_SETTINGS', 'allow')
Say 'Granted: change TV settings (for the Setup page and screensaver timers).'

# ---- 5. Home button + Now Playing (Google's home stays as it is) ----
Step '5/6' 'Turning on the Home button and Now Playing'
$cur = (Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).Trim()
if (($cur -ceq 'null') -or ($cur -eq '')) { $new = $TvSvc }
elseif (":${cur}:".Contains(":${TvSvc}:")) { $new = $cur }
elseif (":${cur}:".Contains(":${SvcShort}:")) { $new = $cur }
else { $new = "${cur}:${TvSvc}" }   # keep any other accessibility services that are already on
Invoke-Run @('settings', 'put', 'secure', 'enabled_accessibility_services', $new)
Invoke-Run @('settings', 'put', 'secure', 'accessibility_enabled', '1')
$bound = $false
for ($i = 0; $i -lt 10; $i++) {
    $dump = Invoke-Adb @('shell', 'dumpsys', 'accessibility')
    if ($dump -match 'Bound services:.*NemuiTV Home button') { $bound = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $bound) { Fail "the Home-button setting was written, but the service isn't running yet.`nTry again in a moment, or look at: adb logcat -d -s NemuiTV" }
Say "Home button: NemuiTV opens when you press Home on the remote. Google's home screen stays installed."

[void](Invoke-Adb @('shell', 'cmd', 'notification', 'allow_listener', $Listener))
$enabled = ''
$listenerOn = $false
for ($i = 0; $i -lt 10; $i++) {
    $enabled = (Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')).Trim()
    $wrapped = ":${enabled}:"
    if ($wrapped.Contains(":${Listener}:") -or $wrapped.Contains(":${ListenerShort}:")) { $listenerOn = $true; break }
    Start-Sleep -Milliseconds 500
}
if ($listenerOn) {
    Say "Now Playing: NemuiTV can see what's playing."
} else {
    Fail "Now Playing wasn't accepted by Android (enabled_notification_listeners = $enabled)."
}

# ---- 6. done ----
Step '6/6' 'Done'
Say '  - adb: ready'
Say "  - Connected: $($global:AdbSerial)"
Say '  - Installed: NemuiTV'
Say '  - Permissions: granted'
Say '  - Home button: on'
Say '  - Now Playing: on'
Say ''
Say 'Continue on the TV (the Setup checklist opens). You can turn Wireless debugging off now.'
[void](Invoke-Adb @('shell', 'am', 'start', '-n', "$Pkg/.HomeActivity"))
