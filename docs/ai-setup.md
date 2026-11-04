# NemuiTV + Home Assistant: setup guide for AI agents

This file is for an AI agent (or a very exact human). It lists the exact Home Assistant names NemuiTV reads, what it reads them for, and a check for each step. The human-friendly version is [home-assistant.md](home-assistant.md).

## Rules

1. Do not change, rename or delete any existing automation, script, entity, area, device or label without asking the user first. Adding new labels to existing entities counts as a change: ask first, and list exactly which entities.
2. Create a dedicated Home Assistant user named `NemuiTV` for the token. Do not use the user's own account.
3. Never paste the token into chat, logs, commit messages or tool output. Have the user put it into `config.json` themselves, or write it to the file without echoing it. When you run an API check, use an environment variable, and do not print the request headers.
4. Do each step, run its check, and report the result before going on. If a check fails, stop and say what you saw.
5. Do not go beyond this file. NemuiTV only reads Home Assistant, except for the service calls listed at the end.

## Facts about NemuiTV

- It connects to one WebSocket: `ws(s)://<host>:<port>/api/websocket`, derived from `ha.url` in `config.json`, and authenticates with the long-lived token in `ha.token`.
- The user behind the token should be a dedicated **non-administrator** user (safer). Admin is not needed. NemuiTV calls `config/area_registry/list`, `config/device_registry/list`, `config/entity_registry/list` and `config/label_registry/list`, and then `subscribe_entities`.
- Camera video uses `camera/webrtc/get_client_config` (Home Assistant's WebRTC support).
- Config fields are documented in [configuration.md](configuration.md). The `config.json` file is pushed to the TV with `scripts/push-config.sh` (or `.ps1`). Manual adb steps are in [manual-setup.md](manual-setup.md).

## Step 1: user and token (required)

Do:

1. Create the user `NemuiTV` (Settings, People, Users) as a regular, non-administrator user (do not make it an administrator).
2. Ask the user to log in as that user and create a long-lived token (Profile, Security).
3. Ask the user to put it into `config.json`:

   ```json
   "ha": { "url": "http://homeassistant.local:8123", "token": "<TOKEN>" }
   ```

Check: with the token in an environment variable `HA_TOKEN` (do not print it), this must print `200`:

```
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $HA_TOKEN" http://homeassistant.local:8123/api/
```

After pushing the config, the TV's Setup page (hold Home, Launcher settings, Setup) row "Home Assistant" says `Connected`.

Note: an agent that uses this non-admin token cannot reload integrations or change Home Assistant configuration. That is expected; do not ask for admin rights to work around it.

## Step 2: weather (optional)

Name read: the `weather.*` entity in `ha.weatherEntity`. Default: `weather.forecast_home`.

Check: this must return a state (not 404):

```
curl -s -H "Authorization: Bearer $HA_TOKEN" http://homeassistant.local:8123/api/states/weather.forecast_home
```

If it is 404, ask the user which weather entity to use, and set `ha.weatherEntity`.

## Step 3: areas and room entities (required for the hub)

Names read:

| Registry | What for |
|---|---|
| area registry: `area_id`, `name` | Room names |
| device registry: `id`, `area_id`, `name`, `name_by_user`, `manufacturer` | A device's area; device names; the Tesla check |
| entity registry: `entity_id`, `device_id`, `area_id`, `name`, `original_name`, `labels`, `entity_category`, `hidden_by`, `disabled_by`, `has_entity_name` | Everything below |

Rules NemuiTV applies:

- A **room** is an area that has at least one tile. A **tile** is an entity in domain `light`, `switch` or `fan` with `entity_category` null, not hidden and not disabled, whose own `area_id` (else its device's `area_id`) is set and is not named in `hub.excludedAreas`.
- Entities in domains `lock`, `climate` and `camera`, with an area (same rule), are used for the lock strip, the thermostat (first by name) and the camera list.
- Entities with no area are ignored.

Check: list areas and their entities with a template (Developer tools, Template, or `POST /api/template`):

```
{% for a in areas() %}{{ area_name(a) }}: {{ area_entities(a) | select('match', '^(light|switch|fan)\.') | list }}
{% endfor %}
```

Report the rooms to the user and ask whether any area should be listed in `hub.excludedAreas`.

## Step 4: labels (optional; ask before adding)

| Label name | Entity domain | Effect |
|---|---|---|
| `nemuitv` | `script` | Scene list. Ordered by `hub.sceneOrder` (script entity ids), then alphabetically |
| `keep_on` | `light`, `switch`, `fan` | Skipped by a room's "all off" and by the lights-on count |
| `nemuitv_alert` | `event` | Alert source (step 6) |

Optional: for a scene `script.<x>`, an `input_boolean.<x>` (same object id) marks the scene as active while it is `on`.

Do not create the labels, or add them to entities, until the user has agreed to the exact list.

Check: this template prints the entities carrying a label (replace the label id; a label's id is usually its name in lowercase, as shown in Settings, Areas, Labels):

```
{{ label_entities('nemuitv') }}
{{ label_entities('keep_on') }}
{{ label_entities('nemuitv_alert') }}
```

Check on the TV: scenes appear in the hub's scenes row.

## Step 5: people, locks, thermostat, cycles (optional, nothing to add)

Names read, no setup beyond Step 3:

- `person.*` (all usable; state `home` counts as home), minus the ids in `hub.hiddenPeople`. Display names from `hub.peopleNames` or the entity's `friendly_name`.
- `lock.*` with an area: states `locked`, `unlocked`, `unlocking`, `open`, `opening`, `locking`, `jammed`. `sensor.*` entities on the same device with `device_class: battery` and a state below 25 raise a warning.
- `climate.*` with an area: attributes `current_temperature`, `temperature`, `target_temp_low`, `target_temp_high`, `min_temp`, `max_temp`, `hvac_modes`.
- For each light/switch/fan tile, sensors on the same device whose entity id ends with `_remaining_time` (required for the cycle bar), `_total_time` and `_current_status`.

Check: list them:

```
{{ states.person | map(attribute='entity_id') | list }}
{{ states.lock | map(attribute='entity_id') | list }}
{{ states.climate | map(attribute='entity_id') | list }}
```

## Step 6: alerts and doorbell (optional)

Name read: `event.*` entities with label `nemuitv_alert`.

Behavior:

- State is an ISO timestamp. A change to a newer timestamp, at most 60 seconds old, fires. The first state seen after connecting is only remembered.
- The attribute `event_type` of the new state picks the text. The attribute `event_types` of the entity decides the kind: if it contains `ring`, it is a doorbell pop-up (30 s, OK expands, Back closes); otherwise a banner (30 s, up to 3).
- Doorbell camera: the first usable `camera.*` entity on the **same device** as the event entity, in an area not excluded by `hub.excludedAreas`.
- Built-in texts for `event_type`: `washing_is_complete` (Washer done), `drying_is_complete` (Dryer done), `error_during_washing` (Washer problem), `drying_failed` (Dryer problem). Override or add with `alerts.text` in `config.json`.
- Alerts are dropped while the TV is off.

Check: for each candidate entity, show the type list (replace the entity id):

```
{{ state_attr('event.example_doorbell', 'event_types') }}
```

A doorbell must list `ring`. Then test with the user: ring the bell or trigger the appliance, and confirm the pop-up or banner shows on the TV.

## Step 7: cameras (optional)

Names read: `camera.*` entities with an area not in `hub.excludedAreas`. Entities whose id contains `doorbell` sort first, then by entity id. Video comes from the WebRTC call named above.

Check: the camera works in Home Assistant's own dashboard with live video.

## Step 8: Tesla (optional)

Detected when the device registry has a device with `manufacturer` equal to `Tesla`. For that device, a car exists if it has an entity `sensor.<key>_battery_level`. The `<key>` is the car's key. Sensors read, for each `<key>`:

- `sensor.<key>_battery_level`
- `sensor.<key>_charging` (`charging` = charging)
- `sensor.<key>_battery_range`
- `sensor.<key>_odometer`
- `sensor.<key>_tire_pressure_front_left`, `sensor.<key>_tire_pressure_front_right`, `sensor.<key>_tire_pressure_rear_left`, `sensor.<key>_tire_pressure_rear_right`

Config: `hub.carColors` (key to `white`, `gray` or `darkgray`; default `gray`; key order sets car order) and `hub.lowTirePsi` (default 38; only judged when the sensor's unit is `psi`).

Check: this must list the car's sensors:

```
{{ states.sensor | selectattr('entity_id', 'search', '_battery_level$') | map(attribute='entity_id') | list }}
```

## Step 9: TV inputs (optional)

Name read: the `media_player.*` entity in `tv.entity`, attribute `source` and `source_list`. Config: `tv.inputs[].source` must equal a value from `source_list`; `tv.thisDevice` is the `source` that is the Google TV box itself.

Check:

```
{{ state_attr('media_player.living_room_tv', 'source_list') }}
```

## Step 10: lofi (optional)

Names read: the Spotify `media_player.*` entity in `lofi.spotifyEntity` (default in the example: `media_player.spotify`), and its `source` list for `lofi.source` (the Spotify Connect name of the TV).

Calls NemuiTV makes (domain `media_player`), in this order: `select_source` (`source`), `volume_set` (`volume_level`), `play_media` (`media_content_type: playlist`, `media_content_id: lofi.playlist`).

Check:

```
{{ state_attr('media_player.spotify', 'source_list') }}
```

## Service calls NemuiTV can make

Only when the user uses a control on the TV:

- `light`/`switch`/`fan`: `turn_on`, `turn_off`; `light.turn_on` with `brightness_pct`
- `homeassistant.turn_off` (a room's "all off")
- `script.turn_on` (scenes)
- `lock.lock`
- `climate.set_hvac_mode`, `climate.set_temperature`
- `media_player.select_source` (TV inputs, lofi), `media_player.volume_set`, `media_player.play_media` (lofi)

It never creates or edits entities, automations, labels or helpers.

## Final check

1. `config.json` is valid JSON (`python3 -m json.tool config.json`).
2. It has been pushed (`scripts/push-config.sh`), and Home was pressed once.
3. The Setup page shows `Home Assistant: Connected` and no `Config` line.
4. The hub (the ⌂ tile) shows the expected rooms.
