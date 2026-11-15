# Manual setup (adb commands)

This is what `scripts/setup.sh` and `scripts/undo.sh` do, as commands you can paste yourself. Use it if a script fails, or if you want to see exactly what changes on your TV.

NemuiTV never disables Google's home screen. It takes over the remote's Home button with an accessibility service (tap = NemuiTV home, hold = Control Center). Google's home stays installed. Undo gives the Home button back to it.

Package name used below: `com.nemuigames.nemuitv`.

## 1. Get adb

`adb` is Google's tool for talking to Android devices. Download "SDK Platform Tools" for your system from developer.android.com (search for "SDK Platform Tools"), unzip it, and either add the folder to your PATH or run `./platform-tools/adb` instead of `adb` below. Nothing needs to be installed system-wide.

Check it works:

```
adb version
```

## 2. Connect to the TV

Which option does Developer options have? "Wireless debugging" (Google TV Streamer, Chromecast with Google TV): follow the steps below. "ADB debugging" or "Network debugging" (TVs with Google TV built in): skip to "If your TV has ADB debugging instead".

1. On the TV: Settings, System, About. Press OK on "Android TV OS build" 7 times. Developer options are now on.
2. Settings, System, Developer options: turn on "Wireless debugging".
3. Choose "Pair device with pairing code". The TV shows an address (like `192.0.2.10:37123`) and a 6-digit code.

Pair (this asks for the code):

```
adb pair <pairing-address>
```

Go back one screen on the TV. "IP address & Port" shows a different port. Connect to that one:

```
adb connect <ip-address:port>
```

If the TV asks "Allow USB debugging?", choose Always allow. Check:

```
adb devices
```

You should see your TV with the state `device`. If you have more than one device, add `-s <ip-address:port>` right after `adb` in every command below.

### If your TV has ADB debugging instead of Wireless debugging

1. Do step 1 above, then in Developer options turn on "ADB debugging" (some TVs call it "Network debugging").
2. Find the TV's IP address: Settings, Network & Internet, then your network.
3. Connect to port 5555:

```
adb connect <TV IP>:5555
```

4. On the TV, accept the prompt and choose "Always allow from this computer". Run `adb devices` to check, then continue with step 3 below.

If it won't connect: turn "ADB debugging" off and on, make sure the TV is on Wi-Fi (not Ethernet) on the same network as your computer, and if your TV has "Wireless debugging", use that instead.

## 3. Install

Installs (or updates) the app. `-r` keeps its data when updating:

```
adb install -r NemuiTV-1.0.1.apk
```

## 4. Grant permissions

Lets NemuiTV change a few TV settings (the Setup page switches and the screensaver timers):

```
adb shell pm grant com.nemuigames.nemuitv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.nemuigames.nemuitv WRITE_SETTINGS allow
```

## 5. Turn on the Home button

First read what is already enabled, so you keep other accessibility services:

```
adb shell settings get secure enabled_accessibility_services
```

- If it prints `null`, run:

  ```
  adb shell settings put secure enabled_accessibility_services com.nemuigames.nemuitv/com.nemuigames.nemuitv.takeover.HomeTakeoverService
  ```

- If it prints something else, add ours after a colon. Example, if it printed `com.example.reader/com.example.reader.Service`:

  ```
  adb shell settings put secure enabled_accessibility_services com.example.reader/com.example.reader.Service:com.nemuigames.nemuitv/com.nemuigames.nemuitv.takeover.HomeTakeoverService
  ```

Then switch accessibility on:

```
adb shell settings put secure accessibility_enabled 1
```

Check that the service is running. The output should mention "NemuiTV Home button":

```
adb shell dumpsys accessibility | grep "Bound services"
```

On Windows PowerShell, use `adb shell dumpsys accessibility | Select-String "Bound services"`.

Press Home on the remote. NemuiTV should open.

## 6. Turn on Now Playing

Lets NemuiTV read what your music app is playing:

```
adb shell cmd notification allow_listener com.nemuigames.nemuitv/com.nemuigames.nemuitv.nowplaying.NowPlayingListener
```

Check that it is listed:

```
adb shell settings get secure enabled_notification_listeners
```

## 7. Open the app

```
adb shell am start -n com.nemuigames.nemuitv/.HomeActivity
```

You can turn Wireless debugging or ADB debugging off now. The Setup page on the TV (hold Home, Launcher settings, Setup) shows what is on. Its Home button and Screensaver rows are switches you can change there. The Now Playing row only shows its status, because Android doesn't let an app turn its own listener on. Step 6 above is what turns it on.

## Undo by hand

Read each list first, then write it back without NemuiTV's entry.

Home button. Read the list:

```
adb shell settings get secure enabled_accessibility_services
```

If NemuiTV was the only entry, remove the setting and turn accessibility off:

```
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
```

If other services are listed, put the list back without ours (entries are separated by colons).

Now Playing:

```
adb shell cmd notification disallow_listener com.nemuigames.nemuitv/com.nemuigames.nemuitv.nowplaying.NowPlayingListener
```

Screensaver. Only if NemuiTV is your screensaver (`adb shell settings get secure screensaver_components` prints a name that ends in `.ambient.AmbientDream`). This revokes the permissions and puts Google's Backdrop screensaver and the stock timers back:

```
adb shell pm revoke com.nemuigames.nemuitv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.nemuigames.nemuitv WRITE_SETTINGS default
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
adb shell settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop
adb shell settings put system screen_off_timeout 600000
adb shell settings put secure sleep_timeout 1200000
```

Uninstall (optional):

```
adb uninstall com.nemuigames.nemuitv
```

## Send your settings and wallpapers

The scripts do this for you (see [configuration.md](configuration.md) and [wallpapers.md](wallpapers.md)). By hand, the files go in the app's own folder on the TV.

Do not create that folder with `adb shell mkdir`. A folder made by adb belongs to the wrong user, and NemuiTV can't read it. Open the app once instead (step 7); it creates the folders itself. Then push:

```
adb push config.json /sdcard/Android/data/com.nemuigames.nemuitv/files/config.json
adb push my-video.mp4 /sdcard/Android/data/com.nemuigames.nemuitv/files/wallpapers/
```
