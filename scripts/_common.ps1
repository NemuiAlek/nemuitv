# Shared bits for NemuiTV device scripts (Windows PowerShell 5.1+). Dot-source it; don't run it.
# Usage in scripts:  $Serial = ...;  . "$PSScriptRoot\_common.ps1"
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); $OutputEncoding = [Console]::OutputEncoding } catch { }

$Pkg = 'com.nemuigames.nemuitv'
$DeviceDir = "/sdcard/Android/data/$Pkg/files"

function Stop-Script {
    param([string]$Message)
    [Console]::Error.WriteLine($Message)
    exit 1
}

# Run a native program; returns its output (stdout+stderr, CR removed) as one string and sets $global:NativeExit.
function Invoke-Native {
    param([string]$Exe, [string[]]$ArgList)
    $fixed = @()
    foreach ($a in $ArgList) { $fixed += ($a -replace '"', '\"') }   # Windows PowerShell 5.1 doesn't escape embedded quotes
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $lines = @()
    try {
        $lines = @(& $Exe @fixed 2>&1 | ForEach-Object { "$_" })
        $global:NativeExit = $LASTEXITCODE
    } catch {
        $global:NativeExit = 127
        $lines = @("$_")
    } finally {
        $ErrorActionPreference = $old
    }
    return (($lines -join "`n") -replace "`r", '')
}

# platform-tools major version of an adb (the "Version 37.0.1-..." line; the first line is the protocol version).
# Returns 0 if it can't be read. `adb pair` needs 30 or newer.
function Get-AdbMajor {
    param([string]$Exe)
    $o = Invoke-Native $Exe @('version')
    foreach ($line in ($o -split "`n")) {
        if ($line -match '^Version (\d+)\.') { return [int]$Matches[1] }
    }
    return 0
}
function Get-AdbVersionText {
    param([string]$Exe)
    $o = Invoke-Native $Exe @('version')
    foreach ($line in ($o -split "`n")) {
        if ($line -match '^Version (\S+)') { return $Matches[1] }
    }
    return 'unknown'
}

# adb: the one on PATH if it is new enough, else the copy setup.ps1 downloaded into scripts\platform-tools\, else whatever is on PATH.
$global:AdbBin = 'adb'
$localAdb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
$pathAdb = Get-Command adb -ErrorAction SilentlyContinue
if ($pathAdb) {
    if (((Get-AdbMajor 'adb') -lt 30) -and (Test-Path -LiteralPath $localAdb -PathType Leaf)) { $global:AdbBin = $localAdb }
} elseif (Test-Path -LiteralPath $localAdb -PathType Leaf) {
    $global:AdbBin = $localAdb
}
if ($null -eq $Serial) { $Serial = '' }
$global:AdbSerial = $Serial
$global:AdbExit = 0

# Run adb (with -s SERIAL when one is set). Returns the output as a string; exit code in $global:AdbExit.
# Stops with a plain message if adb says the device went away.
function Invoke-Adb {
    param([string[]]$Argv, [switch]$NoSerial, [switch]$NoLost)
    $a = @()
    if ((-not $NoSerial) -and $global:AdbSerial) { $a += '-s'; $a += $global:AdbSerial }
    $a += $Argv
    $out = Invoke-Native $global:AdbBin $a
    $global:AdbExit = $global:NativeExit
    if ((-not $NoLost) -and ($global:AdbExit -ne 0) -and ($out -match 'error: (device|no devices|closed|protocol)|device offline|no devices/emulators')) {
        Stop-Script "Lost contact with the TV. Check 'adb devices' / reconnect (Wireless debugging or ADB debugging)."
    }
    return $out
}

