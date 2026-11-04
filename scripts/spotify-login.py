#!/usr/bin/env python3
"""One-time Spotify sign-in for NemuiTV's heart button (like / unlike the playing song).

Run: python3 scripts/spotify-login.py   (Windows: py scripts\\spotify-login.py)
Paste your Spotify app's Client ID, then sign in in the browser that opens. PKCE sign-in: no client secret.
Your Spotify app (developer.spotify.com > Dashboard > your app > Settings) must list this Redirect URI:
  http://127.0.0.1:8888/callback
Saves the Client ID + a refresh token into config.json ("spotify"); then run push-config.
"""
import base64, hashlib, http.server, json, os, secrets, sys, tempfile, urllib.error, urllib.parse, urllib.request, webbrowser

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cfg_path = os.path.join(root, "config.json")
# Read config.json first, so a broken file stops us before the browser sign-in. No file yet: start empty and add
# only "spotify" (copying config.example.json would bring its placeholder Home Assistant block along).
cfg = {}
if os.path.exists(cfg_path):
    try:
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        sys.exit(f"config.json isn't valid JSON ({e}). Fix it (or move it away) and run this again — nothing changed.")
    if not isinstance(cfg, dict):
        sys.exit("config.json must be a JSON object ({ ... }). Fix it and run this again — nothing changed.")
try:
    client_id = input("Paste your Spotify app's Client ID, then press Enter: ").strip()
except (EOFError, KeyboardInterrupt):
    sys.exit("\nNo Client ID entered — nothing changed.")
if not client_id:
    sys.exit("No Client ID entered — nothing changed.")
redirect = "http://127.0.0.1:8888/callback"
scopes = "user-library-read user-library-modify user-read-currently-playing user-read-playback-state"
verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
state = secrets.token_urlsafe(16)
auth_url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
    "client_id": client_id, "response_type": "code", "redirect_uri": redirect, "scope": scopes,
    "code_challenge_method": "S256", "code_challenge": challenge, "state": state,
})

result = {}
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if not self.path.startswith("/callback"):
            self.send_response(404); self.end_headers(); return
        ok = q.get("state", [""])[0] == state and "code" in q
        result["code"] = q["code"][0] if ok else None
        result["error"] = q.get("error", [None])[0]
        self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.end_headers()
        msg = "Signed in — you can close this tab and go back to Terminal." if ok else "Sign-in failed — see Terminal."
        self.wfile.write(f"<h2 style='font-family:system-ui'>{msg}</h2>".encode())
    def log_message(self, *a): pass

srv = http.server.HTTPServer(("127.0.0.1", 8888), Handler)
print("Opening Spotify in your browser… (if nothing opens, paste this link into it:)\n" + auth_url)
webbrowser.open(auth_url)
while "code" not in result:
    srv.handle_request()
if not result["code"]:
    sys.exit(f"Sign-in failed ({result.get('error') or 'state mismatch'}). Check the Redirect URI on your Spotify app.")

def post(url, data):
    req = urllib.request.Request(url, urllib.parse.urlencode(data).encode(),
                                 {"Content-Type": "application/x-www-form-urlencoded"})
    return json.load(urllib.request.urlopen(req, timeout=10))
try:
    tok = post("https://accounts.spotify.com/api/token", {
        "grant_type": "authorization_code", "code": result["code"], "redirect_uri": redirect,
        "client_id": client_id, "code_verifier": verifier,
    })
except urllib.error.HTTPError as e:
    sys.exit(f"Spotify refused the sign-in (HTTP {e.code}): {e.read().decode()[:200]}")

if not tok.get("refresh_token"):
    sys.exit("Spotify signed you in but sent no refresh token, so nothing was saved. Run this again.")
cfg["spotify"] = {"clientId": client_id, "refreshToken": tok["refresh_token"]}
# Write a private temp file next to config.json, then swap it in: a crash never leaves a half-written config.
fd, tmp = tempfile.mkstemp(dir=root, prefix=".config.", suffix=".tmp")
try:
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, cfg_path)
except BaseException:
    try: os.remove(tmp)
    except OSError: pass
    raise
print("✓ Signed in. Client ID + refresh token saved in config.json (git-ignored).")

# Read-only check: what's playing, and is it already liked?
def get(path):
    req = urllib.request.Request("https://api.spotify.com/v1" + path, headers={"Authorization": "Bearer " + tok["access_token"]})
    with urllib.request.urlopen(req, timeout=10) as r:
        body = r.read()
        return r.status, (json.loads(body) if body else None)
try:
    status, now = get("/me/player/currently-playing")
    item = (now or {}).get("item")
    if status == 204 or not item:
        print("Nothing is playing on Spotify right now, so I couldn't test the ♥ check (sign-in is still saved).")
    else:
        uri = item["uri"]
        _, liked = get("/me/library/contains?" + urllib.parse.urlencode({"uris": uri}))
        artists = ", ".join(a["name"] for a in item.get("artists", []))
        print(f"Now playing: {item['name']} — {artists} · liked: {'yes ♥' if liked and liked[0] else 'no ♡'}")
except urllib.error.HTTPError as e:
    print(f"Signed in, but the test call failed (HTTP {e.code}): {e.read().decode()[:200]}")
print("Next: run push-config (scripts/push-config.sh or scripts/push-config.ps1); NemuiTV reads it the next time Home shows.")
