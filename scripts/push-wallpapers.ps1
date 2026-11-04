# Sync wallpapers\*.mp4 on this computer to the device: push new/changed files, delete ones you removed here.
# Usage: scripts\push-wallpapers.ps1 [-Serial ADDRESS]
param(
    [Parameter(Position = 0)][string]$Serial = '',
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'Sync wallpapers\*.mp4 on this computer to the device: push new/changed files, delete ones you removed here.'
    Write-Host 'Usage: scripts\push-wallpapers.ps1 [-Serial ADDRESS]'
    exit 0
}
. "$PSScriptRoot\_common.ps1"
$Remote = "$DeviceDir/wallpapers"
# (Filter on the extension ourselves: Windows' -Filter *.mp4 would also match names like .mp4x)
$localFiles = @(Get-ChildItem -LiteralPath 'wallpapers' -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -ieq '.mp4' } | Sort-Object Name)
if ($localFiles.Count -eq 0) { Stop-Script 'No .mp4 files in wallpapers/ - nothing to push.' }
# Wallpapers must be 1080p H.264 with BT.709 color tags or they stutter on the Streamer: warn, and point at the fixer.
if (Get-Command ffprobe -ErrorAction SilentlyContinue) {
    $unfit = @()
    foreach ($f in $localFiles) {
        if ($f.Name.StartsWith('._')) { continue }
        $info = Invoke-Native 'ffprobe' @('-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=codec_name,width,height,color_primaries', '-of', 'default=nw=1', $f.FullName)
        $codec = ''; $prim = ''; $w = 0; $h = 0
        foreach ($line in ($info -split "`n")) {
            if ($line.StartsWith('codec_name=')) { $codec = $line.Substring(11).Trim() }
            elseif ($line.StartsWith('width=')) { [void][int]::TryParse($line.Substring(6).Trim(), [ref]$w) }
            elseif ($line.StartsWith('height=')) { [void][int]::TryParse($line.Substring(7).Trim(), [ref]$h) }
            elseif ($line.StartsWith('color_primaries=')) { $prim = $line.Substring(16).Trim() }
        }
        if (-not (($codec -ceq 'h264') -and ($w -gt 0) -and ($w -le 1920) -and ($h -le 1080) -and ($prim -ceq 'bt709'))) { $unfit += $f.Name }
    }
    if ($unfit.Count -gt 0) {
        [Console]::Error.WriteLine('Warning: these may stutter on the TV (need H.264, at most 1920x1080, BT.709 color tags). Run scripts\fix-wallpapers.ps1 first:')
        foreach ($n in $unfit) { [Console]::Error.WriteLine("  $n") }
    }
} else {
    [Console]::Error.WriteLine('ffprobe not found, skipping the format check.')
}
Assert-Device
Initialize-DeviceDirs

function Get-RemoteSize {
    param([string]$Name)
    return (Invoke-Adb @('shell', "stat -c %s $(Shq "$Remote/$Name") 2>/dev/null")).Trim()   # missing file = empty size
}

$keep = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($f in $localFiles) {
    $name = $f.Name
    [void]$keep.Add($name)
    if ($name.StartsWith('._')) { continue }
    $lsize = [string]$f.Length
    $rsize = Get-RemoteSize $name
    if ($lsize -ceq $rsize) {
        Write-Host "same     $name"
    } else {
        Invoke-AdbPush $f.FullName "$Remote/$name"
        Write-Host "pushed   $name"
    }
}
$listing = Invoke-Adb @('shell', "ls -1 $(Shq $Remote)")
if ($global:AdbExit -ne 0) { Stop-Script "Couldn't list the wallpapers folder on the TV." }
foreach ($name in ($listing -split "`n")) {
    $name = $name.TrimEnd("`r")
    if ($name -eq '') { continue }
    if (-not $keep.Contains($name)) {
        [void](Invoke-Adb @('shell', "rm $(Shq "$Remote/$name")"))
        Write-Host "deleted  $name"
    }
}
Write-Host 'Wallpapers in sync. New ones appear the next time you come back Home.'
