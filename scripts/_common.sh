# Shared bits for NemuiTV device scripts. Source it; don't run it.
# Usage in scripts: source "$(dirname "$0")/_common.sh" "$@"
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Arguments: a bare device serial (old style), or --serial S; --file PATH is for push-config.sh.
SERIAL=""; FILE_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="${2:-}"; shift; [[ $# -gt 0 ]] && shift ;;
    --file) FILE_ARG="${2:-}"; shift; [[ $# -gt 0 ]] && shift ;;
    -*) shift ;;   # flags that belong to the calling script
    *) [[ -z "$SERIAL" ]] && SERIAL="$1"; shift ;;
  esac
done
# platform-tools major version of an adb binary (the "Version 37.0.1-..." line; the first line is the protocol version).
# Prints 0 if it can't be read. `adb pair` needs 30 or newer.
adb_major() {
  local v; v=$("$1" version 2>/dev/null </dev/null | tr -d '\r' | sed -n 's/^Version \([0-9][0-9]*\)\..*/\1/p' | head -n 1 || true)
  printf '%s' "${v:-0}"
}
# adb: the one on PATH if it is new enough, else the copy setup.sh downloaded into scripts/platform-tools/, else whatever is on PATH.
ADB_BIN=adb
if command -v adb >/dev/null 2>&1; then
  if [[ "$(adb_major adb)" -lt 30 && -x scripts/platform-tools/adb ]]; then ADB_BIN="$PWD/scripts/platform-tools/adb"; fi
elif [[ -x scripts/platform-tools/adb ]]; then ADB_BIN="$PWD/scripts/platform-tools/adb"; fi
set_adb() { ADB=("$ADB_BIN"); if [[ -n "$SERIAL" ]]; then ADB=("$ADB_BIN" -s "$SERIAL"); fi; return 0; }
set_adb
PKG=com.nemuigames.nemuitv
DEVICE_DIR=/sdcard/Android/data/$PKG/files

# Choose the device when none was given. adb can list one TV twice after pairing (ip:port and an
# adb-...._adb-tls-connect._tcp entry), so compare serial numbers: the same TV is fine, different TVs are not.
# Sets SERIAL. Returns 0 (one TV, or SERIAL set), 1 (no device), 2 (really different devices; list in PICK_LIST).
pick_device() {
  local devs n s sn first="" same=1 ipentry="" firstdev=""
  PICK_LIST=""
  devs=$("$ADB_BIN" devices 2>/dev/null </dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}' || true)
  n=$(printf '%s' "$devs" | grep -c . || true)
  PICK_LIST="$devs"
  if [[ "$n" -eq 0 ]]; then return 1; fi
  if [[ "$n" -eq 1 ]]; then SERIAL="$devs"; set_adb; return 0; fi
  while IFS= read -r s; do
    [[ -n "$s" ]] || continue
    [[ -n "$firstdev" ]] || firstdev="$s"
    case "$s" in emulator-*) same=0; continue ;; esac
    sn=$("$ADB_BIN" -s "$s" shell getprop ro.serialno 2>/dev/null </dev/null | tr -d '\r' | head -n 1 || true)
    if [[ -z "$sn" ]]; then same=0; continue; fi
    if [[ -z "$first" ]]; then first="$sn"; elif [[ "$sn" != "$first" ]]; then same=0; fi
    if [[ -z "$ipentry" ]] && [[ "$s" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+$ ]]; then ipentry="$s"; fi
  done <<<"$devs"
  if [[ $same -eq 1 ]]; then SERIAL="${ipentry:-$firstdev}"; set_adb; return 0; fi
  return 2
}

# "ADB debugging" connect path (TVs without a "Wireless debugging" screen).
# tv_addr: print host:port from what the user typed (spaces trimmed, :5555 added when there is no port). Empty -> 1.
tv_addr() {
  local a="${1:-}"
  a="${a#"${a%%[![:space:]]*}"}"; a="${a%"${a##*[![:space:]]}"}"
  [[ -n "$a" ]] || return 1
  case "$a" in *:*) ;; *) a="$a:5555" ;; esac
  printf '%s\n' "$a"
}
# adb_debug_connect ADDR: disconnect any stale entry, then connect. 0 when adb says "connected to" (also "already connected to"),
# or "failed to authenticate" (first time: the TV is waiting for the user to accept; wait_authorized handles that).
# adb's output is left in CONNECT_OUT.
adb_debug_connect() {
  "$ADB_BIN" disconnect "$1" >/dev/null 2>&1 </dev/null || true
  CONNECT_OUT=$("$ADB_BIN" connect "$1" 2>&1 </dev/null | tr -d '\r' || true)
  grep -qiE "connected to|failed to authenticate" <<<"$CONNECT_OUT"
}
# wait_authorized SECONDS: poll get-state every 2 s (WAIT_STEP_S overrides the pause, for tests).
# 0 = "device"; 1 = timed out (last state in LAST_STATE). Prints the Allow hint once, the first time it sees "unauthorized".
wait_authorized() {
  local max=$(( ${1:-60} / 2 )) i hinted=0
  [[ $max -ge 1 ]] || max=1
  LAST_STATE=""
  for ((i=0; i<max; i++)); do
    LAST_STATE=$("${ADB[@]}" get-state 2>&1 </dev/null | tr -d '\r' || true)
    case "$LAST_STATE" in
      *[Uu][Nn][Aa][Uu][Tt][Hh][Oo][Rr][Ii][Zz][Ee][Dd]*) LAST_STATE=unauthorized ;;
      *[Oo][Ff][Ff][Ll][Ii][Nn][Ee]*) LAST_STATE=offline ;;
      *) LAST_STATE=$(printf '%s\n' "$LAST_STATE" | head -n 1) ;;
    esac
    [[ "$LAST_STATE" == device ]] && return 0
    if [[ "$LAST_STATE" == unauthorized && $hinted -eq 0 ]]; then
      hinted=1; echo "Waiting for you to press Allow on the TV..."
    fi
    sleep "${WAIT_STEP_S:-2}"
  done
  return 1
}

