#!/usr/bin/env bash
# One-time NemuiTV setup from your computer (Mac/Linux): installs the app on a Google TV over Wi-Fi and turns on the
# Home-button takeover and Now Playing. Google's own home screen is never disabled or changed.
# Usage: scripts/setup.sh [--apk PATH] [--serial ADDRESS]      Undo: scripts/undo.sh
set -euo pipefail

# ---- arguments (read before _common.sh moves us to the project folder) ----
APK=""; SERIAL_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) [[ $# -ge 2 ]] || { echo "--apk needs a path." >&2; exit 2; }; APK="$2"; shift 2 ;;
    --serial) [[ $# -ge 2 ]] || { echo "--serial needs a device address." >&2; exit 2; }; SERIAL_ARG="$2"; shift 2 ;;
    -h|--help) sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (use --apk PATH and/or --serial ADDRESS)" >&2; exit 2 ;;
  esac
done
START_DIR="$PWD"
if [[ -n "$APK" ]]; then
  case "$APK" in /*) ;; *) APK="$START_DIR/$APK" ;; esac
fi
source "$(dirname "$0")/_common.sh" ${SERIAL_ARG:+--serial "$SERIAL_ARG"}
SCRIPT_DIR="$PWD/scripts"
TV_SVC="$PKG/$PKG.takeover.HomeTakeoverService"
LISTENER="$PKG/$PKG.nowplaying.NowPlayingListener"
LISTENER_SHORT="$PKG/.nowplaying.NowPlayingListener"
SVC_SHORT="$PKG/.takeover.HomeTakeoverService"

say() { printf '%s\n' "$*"; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }
fail() { trap - ERR; printf '\nStopped: %s\n' "$*" >&2; exit 1; }
# Any other command that fails (set -e) is almost always adb losing the TV mid-run: say so instead of exiting silently.
trap 'printf "\nStopped: lost contact with the TV. Reconnect (Wireless debugging or ADB debugging) and run this again.\n" >&2' ERR

# ---- 1. adb ----
step 1/6 "Looking for adb (the tool that talks to your TV)"
PATH_ADB=""
if command -v adb >/dev/null 2>&1; then
  pv=$(adb_major adb)
  if [[ "$pv" -ge 30 ]]; then PATH_ADB=ok; else
    if [[ "$pv" -eq 0 ]]; then pvs="unknown"; else pvs="$(adb version 2>/dev/null </dev/null | sed -n 's/^Version //p' | head -n 1)"; fi
    say "The adb on this computer is too old for wireless pairing (version $pvs): $(command -v adb)"
  fi
fi
if [[ -x "$SCRIPT_DIR/platform-tools/adb" && "$(adb_major "$SCRIPT_DIR/platform-tools/adb")" -ge 30 ]]; then
  ADB_BIN="$SCRIPT_DIR/platform-tools/adb"; say "Using $ADB_BIN"
elif [[ -n "$PATH_ADB" ]]; then
  ADB_BIN=adb; say "Using adb from your PATH: $(command -v adb)"
else
  case "$(uname -s)" in Darwin) OS=darwin ;; Linux) OS=linux ;; *) fail "unsupported system $(uname -s). Install adb yourself (developer.android.com (search "SDK Platform Tools")) and run this again." ;; esac
  URL="https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
  if [[ -z "$(command -v adb || true)" ]]; then say "adb wasn't found on this computer."; else say "I need a newer adb."; fi
  say "I can download Google's official platform-tools from:"
  say "  $URL"
  say "into $SCRIPT_DIR/platform-tools/ (nothing is installed system-wide)."
  read -r -p "Download it now? (y/N) " ans || ans=""
  case "$ans" in
    y|Y|yes|YES) ;;
    *) fail "adb is needed. Install it from developer.android.com (search "SDK Platform Tools") and run this again." ;;
  esac
  command -v curl >/dev/null 2>&1 || fail "curl is missing, so I can't download. Install platform-tools by hand (link above)."
  command -v unzip >/dev/null 2>&1 || fail "unzip is missing, so I can't unpack the download. Install platform-tools by hand (link above)."
  TMPZIP="$(mktemp -d)"; trap 'rm -rf "$TMPZIP"' EXIT
  say "Downloading…"
  curl -fL --progress-bar -o "$TMPZIP/pt.zip" "$URL" || fail "the download failed. Check your internet connection and run this again."
  rm -rf "$SCRIPT_DIR/platform-tools"
  unzip -q "$TMPZIP/pt.zip" -d "$SCRIPT_DIR" || fail "couldn't unpack the download."
  [[ -x "$SCRIPT_DIR/platform-tools/adb" ]] || fail "the download didn't contain adb."
  ADB_BIN="$SCRIPT_DIR/platform-tools/adb"; say "Downloaded to $SCRIPT_DIR/platform-tools/"
fi
set_adb

# ---- find the APK now so a mistake shows up before we touch the TV ----
if [[ -z "$APK" ]]; then
  # Look in the project folder first (the zip's top folder); only if nothing is there, in its parent.
  found=()
  for tier in 1 2; do
    if [[ $tier -eq 1 ]]; then dirs=("$PWD" "$SCRIPT_DIR"); else dirs=("$(dirname "$PWD")"); fi
    for d in "${dirs[@]}"; do
      for f in "$d"/NemuiTV-*.apk; do
        [[ -f "$f" ]] || continue
        dup=0; for g in ${found[@]+"${found[@]}"}; do [[ "$g" -ef "$f" ]] && dup=1; done
        [[ $dup -eq 1 ]] || found+=("$f")
      done
    done
    [[ ${#found[@]} -gt 0 ]] && break
  done
  if [[ ${#found[@]} -eq 0 ]]; then fail "no NemuiTV-*.apk found next to this folder. Download it from the GitHub release, or pass --apk PATH."; fi
  # Several copies that are byte-for-byte the same are fine: use the first.
  for g in "${found[@]}"; do cmp -s "$g" "${found[0]}" || { printf 'Found more than one APK:\n' >&2; printf '  %s\n' "${found[@]}" >&2; fail "they differ. Pick one with --apk PATH."; }; done
  APK="${found[0]}"
fi
[[ -f "$APK" ]] || fail "APK not found: $APK"

# ---- 2. connect ----
step 2/6 "Connecting to your TV"
if [[ -n "$SERIAL" ]]; then
  say "Using device $SERIAL."
else
  rc=0; pick_device || rc=$?
  if [[ $rc -eq 0 ]]; then
    say "One device is already connected ($SERIAL), skipping pairing."
  elif [[ $rc -eq 2 ]]; then
    printf 'More than one device is connected:\n%s\n' "$PICK_LIST" >&2
    fail "run this again with --serial ADDRESS for the TV you want."
  else
    cat <<'TXT'
No device is connected yet. First, on the TV: Settings > System > About, then press OK on "Android TV OS build" 7 times (Developer options turn on).
TXT
    say 'In Settings > System > Developer options, which one does your TV have?'
    say '  1. "Wireless debugging" (Google TV Streamer, Chromecast with Google TV)'
    say '  2. "ADB debugging" or "Network debugging" (TVs with Google TV built in, e.g. Sony BRAVIA, TCL, Hisense)'
    CHOICE=""
    for attempt in 1 2 3; do
      read -r -p "Type 1 or 2: " CHOICE || CHOICE=""
      case "$CHOICE" in 1|2) break ;; *) CHOICE="" ;; esac
    done
    [[ -n "$CHOICE" ]] || fail "I need a 1 or a 2. Run setup again and pick the option your TV has."
    if [[ "$CHOICE" == 2 ]]; then
      say 'Turn on "ADB debugging". Your TV'"'"'s IP address is in Settings > Network & Internet > (your network).'
      read -r -p "TV IP address: " TV_IP || TV_IP=""
      TV_ADDR=$(tv_addr "$TV_IP") || fail "no TV IP address entered."
      say 'On the TV, choose "Always allow from this computer", then OK.'
      adb_debug_connect "$TV_ADDR" || fail "couldn't connect: $CONNECT_OUT
Try these, then run setup again:
  - Turn \"ADB debugging\" off and on again on the TV.
  - The TV must be on Wi-Fi, not an Ethernet cable, on the same network as this computer.
  - If your TV has \"Wireless debugging\" instead, run setup again and pick 1."
      SERIAL="$TV_ADDR"; set_adb; say "Connected to $SERIAL."
      wait_authorized 60 || fail "the TV didn't allow this computer (state: $LAST_STATE); accept the prompt on the TV, then run setup again."
    else
    say 'Turn on "Wireless debugging", then choose "Pair device with pairing code". The TV shows an address (IP:port) and a 6-digit code.'
    say 'The pairing popup shows an address and a 6-digit code (use these first). After pairing, the Wireless debugging screen'
    say "shows 'IP address & Port' (a different port) - that's the connect address."
    read -r -p "Pairing address (IP:port shown on the TV): " PAIR_ADDR || PAIR_ADDR=""
    [[ -n "$PAIR_ADDR" ]] || fail "no pairing address entered."
    read -r -p "Pairing code (6 digits): " PAIR_CODE || PAIR_CODE=""
    [[ -n "$PAIR_CODE" ]] || fail "no pairing code entered."
    out=$("$ADB_BIN" pair "$PAIR_ADDR" "$PAIR_CODE" 2>&1 </dev/null) || true
    grep -qi "success" <<<"$out" || fail "pairing didn't work: $out
Check the address and code on the TV (the code changes each time) and try again."
    say "Paired."
    say "Now go back one screen on the TV: Wireless debugging shows 'IP address & Port' (a different port than before)."
    read -r -p "Connect address (IP:port): " CONN_ADDR || CONN_ADDR=""
    [[ -n "$CONN_ADDR" ]] || fail "no connect address entered."
    out=$("$ADB_BIN" connect "$CONN_ADDR" 2>&1 </dev/null) || true
    grep -qi "connected to" <<<"$out" || fail "couldn't connect: $out"
    SERIAL="$CONN_ADDR"; set_adb; say "Connected to $SERIAL."
    say "If the TV asks 'Allow USB debugging?', choose Always allow."
    fi
  fi
fi
require_device
state=$("${ADB[@]}" get-state 2>&1 </dev/null | tr -d '\r' || true)
[[ "$state" == device ]] || fail "the TV isn't ready (state: $state). If it says unauthorized, accept the prompt on the TV."

# ---- 3. install ----
step 3/6 "Installing NemuiTV"
say "APK: $APK"
out=$("${ADB[@]}" install -r "$APK" 2>&1 </dev/null) || fail "install failed:
$out"
grep -q "Success" <<<"$out" || fail "install failed:
$out"
has_pkg "$PKG" || fail "installed, but the TV doesn't list $PKG."
say "Installed."

# ---- 4. grants ----
step 4/6 "Giving NemuiTV the permissions it needs"
run() {
  local o; o=$("${ADB[@]}" shell "$@" 2>&1 </dev/null) || fail "adb shell $* failed:
$o"
  if grep -qiE 'exception|error|not allowed|unknown' <<<"$o"; then fail "adb shell $* failed:
$o"; fi
}
run pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
run appops set "$PKG" WRITE_SETTINGS allow
say "Granted: change TV settings (for the Setup page and screensaver timers)."

# ---- 5. Home button + Now Playing (Google's home stays as it is) ----
step 5/6 "Turning on the Home button and Now Playing"
cur=$("${ADB[@]}" shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
if [[ "$cur" == "null" || -z "$cur" ]]; then new="$TV_SVC"
elif [[ ":$cur:" == *":$TV_SVC:"* ]]; then new="$cur"
elif [[ ":$cur:" == *":$SVC_SHORT:"* ]]; then new="$cur"
else new="$cur:$TV_SVC"; fi   # keep any other accessibility services that are already on
run settings put secure enabled_accessibility_services "$new"
run settings put secure accessibility_enabled 1
takeover_running 10 || fail "the Home-button setting was written, but the service isn't running yet.
Try again in a moment, or look at: adb logcat -d -s NemuiTV"
say "Home button: NemuiTV opens when you press Home on the remote. Google's home screen stays installed."

"${ADB[@]}" shell cmd notification allow_listener "$LISTENER" </dev/null >/dev/null 2>&1 || true
ENABLED=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  ENABLED=$("${ADB[@]}" shell settings get secure enabled_notification_listeners </dev/null | tr -d '\r')
  [[ ":$ENABLED:" == *":$LISTENER:"* || ":$ENABLED:" == *":$LISTENER_SHORT:"* ]] && break
  sleep 0.5
done
if [[ ":$ENABLED:" == *":$LISTENER:"* || ":$ENABLED:" == *":$LISTENER_SHORT:"* ]]; then
  say "Now Playing: NemuiTV can see what's playing."
else
  fail "Now Playing wasn't accepted by Android (enabled_notification_listeners = $ENABLED)."
fi

# ---- 6. done ----
step 6/6 "Done"
say "  - adb: ready"
say "  - Connected: $SERIAL"
say "  - Installed: NemuiTV"
say "  - Permissions: granted"
say "  - Home button: on"
say "  - Now Playing: on"
say ""
say "Continue on the TV (the Setup checklist opens). You can turn Wireless debugging / ADB debugging off now."
"${ADB[@]}" shell am start -n "$PKG/.HomeActivity" </dev/null >/dev/null 2>&1 || true
