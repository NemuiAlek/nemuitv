# Home Assistant (optional)

NemuiTV works without Home Assistant. With it, you also get:

- Rooms, lights, switches and fans, plus scenes.
- A lock status, indoor temperature and a thermostat.
- Cameras, a doorbell pop-up, and appliance banners such as "Washer done".
- A people strip and, if you have a Tesla, a car page with battery, range and tire pressure.
- The weather next to the clock.
- Optional: TV input switching and a lofi playlist on the screensaver.

Without Home Assistant, the ⌂ tile opens a page that says "No Home Assistant set up" and points here. Nothing else shows an error.

This page is "how we use it". Everything marked **required** is the minimum for the hub to work. Everything marked **optional** you can skip.

Using an AI agent to do this for you? Give it [ai-setup.md](ai-setup.md).

## 1. A user and token (required)

1. In Home Assistant, go to Settings, People, Users, and add a user named `NemuiTV`. Give it its own password.
2. Leave that user as a regular, non-administrator user. That is safer, and it is all NemuiTV needs: a non-admin user can read the area, device, entity and label lists that NemuiTV uses to build its rooms. Administrator rights are not needed.
3. Log in as that user, open its profile (bottom left), then Security, then "Long-lived access tokens", and create a token named `NemuiTV`.
4. Copy the token right away. Home Assistant shows it once.
5. Put the address and token in `config.json` (see [configuration.md](configuration.md)):

   ```json
   "ha": { "url": "http://homeassistant.local:8123", "token": "PASTE_LONG_LIVED_TOKEN" }
   ```

6. Send it with `push-config` and press Home. The Setup page on the TV should say "Home Assistant: Connected".

⌂ moves into your favorites row the first time Home Assistant connects; you can move it anywhere (hold OK).

The token is a password. Keep `config.json` private. If it leaks, delete the token in Home Assistant and make a new one.

If the Setup page says "Not connected" or an auth error, check the address (it must start with `http://` or `https://`), that the TV can reach it on your network, and that the token was copied fully.

## 2. Weather (optional)

The clock shows the weather from one `weather.*` entity. The default is `weather.forecast_home`, which Home Assistant's built-in Met.no integration creates. To use another one, set `ha.weatherEntity` in `config.json`. If the entity doesn't exist, the Setup page says "Weather entity not found".

## 3. Rooms and areas (required for the hub)

The hub builds its rooms from Home Assistant **areas**. For each area, NemuiTV lists:

- `light`, `switch` and `fan` entities that are in that area. An entity counts as being in an area if the entity itself, or its device, is assigned to it.
- Entities that are hidden or disabled in Home Assistant, or that have an entity category (config or diagnostic entities), are left out.
- Anything without an area is not shown.

Also read per area: `climate` (the thermostat), `lock`, and `camera` entities. If you have more than one `climate` entity, the first by name is used.

To keep an area out of the hub (for example a guest house), list its name in `hub.excludedAreas`.

To set this up: Settings, Areas, and make sure your lights and switches are in the right areas.

## 4. Labels (optional)

Home Assistant labels (Settings, Areas, Labels) mark things for NemuiTV. Create them with exactly these names:

| Label | Put it on | What it does |
|---|---|---|
| `nemuitv` | `script.*` entities | Those scripts become the scenes row. Run by tapping. Order them with `hub.sceneOrder` |
| `keep_on` | `light.*`, `switch.*`, `fan.*` entities | The room's "all off" button and the "lights on" count skip these. Use it for things that must stay on, such as a network switch or a fish tank |
| `nemuitv_alert` | `event.*` entities | NemuiTV watches these for alerts. See section 6 |

A scene can show an on/off state: if an `input_boolean` exists with the same name as the script (`script.movie_night` and `input_boolean.movie_night`), the scene is shown as active while the boolean is on. Optional.

## 5. People, locks and cycles (optional)

- **People:** every usable `person.*` entity is listed, and "home" means the person's state is `home`. Rename with `hub.peopleNames`, hide with `hub.hiddenPeople`.
- **Locks:** `lock.*` entities in an area. The strip shows "Locked", or which locks are unlocked or jammed. A battery sensor on the same device as a lock shows a low-battery warning when it drops below 25 percent (its `device_class` must be `battery`).
- **Appliance cycles:** if the device behind a light or switch tile also has a sensor ending in `_remaining_time`, the tile shows a progress bar and time left. A sensor ending in `_total_time` fills the bar, and one ending in `_current_status` shows the status. This is how a washer or dryer integration usually looks.

