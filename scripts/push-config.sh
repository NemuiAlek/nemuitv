#!/usr/bin/env bash
# Push config.json (HA address/token, wallpaper order, Wi-Fi QR) to a device. Applies next time Home comes back.
# Usage: scripts/push-config.sh [device-serial | --serial S] [--file PATH]     (default file: config.json)
source "$(dirname "$0")/_common.sh" "$@"
CFG="${FILE_ARG:-config.json}"
[[ -f "$CFG" ]] || { echo "$CFG not found. Copy config.example.json to config.json and fill it in." >&2; exit 1; }
if command -v python3 >/dev/null 2>&1; then
  python3 -m json.tool "$CFG" >/dev/null 2>&1 || { echo "$CFG isn't valid JSON — fix it first." >&2; exit 1; }
else
  echo "python3 not found, so I can't check the JSON first. Pushing as is (the app checks it again)."
fi
require_device
ensure_device_dirs
adb_push "$CFG" "$DEVICE_DIR/config.json"
echo "config.json pushed. Press Home (after opening any app) to apply."
