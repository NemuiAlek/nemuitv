# Settings file (config.json)

NemuiTV works with no settings file at all. You only need one for Home Assistant, the Wi-Fi QR code, the Streamer's TV inputs, lofi or Spotify.

## Make and send it

1. In the setup folder, copy the example:

   Mac or Linux:

   ```
   cp config.example.json config.json
   ```

   Windows:

   ```
   copy config.example.json config.json
   ```

2. Edit `config.json` in any text editor. Delete the blocks you don't use, or leave them out.
3. Send it to the TV.

   Mac or Linux:

   ```
   bash scripts/push-config.sh
   ```

   Windows:

   ```
   powershell -ExecutionPolicy Bypass -File scripts\push-config.ps1
   ```

   What it does: checks the JSON, then copies the file to the TV. Options: `--file PATH` (Windows: `-File PATH`) to send a different file, and `--serial ADDRESS` (Windows: `-Serial ADDRESS`) if more than one device is connected.

4. Open any app and press Home. The settings load when Home comes back.

`config.json` holds secrets (your Home Assistant token). Keep it to yourself. Don't post it or upload it anywhere.

## When something is wrong

- Each bad field gets one plain line on the TV's Setup page (hold Home, Launcher settings, Setup), for example `ha.url isn't a URL (needs http:// or https://)`. A bad field falls back to its default. The rest of the file still applies.
- If the file isn't valid JSON, the Setup page says so and NemuiTV keeps the previous settings.
- Unknown fields are ignored.

## The example file

```json
{
  "ha": { "url": "http://homeassistant.local:8123", "token": "PASTE_LONG_LIVED_TOKEN", "weatherEntity": "weather.forecast_home" },
  "wallpaper": { "order": "shuffle" },
  "wifi": { "ssid": "", "password": "", "security": "WPA" },
  "tv": {
    "entity": "media_player.living_room_tv",
    "inputs": [ { "name": "Streamer", "source": "HDMI 1" }, { "name": "Game Console", "source": "HDMI 2" } ],
    "thisDevice": "HDMI 1"
  },
  "lofi": { "spotifyEntity": "media_player.spotify", "source": "Living Room TV", "playlist": "spotify:playlist:YOUR_PLAYLIST_ID", "volume": 0.15 },
  "spotify": { "clientId": "", "refreshToken": "" },
  "hub": { "peopleNames": {}, "carColors": { "my_car": "gray" }, "lowTirePsi": 38, "excludedAreas": [], "hiddenPeople": [], "sceneOrder": [] },
  "alerts": { "text": {} }
}
```

## Fields

Every block is optional.

### `ha` (Home Assistant)

Leave it out to run without Home Assistant. See [home-assistant.md](home-assistant.md).

| Field | Type | Meaning |
|---|---|---|
| `url` | text, required | Your Home Assistant address, like `http://homeassistant.local:8123`. Must start with `http://` or `https://`. Anything after the port (such as `/lovelace/0`) is ignored |
| `token` | text, required | A long-lived access token. Make one for a dedicated Home Assistant user |
| `weatherEntity` | text | The weather entity for the clock. Default `weather.forecast_home` |

If `url` or `token` is missing, the whole `ha` block is ignored and the Setup page tells you which one.

### `wallpaper`

| Field | Meaning |
|---|---|
| `order` | `shuffle` (default) or `sequential`. Anything else shows a line on the Setup page and uses `shuffle` |

### `wifi`

Used for the Wi-Fi QR code in the Control Center, so guests can scan it to join your network.

| Field | Meaning |
|---|---|
| `ssid` | Network name. Leave it empty for no QR code |
| `password` | Network password |
| `security` | `WPA` (default), `WEP`, or `nopass` for an open network |

### `tv` (HA only)

Lets the Control Center switch your TV's HDMI input through Home Assistant. Needs `ha`. Leave it out and the Inputs button is hidden. A `tv` block without an `entity` is ignored.

| Field | Meaning |
|---|---|
| `entity` | The TV's `media_player` entity in Home Assistant |
| `inputs` | A list of `{ "name": "...", "source": "..." }`. `source` must match one of the entity's source names in Home Assistant. An input without a `source` is skipped. `name` defaults to the `source` |
| `thisDevice` | The `source` that is the box NemuiTV runs on. When you press Home while the TV shows another input, NemuiTV switches it back to this one. If left out, NemuiTV uses the input named `Streamer`, and if there is none, it never switches the TV |

### `lofi` (HA only, optional)

When the screensaver starts from Home, NemuiTV can start a playlist (for example lofi music) on the TV's Spotify app through Home Assistant. It only starts when nothing else is playing and the TV is on this box's input. A Lofi switch in Launcher settings turns it on and off. If one of the three required fields is missing, lofi is simply off.

| Field | Meaning |
|---|---|
| `spotifyEntity` | Required. The Spotify `media_player` entity in Home Assistant |
| `source` | Required. The Spotify Connect device name that is this TV (a source of that entity) |
| `playlist` | Required. A Spotify playlist URI like `spotify:playlist:YOUR_PLAYLIST_ID` |
| `volume` | 0 to 1. Default `0.15` |

### `spotify`

The heart button. Filled in by `scripts/spotify-login.py`; see [spotify.md](spotify.md). `clientId` and `refreshToken` are both required, or the heart stays hidden.

### `hub` (HA only)

Tweaks for the Home Assistant hub page.

| Field | Meaning |
|---|---|
| `peopleNames` | Display names for people. Maps a person entity id to a name, like `{ "person.alex": "Alex" }`. Without an entry, the entity's friendly name is shown |
| `carColors` | Maps a car key to a car color: `white`, `gray` or `darkgray`. Default `gray`. A car key is the part of the Tesla sensor names between `sensor.` and `_battery_level`, so `sensor.my_car_battery_level` is `my_car`. The order of the keys here is the order the cars appear in. An unknown color shows a line on the Setup page |
| `lowTirePsi` | A tire reading below this many psi turns orange. Default `38`. Must be more than 0 |
| `excludedAreas` | A list of Home Assistant area names to leave out of the hub, such as `["Guest House"]`. Lights, cameras and locks in those areas are not shown, and a doorbell camera in one is not used |
| `hiddenPeople` | A list of person entity ids to hide from the people strip, such as `["person.guest"]` |
| `sceneOrder` | A list of script entity ids, in the order you want the scenes to appear. Scenes not listed follow, alphabetically |

`excludedAreas`, `hiddenPeople` and `sceneOrder` apply after Home Assistant reconnects or the app restarts. Pushing the file alone may not be enough: restart NemuiTV, or wait for the next reconnect.

### `alerts` (HA only)

| Field | Meaning |
|---|---|
| `text` | Maps an event type to the text of its banner, such as `{ "washing_is_complete": "Laundry is ready" }`. It also works for event types NemuiTV doesn't know. See [home-assistant.md](home-assistant.md) |
