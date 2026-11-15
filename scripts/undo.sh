#!/usr/bin/env bash
# Take NemuiTV's settings back out of the TV. Removes only NemuiTV's own entries; the Home button goes to Google's
# home again. Asks at the end whether to uninstall the app too.
# Usage: scripts/undo.sh [--serial ADDRESS]
set -euo pipefail
for a in "$@"; do case "$a" in -h|--help) sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac; done
source "$(dirname "$0")/_common.sh" "$@"
require_device
# A failing command under set -e is almost always adb losing the TV mid-run: say so instead of exiting silently.
trap 'printf "\nStopped: lost contact with the TV. Reconnect (Wireless debugging or ADB debugging) and run this again.\n" >&2' ERR
SVC="$PKG/$PKG.takeover.HomeTakeoverService"
SVC_SHORT="$PKG/.takeover.HomeTakeoverService"
LISTENER="$PKG/$PKG.nowplaying.NowPlayingListener"
LISTENER_SHORT="$PKG/.nowplaying.NowPlayingListener"
DREAM="$PKG/.ambient.AmbientDream"
DREAM_FULL="$PKG/$PKG.ambient.AmbientDream"
BACKDROP=com.google.android.apps.tv.dreamx/.service.Backdrop

run() {
  local o; o=$("${ADB[@]}" shell "$@" 2>&1 </dev/null) || { echo "Failed: adb shell $*" >&2; echo "$o" >&2; exit 1; }
  if grep -qiE 'exception|error|not allowed|unknown' <<<"$o"; then echo "Failed: adb shell $*" >&2; echo "$o" >&2; exit 1; fi
}
get() { "${ADB[@]}" shell settings get "$1" "$2" </dev/null | tr -d '\r'; }

# Home button (accessibility service)
cur=$(get secure enabled_accessibility_services)
new=$(list_without "$cur" "$SVC" "$SVC_SHORT")
if [[ -z "$new" ]]; then
  run settings delete secure enabled_accessibility_services
  run settings put secure accessibility_enabled 0
else
  run settings put secure enabled_accessibility_services "$new"
fi
echo "Home button: back to Google's home."

# Now Playing
if has_pkg "$PKG"; then "${ADB[@]}" shell cmd notification disallow_listener "$LISTENER" </dev/null >/dev/null 2>&1 || true; fi
cur=$(get secure enabled_notification_listeners)
new=$(list_without "$cur" "$LISTENER" "$LISTENER_SHORT")
if [[ "$new" != "$(list_without "$cur")" ]]; then
  if [[ -z "$new" ]]; then run settings delete secure enabled_notification_listeners; else run settings put secure enabled_notification_listeners "$new"; fi
fi
echo "Now Playing: off."

# Screensaver (only if it is ours)
sc=$(get secure screensaver_components)
if [[ "$sc" == "$DREAM" || "$sc" == "$DREAM_FULL" ]]; then
  if has_pkg "$PKG"; then
    run pm revoke "$PKG" android.permission.WRITE_SECURE_SETTINGS
    run appops set "$PKG" WRITE_SETTINGS default
  fi
  run settings put secure screensaver_components "$BACKDROP"
  run settings put secure screensaver_default_component "$BACKDROP"
  run settings put system screen_off_timeout 600000
  run settings put secure sleep_timeout 1200000
  echo "Screensaver: Google's Backdrop again (10 min), sleep after 20 min."
else
  echo "Screensaver: wasn't NemuiTV, left alone."
fi

if has_pkg "$PKG"; then
  read -r -p "Also uninstall NemuiTV? (y/N) " ans || ans=""
  case "$ans" in
    y|Y|yes|YES) "${ADB[@]}" uninstall "$PKG" </dev/null >/dev/null && echo "NemuiTV uninstalled." ;;
    *) echo "NemuiTV stays installed (open it from the apps list)." ;;
  esac
fi