# Choose the device when none was given. adb can list one TV twice after pairing (ip:port and an
# adb-....._adb-tls-connect._tcp entry), so compare serial numbers: the same TV is fine, different TVs are not.
# Returns 'ok' (AdbSerial set), 'none', or 'many' (really different devices; $global:PickList has them).
function Select-Device {
    $devOut = Invoke-Adb @('devices') -NoSerial -NoLost
    $devs = @()
    foreach ($line in ($devOut -split "`n")) {
        $p = $line.Trim() -split '\s+'
        if (($p.Count -ge 2) -and ($p[1] -ceq 'device')) { $devs += $p[0] }
    }
    $global:PickList = ($devs -join "`n")
    if ($devs.Count -eq 0) { return 'none' }
    if ($devs.Count -eq 1) { $global:AdbSerial = $devs[0]; return 'ok' }
    $same = $true
    $first = ''
    $ipEntry = ''
    foreach ($d in $devs) {
        if ($d -like 'emulator-*') { $same = $false; continue }
        $sn = (Invoke-Adb @('-s', $d, 'shell', 'getprop', 'ro.serialno') -NoSerial -NoLost).Trim()
        if (($global:AdbExit -ne 0) -or ($sn -eq '')) { $same = $false; continue }
        if ($first -eq '') { $first = $sn } elseif ($sn -cne $first) { $same = $false }
        if (($ipEntry -eq '') -and ($d -match '^\d+\.\d+\.\d+\.\d+:\d+$')) { $ipEntry = $d }
    }
    if (-not $same) { return 'many' }
    if ($ipEntry -ne '') { $global:AdbSerial = $ipEntry } else { $global:AdbSerial = $devs[0] }
    return 'ok'
}

# "ADB debugging" connect path (TVs without a "Wireless debugging" screen).
# Get-TvAddr: host:port from what the user typed (spaces trimmed, :5555 added when there is no port). $null when empty.
function Get-TvAddr {
    param([string]$typed)
    if ($null -eq $typed) { return $null }
    $a = $typed.Trim()
    if ($a -eq '') { return $null }
    if (-not $a.Contains(':')) { $a = "${a}:5555" }
    return $a
}
# Connect-AdbDebug ADDR: disconnect any stale entry, then connect. $true when adb says "connected to" (also "already connected to"),
# or "failed to authenticate" (first time: the TV is waiting for the user to accept; Wait-Authorized handles that).
# adb's output is left in $global:ConnectOut.
function Connect-AdbDebug {
    param([string]$addr)
    [void](Invoke-Adb @('disconnect', $addr) -NoSerial -NoLost)
    $global:ConnectOut = Invoke-Adb @('connect', $addr) -NoSerial -NoLost
    return ($global:ConnectOut -match '(?i)connected to|failed to authenticate')
}
# Wait-Authorized SECONDS: poll get-state every 2 s (env WAIT_STEP_S overrides the pause, for tests).
# $true = "device"; $false = timed out (last state in $global:LastState). Prints the Allow hint once, the first time it sees "unauthorized".
# Test-TakeoverRunning: poll every 0.5 s (env WAIT_STEP_S overrides) until Android runs NemuiTV's Home-button service.
# Checks the service by its class name in `dumpsys activity services`, not the label in `dumpsys accessibility`
# (TV makers show different labels: Sony BRAVIA lists it as "Home").
function Test-TakeoverRunning {
    param([int]$Seconds = 5)
    $max = $Seconds * 2
    $stepS = 0.5
    if ($env:WAIT_STEP_S) { $stepS = [double]$env:WAIT_STEP_S }
    for ($i = 0; $i -lt $max; $i++) {
        $out = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $Pkg) -NoLost
        # Running = an active "* ServiceRecord{... HomeTakeoverService}" block with its own "app=ProcessRecord" line
        # (old DEAD ConnectionRecords also name the service, and other services have their own app= lines).
        if ($out -cmatch '(?s)\* ServiceRecord\{[^}]*\.takeover\.HomeTakeoverService[ }](?:(?!\* ServiceRecord|\n {2}[A-Z][A-Za-z ]*:\r?\n).)*?app=ProcessRecord') { return $true }
        Start-Sleep -Milliseconds ([int]($stepS * 1000))
    }
    return $false
}

function Wait-Authorized {
    param([int]$Seconds = 60)
    $max = [int][math]::Floor($Seconds / 2)
    if ($max -lt 1) { $max = 1 }
    $stepS = 2.0
    if ($env:WAIT_STEP_S) { $stepS = [double]$env:WAIT_STEP_S }
    $hinted = $false
    $global:LastState = ''
    for ($i = 0; $i -lt $max; $i++) {
        $s = Invoke-Adb @('get-state') -NoLost
        if ($s -match '(?i)unauthorized') { $s = 'unauthorized' }
        elseif ($s -match '(?i)offline') { $s = 'offline' }
        else { $s = (($s -split "`n")[0]).Trim() }
        $global:LastState = $s
        if ($s -ceq 'device') { return $true }
        if (($s -ceq 'unauthorized') -and (-not $hinted)) {
            $hinted = $true
            Write-Host 'Waiting for you to press Allow on the TV...'
        }
        Start-Sleep -Milliseconds ([int]($stepS * 1000))
    }
    return $false
}

