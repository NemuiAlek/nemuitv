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
        Stop-Script "Lost contact with the TV. Check 'adb devices' / reconnect wireless debugging."
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
        Stop-Script "Can't reach the device$where. Check 'adb devices' / reconnect wireless debugging."
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
