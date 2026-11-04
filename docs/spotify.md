# Spotify heart button (optional)

While Spotify plays, the Control Center's Now Playing banner can show a heart. Press it to like or unlike the song in your Spotify library. The heart stays hidden until you set this up, and it hides again if anything goes wrong.

You need:

- A Spotify account. In Spotify's "development mode", an app works for up to 5 users, and the app's owner needs Premium.
- Python 3 on your computer.
- The setup folder from the [README](../README.md) (it has `scripts/spotify-login.py` and `config.example.json`).

Windows: install Python from https://www.python.org/downloads/ and tick "Add python.exe to PATH" in the installer.

## Steps

1. Open https://developer.spotify.com/dashboard and sign in.
2. Create an app. Any name works. Under "Redirect URIs", add exactly this and save:

   ```
   http://127.0.0.1:8888/callback
   ```

3. Open the app's Settings and copy the **Client ID**. You do not need the client secret; the sign-in uses PKCE.
4. In the setup folder, run the login script.

   Mac or Linux:

   ```
   python3 scripts/spotify-login.py
   ```

   Windows:

   ```
   py scripts\spotify-login.py
   ```

   What it does: asks for your Client ID, opens a browser for you to sign in and allow access, then saves the Client ID and a refresh token into `config.json` under `spotify`. (It creates `config.json` from `config.example.json` if you don't have one yet.) The scopes it asks for are: read and modify your library, and read what is playing.

5. Send the file to the TV:

   Mac or Linux:

   ```
   bash scripts/push-config.sh
   ```

   Windows:

   ```
   powershell -ExecutionPolicy Bypass -File scripts\push-config.ps1
   ```

6. Press Home after opening any app. The settings load when Home comes back.

## Check it

Play a song in the Spotify app on the TV, hold Home, and look at the Now Playing banner. The heart shows filled if the song is already liked.

## Notes

- The refresh token in `config.json` is a secret. Don't share the file or post it.
- To remove the feature, delete the `spotify` block from `config.json` and push it again.
- The `spotify` block is different from `lofi`. `lofi` is a Home Assistant feature (see [home-assistant.md](home-assistant.md)).