# Stop early (before touching anything) if the device isn't reachable.
function Assert-Device {
    if (-not $global:AdbSerial) {
        $r = Select-Device
        if ($r -eq 'many') {
            [Console]::Error.WriteLine('More than one device is connected:')
            [Console]::Error.WriteLine($global:PickList)
            Stop-Script 'Run this again with -Serial ADDRESS for the TV you want.'
        }
    }
    [void](Invoke-Adb @('get-state') -NoLost)
    if ($global:AdbExit -ne 0) {
        $where = ''
        if ($global:AdbSerial) { $where = " ($($global:AdbSerial))" }
        Stop-Script "Can't reach the device$where. Check 'adb devices' / reconnect (Wireless debugging or ADB debugging)."
    }
}

function Test-Pkg {
    param([string]$Name)
    $o = Invoke-Adb @('shell', 'pm', 'list', 'packages', $Name)
    return (($o -split "`n") -ccontains "package:$Name")
}

# Quote a string for the device's shell, so file names with spaces, quotes, $ or backticks survive.
function Shq {
    param([string]$s)
    return "'" + $s.Replace("'", "'\''") + "'"
}

# Lets NemuiTV install its own updates (Android still asks before every install). Never fatal: on failure it says so
# and returns $false, and setup carries on. Same output check as Invoke-Run, since appops can print an error and exit 0.
function Grant-InstallPermission {
    Write-Host 'Letting NemuiTV install its own updates, so you can update from the TV later. Android still asks you before every install.'
    $o = Invoke-Adb @('shell', 'appops', 'set', $Pkg, 'REQUEST_INSTALL_PACKAGES', 'allow')
    if (($global:AdbExit -eq 0) -and ($o -notmatch 'exception|error|not allowed|unknown')) {
        Write-Host 'Allowed: updates from the TV.'
        return $true
    }
    Write-Host "Couldn't allow updates from the TV here; NemuiTV will ask you on the TV the first time you update."
    return $false
}

# Run an adb push quietly, but show adb's own error text if it fails.
function Invoke-AdbPush {
    param([string]$Src, [string]$Dst)
    $out = Invoke-Adb @('push', $Src, $Dst)
    if ($global:AdbExit -ne 0) {
        [Console]::Error.WriteLine("adb push failed for ${Src}:")
        Stop-Script $out
    }
}

# NemuiTV must create its own folders: if adb (the "shell" user) creates them, Android won't let the app read
# them. So start the app, wait for it to make them, and repair a folder that adb created by mistake.
function Initialize-DeviceDirs {
    $d = "$DeviceDir/wallpapers"
    $owner = (Invoke-Adb @('shell', "stat -c %U $(Shq $d) 2>/dev/null")).Trim()
    if ($owner -ceq 'shell') {
        Write-Host "Repairing wallpapers folder (it was created by adb, so NemuiTV couldn't read it)..."
        [void](Invoke-Adb @('shell', "rm -r $(Shq $d)"))
        $owner = ''
    }
    if ($owner -eq '') {
        [void](Invoke-Adb @('shell', 'am', 'start', '-n', "$Pkg/.HomeActivity"))
        for ($i = 0; $i -lt 30; $i++) {
            $owner = (Invoke-Adb @('shell', "stat -c %U $(Shq $d) 2>/dev/null")).Trim()
            if ($owner -ne '') { break }
            Start-Sleep -Milliseconds 500
        }
        if ($owner -eq '') { Stop-Script "NemuiTV didn't create its folders - is it installed? (run scripts\setup.ps1)" }
    }
}

# Return a ':'-separated settings list without the given entries (and without "null"). '' if nothing is left.
function Get-ListWithout {
    param([string]$Current, [string[]]$Remove = @())
    $kept = @()
    foreach ($item in ($Current -split ':')) {
        if (($item -eq '') -or ($item -ceq 'null')) { continue }
        if ($Remove -ccontains $item) { continue }
        $kept += $item
    }
    return ($kept -join ':')
}
