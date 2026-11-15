# Convert the videos in wallpapers\ into what the TV plays smoothly: H.264, at most 1080p, 30 fps, about 10 Mbps, BT.709 color tags,
# no audio, as .mp4. Originals move to wallpapers\originals\. Needs ffmpeg (never installed for you).
# Usage: scripts\fix-wallpapers.ps1
param(
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'Convert the videos in wallpapers\ into what the TV plays smoothly: H.264, at most 1080p, 30 fps, about 10 Mbps, BT.709 color tags,'
    Write-Host 'no audio, as .mp4. Originals move to wallpapers\originals\. Needs ffmpeg (never installed for you).'
    Write-Host 'Usage: scripts\fix-wallpapers.ps1'
    exit 0
}
$Serial = ''
. "$PSScriptRoot\_common.ps1"
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Host 'ffmpeg not found. Install it from https://ffmpeg.org/download.html (Windows: winget install ffmpeg) and run this again.'
    exit 1
}
$exts = @('.mp4', '.mov', '.m4v', '.webm', '.mkv')
$files = @(Get-ChildItem -LiteralPath 'wallpapers' -File -ErrorAction SilentlyContinue | Where-Object { $exts -contains $_.Extension.ToLower() } | Sort-Object Name)
if ($files.Count -eq 0) { Stop-Script 'No videos in wallpapers/ - nothing to convert.' }
[void](New-Item -ItemType Directory -Path 'wallpapers\originals' -Force)
foreach ($f in $files) {
    $base = $f.Name
    if ($base.StartsWith('._')) { continue }
    $name = $f.BaseName
    $out = "wallpapers\$name.mp4"
    $tmp = "wallpapers\.converting-$name.mp4"
    $orig = "wallpapers\originals\$base"
    if (Test-Path -LiteralPath $orig) {
        [Console]::Error.WriteLine("skipped  $base (wallpapers/originals/ already has a file with that name)")
        continue
    }
    if ((Test-Path -LiteralPath $out) -and ((Resolve-Path -LiteralPath $out).Path -ine $f.FullName)) {
        [Console]::Error.WriteLine("skipped  $base (wallpapers/$name.mp4 already exists)")
        continue
    }
    Write-Host "converting $base ..."
    $ffOut = Invoke-Native 'ffmpeg' @('-v', 'error', '-nostdin', '-y', '-i', $f.FullName, '-c:v', 'libx264', '-preset', 'slow', '-crf', '20',
        '-maxrate', '10M', '-bufsize', '20M',
        '-vf', "scale='min(1920,iw)':-2,fps=30,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709", '-pix_fmt', 'yuv420p',
        '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', '-an', '-movflags', '+faststart', '-f', 'mp4', $tmp)
    if ($global:NativeExit -eq 0) {
        Move-Item -LiteralPath $f.FullName -Destination $orig
        Move-Item -LiteralPath $tmp -Destination $out
        Write-Host "done     $name.mp4 (original moved to wallpapers/originals/)"
    } else {
        if ($ffOut) { [Console]::Error.WriteLine($ffOut) }
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        [Console]::Error.WriteLine("FAILED   $base - left as it was.")
    }
}
Write-Host 'Next: scripts\push-wallpapers.ps1'