# takeover_running SECONDS: poll every 0.5 s (WAIT_STEP_S overrides) until Android runs NemuiTV's Home-button service.
# Checks the service by its class name in `dumpsys activity services`, not the label in `dumpsys accessibility`
# (TV makers show different labels: Sony BRAVIA lists it as "Home"). 0 = running, 1 = not within SECONDS.
takeover_running() {
  local max=$(( ${1:-5} * 2 )) i out
  for ((i=0; i<max; i++)); do
    out=$("${ADB[@]}" shell dumpsys activity services "$PKG" 2>/dev/null </dev/null | tr -d '\r' || true)
    # Running = an active "* ServiceRecord{… HomeTakeoverService}" block with its own "app=ProcessRecord" line
    # (old DEAD ConnectionRecords also name the service, and other services have their own app= lines).
    if awk '/^ *\* ServiceRecord\{/ { inb = ($0 ~ /\.takeover\.HomeTakeoverService[ }]/) } /^ *[A-Z][A-Za-z ]*:$/ { inb = 0 }
            inb && /app=ProcessRecord/ { f = 1 } END { exit !f }' <<<"$out"; then return 0; fi
    sleep "${WAIT_STEP_S:-0.5}"
  done
  return 1
}

# Stop early (before touching anything) if the device isn't reachable.
require_device() {
  local rc=0
  if [[ -z "$SERIAL" ]]; then
    pick_device || rc=$?
    if [[ $rc -eq 2 ]]; then
      printf 'More than one device is connected:\n%s\nRun this again with --serial ADDRESS for the TV you want.\n' "$PICK_LIST" >&2; exit 1
    fi
  fi
  "${ADB[@]}" get-state >/dev/null 2>&1 || {
    echo "Can't reach the device${SERIAL:+ ($SERIAL)}. Check 'adb devices' / reconnect (Wireless debugging or ADB debugging)." >&2; exit 1; }
}

has_pkg() { local o; o=$("${ADB[@]}" shell pm list packages "$1" </dev/null | tr -d '\r' || true); grep -qx "package:$1" <<<"$o"; }

# Quote a string for the device's shell, so file names with spaces, quotes, $ or backticks survive.
shq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# Run an adb push quietly, but show adb's own error text if it fails.
adb_push() {
  local out; out=$("${ADB[@]}" push "$1" "$2" 2>&1 </dev/null) || { echo "adb push failed for $1:" >&2; echo "$out" >&2; return 1; }
}

# NemuiTV must create its own folders: if adb (the "shell" user) creates them, Android won't let the app read
# them. So start the app, wait for it to make them, and repair a folder that adb created by mistake.
ensure_device_dirs() {
  local d="$DEVICE_DIR/wallpapers" owner i
  owner=$("${ADB[@]}" shell "stat -c %U $(shq "$d") 2>/dev/null" </dev/null | tr -d '\r' || true)
  if [[ "$owner" == "shell" ]]; then
    echo "Repairing wallpapers folder (it was created by adb, so NemuiTV couldn't read it)…"
    "${ADB[@]}" shell "rm -r $(shq "$d")" </dev/null
    owner=""
  fi
  if [[ -z "$owner" ]]; then
    "${ADB[@]}" shell am start -n "$PKG/.HomeActivity" >/dev/null 2>&1 </dev/null || true
    for i in $(seq 1 30); do
      owner=$("${ADB[@]}" shell "stat -c %U $(shq "$d") 2>/dev/null" </dev/null | tr -d '\r' || true)
      [[ -n "$owner" ]] && break
      sleep 0.5
    done
    [[ -n "$owner" ]] || { echo "NemuiTV didn't create its folders — is it installed? (run scripts/setup.sh)" >&2; exit 1; }
  fi
}

# Print a ':'-separated settings list without the given entries (and without "null"). Prints nothing if nothing is left.
list_without() {
  local cur="$1"; shift
  local out="" item skip e
  while IFS= read -r item; do
    [[ -z "$item" || "$item" == null ]] && continue
    skip=0; for e in "$@"; do [[ "$item" == "$e" ]] && skip=1; done
    [[ $skip -eq 1 ]] || out="${out:+$out:}$item"
  done < <(printf '%s' "$cur" | tr ':' '
')
  printf '%s' "$out"
}
