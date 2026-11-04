#!/usr/bin/env bash
# Sync wallpapers/*.mp4 on this computer to the device: push new/changed files, delete ones you removed here.
# Usage: scripts/push-wallpapers.sh [device-serial]
source "$(dirname "$0")/_common.sh" "$@"
REMOTE="$DEVICE_DIR/wallpapers"
shopt -s nullglob nocaseglob
local_files=(wallpapers/*.mp4)
shopt -u nocaseglob
[[ ${#local_files[@]} -gt 0 ]] || { echo "No .mp4 files in wallpapers/ — nothing to push." >&2; exit 1; }
# Wallpapers must be 1080p H.264 with BT.709 color tags or they stutter on the Streamer: warn, and point at the fixer.
if command -v ffprobe >/dev/null 2>&1; then
  unfit=""
  for f in "${local_files[@]}"; do
    [[ "$(basename "$f")" == ._* ]] && continue
    info=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,color_primaries -of default=nw=1 "$f" 2>/dev/null </dev/null | tr -d '\r' || true)
    codec=$(printf '%s\n' "$info" | sed -n 's/^codec_name=//p'); w=$(printf '%s\n' "$info" | sed -n 's/^width=//p')
    h=$(printf '%s\n' "$info" | sed -n 's/^height=//p'); prim=$(printf '%s\n' "$info" | sed -n 's/^color_primaries=//p')
    [[ "$codec" == h264 && "${w:-0}" -le 1920 && "${h:-0}" -le 1080 && "${w:-0}" -gt 0 && "$prim" == bt709 ]] || unfit="$unfit  $(basename "$f")"$'\n'
  done
  [[ -z "$unfit" ]] || printf 'Warning: these may stutter on the TV (need H.264, at most 1920x1080, BT.709 color tags). Run scripts/fix-wallpapers.sh first:\n%s' "$unfit" >&2
else
  echo "ffprobe not found, skipping the format check." >&2
fi
require_device
ensure_device_dirs

remote_size() { { "${ADB[@]}" shell "stat -c %s $(shq "$REMOTE/$1") 2>/dev/null" </dev/null || true; } | tr -d '\r'; }  # missing file = empty size

keep=""   # newline-separated local names (macOS bash 3.2 has no associative arrays)
for f in "${local_files[@]}"; do
  name=$(basename "$f"); keep="$keep$name"$'\n'
  [[ "$name" == ._* ]] && continue
  lsize=$(wc -c < "$f" | tr -d ' '); rsize=$(remote_size "$name")
  if [[ "$lsize" == "$rsize" ]]; then echo "same     $name"
  else adb_push "$f" "$REMOTE/$name"; echo "pushed   $name"; fi
done
# (adb shell reads stdin, so every adb call inside this loop gets </dev/null or it eats the file list)
while IFS= read -r name; do
  name=${name%$'\r'}; [[ -z "$name" ]] && continue
  if ! grep -Fxq -- "$name" <<<"$keep"; then "${ADB[@]}" shell "rm $(shq "$REMOTE/$name")" </dev/null; echo "deleted  $name"; fi
done < <("${ADB[@]}" shell "ls -1 $(shq "$REMOTE")" </dev/null)
echo "Wallpapers in sync. New ones appear the next time you come back Home."
