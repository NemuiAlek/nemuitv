# Take NemuiTV's settings back out of the TV. Removes only NemuiTV's own entries; the Home button goes to Google's
# home again. Asks at the end whether to uninstall the app too.
# Usage: scripts\undo.ps1 [-Serial ADDRESS]
param(
    [Parameter(Position = 0)][string]$Serial = '',
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host "Take NemuiTV's settings back out of the TV. Removes only NemuiTV's own entries; the Home button goes to Google's"
    Write-Host 'home again. Asks at the end whether to uninstall the app too.'
    Write-Host 'Usage: scripts\undo.ps1 [-Serial ADDRESS]'
    exit 0
}
. "$PSScriptRoot\_common.ps1"
Assert-Device
$Svc = "$Pkg/$Pkg.takeover.HomeTakeoverService"
$SvcShort = "$Pkg/.takeover.HomeTakeoverService"
$Listener = "$Pkg/$Pkg.nowplaying.NowPlayingListener"
$ListenerShort = "$Pkg/.nowplaying.NowPlayingListener"
$Dream = "$Pkg/.ambient.AmbientDream"
$DreamFull = "$Pkg/$Pkg.ambient.AmbientDream"
$Backdrop = 'com.google.android.apps.tv.dreamx/.service.Backdrop'

function Invoke-Run {
    param([string[]]$ShellArgs)
    $o = Invoke-Adb (@('shell') + $ShellArgs)
    $text = $ShellArgs -join ' '
    if (($global:AdbExit -ne 0) -or ($o -match 'exception|error|not allowed|unknown')) {
        [Console]::Error.WriteLine("Failed: adb shell $text")
        Stop-Script $o
    }
}
function Get-Setting {
    param([string]$Namespace, [string]$Key)
    return (Invoke-Adb @('shell', 'settings', 'get', $Namespace, $Key)).Trim()
}

# Home button (accessibility service)
$cur = Get-Setting 'secure' 'enabled_accessibility_services'
$new = Get-ListWithout $cur @($Svc, $SvcShort)
if ($new -eq '') {
    Invoke-Run @('settings', 'delete', 'secure', 'enabled_accessibility_services')
    Invoke-Run @('settings', 'put', 'secure', 'accessibility_enabled', '0')
} else {
    Invoke-Run @('settings', 'put', 'secure', 'enabled_accessibility_services', $new)
}
Write-Host "Home button: back to Google's home."

# Now Playing
if (Test-Pkg $Pkg) { [void](Invoke-Adb @('shell', 'cmd', 'notification', 'disallow_listener', $Listener)) }
$cur = Get-Setting 'secure' 'enabled_notification_listeners'
$new = Get-ListWithout $cur @($Listener, $ListenerShort)
if ($new -cne (Get-ListWithout $cur @())) {
    if ($new -eq '') { Invoke-Run @('settings', 'delete', 'secure', 'enabled_notification_listeners') }
    else { Invoke-Run @('settings', 'put', 'secure', 'enabled_notification_listeners', $new) }
}
Write-Host 'Now Playing: off.'

# Screensaver (only if it is ours)
$sc = Get-Setting 'secure' 'screensaver_components'
if (($sc -ceq $Dream) -or ($sc -ceq $DreamFull)) {
    if (Test-Pkg $Pkg) {
        Invoke-Run @('pm', 'revoke', $Pkg, 'android.permission.WRITE_SECURE_SETTINGS')
        Invoke-Run @('appops', 'set', $Pkg, 'WRITE_SETTINGS', 'default')
    }
    Invoke-Run @('settings', 'put', 'secure', 'screensaver_components', $Backdrop)
    Invoke-Run @('settings', 'put', 'secure', 'screensaver_default_component', $Backdrop)
    Invoke-Run @('settings', 'put', 'system', 'screen_off_timeout', '600000')
    Invoke-Run @('settings', 'put', 'secure', 'sleep_timeout', '1200000')
    Write-Host "Screensaver: Google's Backdrop again (10 min), sleep after 20 min."
} else {
    Write-Host "Screensaver: wasn't NemuiTV, left alone."
}

# Updates from the TV (setup allowed NemuiTV to install them). Never fatal, like setup's step.
if (Test-Pkg $Pkg) { [void](Invoke-Adb @('shell', 'appops', 'set', $Pkg, 'REQUEST_INSTALL_PACKAGES', 'default')) }

if (Test-Pkg $Pkg) {
    $ans = ''
    try { $ans = Read-Host 'Also uninstall NemuiTV? (y/N)' } catch { $ans = '' }
    if ($ans -match '^(y|yes)$') {
        [void](Invoke-Adb @('uninstall', $Pkg))
        if ($global:AdbExit -eq 0) { Write-Host 'NemuiTV uninstalled.' } else { [Console]::Error.WriteLine("Couldn't uninstall NemuiTV.") }
    } else {
        Write-Host 'NemuiTV stays installed (open it from the apps list).'
    }
}
