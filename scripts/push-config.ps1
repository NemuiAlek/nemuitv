# Push config.json (HA address/token, wallpaper order, Wi-Fi QR) to a device. Applies next time Home comes back.
# Usage: scripts\push-config.ps1 [-Serial S] [-File PATH]     (default file: config.json in the project folder)
param(
    [Parameter(Position = 0)][string]$Serial = '',
    [string]$File = '',
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'Push config.json (HA address/token, wallpaper order, Wi-Fi QR) to a device. Applies next time Home comes back.'
    Write-Host 'Usage: scripts\push-config.ps1 [-Serial S] [-File PATH]     (default file: config.json in the project folder)'
    exit 0
}
# -File is relative to the folder you ran this from (resolved before _common.ps1 moves us to the project folder).
if ($File -ne '') {
    if (-not [System.IO.Path]::IsPathRooted($File)) { $File = Join-Path (Get-Location).Path $File }
}
. "$PSScriptRoot\_common.ps1"
$Cfg = $File
if ($Cfg -eq '') { $Cfg = 'config.json' }
if (-not (Test-Path -LiteralPath $Cfg -PathType Leaf)) { Stop-Script "$Cfg not found. Copy config.example.json to config.json and fill it in." }
$jsonOk = $true
try {
    $raw = Get-Content -LiteralPath $Cfg -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($raw)) { $jsonOk = $false }
    else { [void](ConvertFrom-Json -InputObject $raw) }
} catch {
    $jsonOk = $false
}
if (-not $jsonOk) { Stop-Script "$Cfg isn't valid JSON - fix it first." }
Assert-Device
Initialize-DeviceDirs
Invoke-AdbPush $Cfg "$DeviceDir/config.json"
Write-Host 'config.json pushed. Press Home (after opening any app) to apply.'
