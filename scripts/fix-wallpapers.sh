#!/usr/bin/env bash
# Convert the videos in wallpapers/ into what the TV plays smoothly: H.264, at most 1080p, 30 fps, about 10 Mbps, BT.709 color tags,
# no audio, as .mp4. Originals move to wallpapers/originals/. Needs ffmpeg (never installed for you).
# Usage: scripts/fix-wallpapers.sh
set -euo pipefail
cd "$(dirname "$0")/.."
command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg not found. Install it from https://ffmpeg.org/download.html (Mac: brew install ffmpeg) and run this again."; exit 1; }
shopt -s nullglob nocaseglob
files=(wallpapers/*.mp4 wallpapers/*.mov wallpapers/*.m4v wallpapers/*.webm wallpapers/*.mkv)
shopt -u nocaseglob
[[ ${#files[@]} -gt 0 ]] || { echo "No videos in wallpapers/ — nothing to convert." >&2; exit 1; }
mkdir -p wallpapers/originals
for f in "${files[@]}"; do
  base=$(basename "$f"); [[ "$base" == ._* ]] && continue
  name="${base%.*}"
  out="wallpapers/$name.mp4"; tmp="wallpapers/.converting-$name.mp4"
  orig="wallpapers/originals/$base"
  if [[ -e "$orig" ]]; then echo "skipped  $base (wallpapers/originals/ already has a file with that name)" >&2; continue; fi
  echo "converting $base …"
  if ffmpeg -v error -nostdin -y -i "$f" -c:v libx264 -preset slow -crf 20 -maxrate 10M -bufsize 20M \
      -vf "scale='min(1920,iw)':-2,fps=30,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709" -pix_fmt yuv420p \
      -colorspace bt709 -color_primaries bt709 -color_trc bt709 -an -movflags +faststart -f mp4 "$tmp"; then
    mv "$f" "$orig"
    mv "$tmp" "$out"
    echo "done     $name.mp4 (original moved to wallpapers/originals/)"
  else
    rm -f "$tmp"; echo "FAILED   $base — left as it was." >&2
  fi
done
echo "Next: scripts/push-wallpapers.sh"
