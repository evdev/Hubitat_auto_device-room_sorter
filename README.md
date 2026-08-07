# Auto Room Sorter (Hubitat)

Hubitat app that detects room names inside device labels, creates any missing rooms, and assigns unassigned devices into them — with preview, exclusions, leftovers, and one-level undo.

## Install via Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) if you do not already have it.
2. In HPM, choose **Install** → **From a URL** (or **Add a Custom Repository** if you prefer).
3. Use this repository URL:

   `https://raw.githubusercontent.com/evdev/Hubitat_auto_device-room_sorter/main/repository.json`

4. Select **Auto Room Sorter** and install.
5. **Apps** → **Add User App** → **Auto Room Sorter**.
6. Optional: enter hub login security credentials in the app if your hub requires login.
7. Check the safety acknowledgment before creating rooms or sorting.

Prefer a hub backup before large sorts.

### Manual install

1. **Apps Code** → **New App** → paste [`AutoRoomSorter.groovy`](AutoRoomSorter.groovy) (or Import using the `importUrl` in the definition) → **Save**.
2. **Apps** → **Add User App** → **Auto Room Sorter**.

## How it works

1. **Scan** — Reads all devices (including children) from `/hub2/devicesList` and all rooms from `/room/listRoomsJson`.
2. **Seed** — Every existing hub room becomes a match target (so `Saadya's Room Light` matches `Saadya's Room` with no setup). Matching catalog aliases are merged in (so `Outdoor …` matches your existing `Outside` room).
3. **Step 1** — Review detected rooms. **Exists** rooms are reused (never duplicated, never renamed). **New** rooms can be created. Edit aliases freely; new room names are editable.
4. **Step 2** — Preview matches grouped by room, exclude any devices, optionally assign leftovers manually, then **Sort Devices**.
5. **Undo** — One-level undo restores each device’s previous room from the last sort (and can delete empty rooms created in that run).

Already-assigned devices are **never** moved.

## Matching rules

- Uses the device **display label**, not the driver type name.
- Normalization: lowercase, strip apostrophes (`Saadya's` → `saadyas`), replace other non-alphanumerics with spaces (`Backyard_Left_Side` → `backyard left side`), tokenize.
- Aliases match only as whole token sequences (no substring hits inside longer words).
- Longest / most-specific alias wins; ties are flagged **ambiguous** in the preview.
- Alias safety: minimum 3 characters; bare qualifiers like `master` / `guest` are rejected; generic words like `room` / `hall` are denied.

Child and virtual devices are included by default (toggles on the main page).

## Important API notes

Room create/assign is **not** in the public Groovy API. This app uses hub-local undocumented endpoints (verified on C-8 Pro firmware **2.5.1.143**):

| Action | Endpoint |
|--------|----------|
| List rooms | `GET /room/listRoomsJson` |
| List devices | `GET /hub2/devicesList` |
| Create room | `POST /room/save` JSON `{"roomId":0,"name":"…","deviceIds":[]}` |
| Assign | `GET /device/setRoom?deviceId=&roomId=` |
| Unassign | `setRoom` with `roomId=0` |

**Do not** use `/device/updateRoom` — its `room` parameter is a **name**, and unknown names create new rooms (e.g. `room=18` created a room named `"18"`).

These endpoints can change with firmware. The app probes reads on open and runs a no-op write probe before the first write of a run.

## Local matcher tests

```bash
groovy tests/MatcherSpec.groovy
```

Uses real fixtures under `tests/fixtures/` captured from a live hub.

## Non-English homes

The built-in catalog is English. Existing hub room names are still seeded automatically, and you can add custom rooms/aliases in Step 1.