## 6. Alerts: banners and the doorbell (optional)

NemuiTV watches `event.*` entities that carry the `nemuitv_alert` label. Home Assistant event entities have a timestamp as their state and an `event_type` attribute. When a new event arrives:

- If the entity's `event_types` list contains `ring`, it is a **doorbell**. A small pop-up opens with the live camera. OK expands it (and pauses playing media), Back closes it. It closes by itself after 30 seconds.
- Otherwise it is a **banner** at the top of the screen for 30 seconds (up to 3 stack).

The camera for a doorbell is a `camera.*` entity on the same device as the event entity, in an area that isn't in `hub.excludedAreas`. The camera must support WebRTC in Home Assistant.

Built-in banner texts:

| `event_type` | Banner |
|---|---|
| `washing_is_complete` | Washer done |
| `drying_is_complete` | Dryer done |
| `error_during_washing` | Washer problem |
| `drying_failed` | Dryer problem |

Any other event type shows the device name and the event type in plain words. Change any of these with `alerts.text` in `config.json`, for example `"alerts": { "text": { "washing_is_complete": "Laundry is ready" } }`.

Alerts only appear while the TV is on. Events older than 60 seconds, and everything that happened before NemuiTV connected, are ignored.

To set this up: Settings, Devices & services, Entities, find your doorbell or appliance event entities, open each one, and add the label `nemuitv_alert`.

## 7. Cameras (optional)

`camera.*` entities in an area (and not in `hub.excludedAreas`) appear on the hub. One whose entity id contains `doorbell` is listed first. Video uses WebRTC through Home Assistant, so the camera needs to work with Home Assistant's WebRTC support.

## 8. Tesla (optional)

NemuiTV shows a Tesla page automatically when Home Assistant has a device whose manufacturer is `Tesla`. We use Home Assistant's Tesla Fleet integration. The sensors NemuiTV reads (for a car whose entities start with `sensor.my_car_`):

- `sensor.my_car_battery_level`
- `sensor.my_car_charging` (state `charging` shows as charging)
- `sensor.my_car_battery_range`
- `sensor.my_car_odometer`
- `sensor.my_car_tire_pressure_front_left`, `_front_right`, `_rear_left`, `_rear_right`
- `binary_sensor.my_car_status`: when it is `off`, the car is asleep. The page then shows "💤 Asleep · last update" with the time it went to sleep, instead of a charging state it can't know. The status strip shows 💤 after the percent.
- `sensor.my_car_inside_temperature` and `sensor.my_car_outside_temperature`: shown as "Inside 78°   Outside 79°".

Any of these can be missing: the page just leaves that part out.

The Tesla page is read-only: it shows battery, charging (when the car is awake), "Asleep · last update", range, odometer, tire pressure, and inside and outside temperature. NemuiTV never sends a command to the car.

The car must have a `sensor.<name>_battery_level` entity, and `<name>` is its key. The key becomes the car's name on screen (`my_car` shows as "My Car"). Tires below `hub.lowTirePsi` turn orange, and only when the sensor reports psi. Set a car's icon color with `hub.carColors`, like `{ "my_car": "white" }` (`white`, `gray` or `darkgray`; default `gray`).

## 9. TV input switching (optional)

Needs a `media_player` entity for your TV in Home Assistant, whose `source` list has your HDMI inputs (for example from the Android TV Remote, Sony Bravia or Samsung integrations). Fill in the `tv` block in [configuration.md](configuration.md). NemuiTV calls `media_player.select_source` on it.

## 10. Lofi on the screensaver (optional)

Needs Home Assistant's Spotify integration, and the Spotify app on the TV signed in to the same account. When the screensaver starts from Home, NemuiTV calls, on your Spotify `media_player` entity: `select_source` (your TV's Spotify Connect name), `volume_set`, then `play_media` with your playlist. It does nothing if something is already playing. Fill in the `lofi` block in [configuration.md](configuration.md). The Lofi switch in Launcher settings turns it on and off.

## What NemuiTV changes in Home Assistant

It only reads, except when you use a control on the TV. Then it calls: `turn_on`/`turn_off` on lights, switches and fans, `light.turn_on` with a brightness, `homeassistant.turn_off` for a room's "all off", `script.turn_on` for scenes, `lock.lock`, climate `set_hvac_mode`/`set_temperature`, `media_player.select_source`, and the lofi calls above. It never creates or edits entities, automations or helpers.
