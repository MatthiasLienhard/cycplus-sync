# CYCPLUS Sync — CYCPLUS / XOSS bike computers → Health Connect

[![Build](https://github.com/andrewkomkov/cycplus-m2-sync/actions/workflows/build.yml/badge.svg)](https://github.com/andrewkomkov/cycplus-m2-sync/actions/workflows/build.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=andrewkomkov_cycplus-m2-sync&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=andrewkomkov_cycplus-m2-sync)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=andrewkomkov_cycplus-m2-sync&metric=coverage)](https://sonarcloud.io/component_measures?id=andrewkomkov_cycplus-m2-sync&metric=coverage)
[![Release](https://img.shields.io/github/v/release/andrewkomkov/cycplus-m2-sync?sort=semver)](https://github.com/andrewkomkov/cycplus-m2-sync/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Download rides from a **CYCPLUS or XOSS** GPS bike computer straight to your Android phone over
Bluetooth LE, write them into **Health Connect**, and export the raw `.fit` files anywhere you
like — **without the vendor cloud, without an account, without the vendor app**.

[Читать по-русски](README.ru.md)

| Rides | Bulk selection | Settings |
| --- | --- | --- |
| ![Ride list](docs/screenshot-en.png) | ![Selecting rides to export](docs/screenshot-select.png) | ![Settings menu](docs/screenshot-menu.png) |

## Why this exists

The stock workflow sends every ride through the vendor's cloud, and the Google side of it is
disappearing too: the **Google Fit APIs are shut down at the end of 2026** and the Fit app is being
replaced by Google Health, with **Health Connect** as the on-device store everything now talks to.
This app writes directly into Health Connect, so your rides land where Google Health and anything
else that reads that store can pick them up — and the `.fit` files stay yours.

Strava is the exception worth knowing about: its Health Connect link only goes one way. Strava
writes activities into Health Connect and reads weight back out, but it never reads activities
from it, so rides will not reach Strava this way. Getting them there means uploading the `.fit`
yourself — the share button hands you the file — because as of June 2026 Strava's API needs a paid
Strava subscription on its Standard tier, which is where an app like this one lands.

## Features

- **Bluetooth LE sync** — finds the bike computer, downloads only new rides (228 KB in ~13 s)
- **Health Connect import** — cycling session with GPS route, heart rate, cadence, speed, power,
  distance, elevation gain; pauses are written as segments so *moving time* stays correct
- **No duplicates** — de-duplicated by `clientRecordId`, re-running a sync is safe
- **Raw `.fit` export** — share one ride or many at once, with readable file names like
  `2026-07-24_10-30_40.99km_cycplus.fit`
- **Calories the M2 never records** — estimated from heart rate (Keytel et al., 2005), with a
  speed-based MET fallback, and written as `TotalCaloriesBurnedRecord`
- **Smart scale support** — reads weight straight off a Bluetooth scale's advertisement and files
  it in Health Connect; the fresh weight feeds the calorie estimate
- **Verify** — reads the rides back out of Health Connect and reports what is missing, plus which
  other apps wrote records for the same days
- **Sync on launch** — opening the app finds the bike computer and pulls new rides in ~3 s
- **Update check** — asks GitHub Releases for a newer version, then downloads and installs it in
  place: the APK is fetched in-app, checked against the published sha256, and handed to the system
  installer, so all you see is Android's own "update this app?" dialog
- **Ride in detail** — tap a ride for its route on an OpenStreetMap basemap plus elevation,
  speed, heart-rate, cadence and power charts; select a metric to color the route
- **Power curve** — best average power over logarithmically spaced intervals, from seconds to
  longer efforts
- **3D fly-through** — fly the track from a bird's-eye chase camera, with the map draped on the
  ground in perspective; drag to orbit, pinch to zoom, double-tap to recentre. No API key and no
  account, and the basemap switches off if you want the ride to stay fully offline
- **Fully scriptable over ADB** — every action runs headless, no tapping required
- **Material 3 Expressive UI** with dynamic colour, English and Russian localisation
- **Device card** — model, firmware and battery read straight off the device; storage is shown
  only when the device reports a valid value

## Install

Grab the APK from [Releases](https://github.com/andrewkomkov/cycplus-m2-sync/releases) and
install it, or build from source:

```bash
git clone https://github.com/andrewkomkov/cycplus-m2-sync.git
cd cycplus-m2-sync/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android 13 or newer with Health Connect (built into Android 14+).

Debug builds install as `dev.komkov.m2sync.debug`, so they sit next to a release build instead of
replacing it. Substitute that package name in the ADB commands below when you build from source.

Permissions can be granted from the app (lock icon), or entirely from a terminal:

```bash
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
  adb shell pm grant dev.komkov.m2sync android.permission.$p
done
for p in WRITE_EXERCISE WRITE_EXERCISE_ROUTE WRITE_HEART_RATE WRITE_DISTANCE \
         WRITE_SPEED WRITE_ELEVATION_GAINED WRITE_TOTAL_CALORIES_BURNED WRITE_WEIGHT \
         READ_EXERCISE READ_HEART_RATE READ_DISTANCE READ_SPEED READ_WEIGHT; do
  adb shell pm grant dev.komkov.m2sync android.permission.health.$p
done
```

`$S.PERMS` prints the exact list the current build expects, so it stays honest when the set changes.

## Usage from the terminal

```bash
S="adb shell am start-foreground-service -n dev.komkov.m2sync/.SyncService -a dev.komkov.m2sync"

$S.SCAN                    # look for the bike computer
$S.SYNC                    # download new rides and import them
$S.SYNC -e name M2_XXXX    # target a specific device
$S.INFO                    # firmware, battery, free memory
$S.IMPORT                  # import already downloaded files only
$S.IMPORT -e force 1       # re-import everything (after changing import logic)
$S.STATUS                  # what is stored locally
$S.VERIFY                  # read back from Health Connect and compare
$S.WEIGH                   # wait for a scale reading and store the weight
$S.PERMS                   # permission strings Health Connect expects

adb logcat -s M2SYNC       # follow the work
```

Downloaded files live in `/storage/emulated/0/Android/data/dev.komkov.m2sync/files/fit`:

```bash
adb pull /storage/emulated/0/Android/data/dev.komkov.m2sync/files/fit ./rides
```

## Supported devices

Verified on **Cycplus M2**, firmware V1.4.0, and **CYCPLUS M3**, firmware V1.1.9.

The same Nordic UART protocol is used across related devices. Legacy models use the standard
YMODEM transfer, while the CYCPLUS M3 uses a hybrid transfer: YMODEM-style metadata followed by
its custom `55 aa` data frames. Discovery accepts legacy `M2_` names and CYCPLUS advertisements.
Newer models (NAV, G2+) list rides in `workouts.json` instead of `filelist.txt` — not yet handled.
Reports welcome.

## What lands in Health Connect

| Health Connect record | Source in the `.fit` |
| --- | --- |
| `ExerciseSessionRecord` (biking) + `ExerciseRoute` | session + 1 Hz GPS track |
| `ExerciseSegment` (biking / pause) | gaps in the recording |
| `HeartRateRecord` | `heart_rate` per record |
| `CyclingPedalingCadenceRecord` | `cadence` |
| `PowerRecord` | `power` per record, when a power meter was connected |
| `SpeedRecord`, `DistanceRecord` | `enhanced_speed`, session total |
| `ElevationGainedRecord` | `total_ascent` |
| `TotalCaloriesBurnedRecord` | estimated — see below |
| `WeightRecord` | a Bluetooth scale, not the bike computer |

### Calories

The M2 records none: `total_calories` is empty in both `session` and `lap`. So the app estimates
them itself — from heart rate with the Keytel et al. (2005) equation, which needs exactly what the
`.fit` already has per second, and from speed via MET (Compendium of Physical Activities) where
heart rate is missing. Both models give gross expenditure over moving time, which is the semantics
`TotalCaloriesBurnedRecord` asks for. A ride that does carry `total_calories` is taken as is.

The estimate needs weight, year of birth and sex. Weight comes from the latest `WeightRecord` in
Health Connect — the ⚖ button fills it from a Bluetooth scale. Year of birth and sex are read from
the Health Connect medical record (FHIR `Patient`) when it has them, and asked for once in
**⋮ → Profile for calories** otherwise. Change any of it and the rides recalculate on the spot.

### Smart scale

Weight is read from the standard Weight Scale advertisement (`0x181D`) — no pairing, no GATT
connection, the app just listens. Verified on **Mi Smart Scale 2**. Body composition is not
available over the air: impedance travels in the 13-byte `0x181B` packet the scale does not
broadcast, and Mi Fit computes body fat on the phone.

## Protocol

The device speaks YMODEM over the Nordic UART Service. Full write-up with commands, quirks and
per-model differences: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Desktop tools (optional)

Python CLI for pulling rides without a phone, and for probing an unknown model:

```bash
pip install -r tools/requirements.txt
python tools/m2_cli.py scan          # BLE devices nearby
python tools/m2_cli.py info          # model, firmware, battery, free space
python tools/m2_cli.py sync          # download new .fit into ./fit
python tools/m2_cli.py services      # full GATT map — useful for new devices
python tools/fit_summary.py          # what is inside the downloaded files
```

## Privacy

Rides never leave the phone: no analytics, no account, no upload. The only network request the app
can make is an optional version check against `api.github.com/repos/…/releases/latest`, which sends
nothing but a User-Agent and can be switched off in the ⋮ menu. Weight, year of birth and sex are
used for the calorie estimate only and never leave the device — the profile lives in the app's own
preferences, the weight in Health Connect.

## Credits

- [ekspla/xoss_sync](https://github.com/ekspla/xoss_sync) — the working Python implementation of
  the protocol that this project started from
- [Kaiserdragon2/CycSync](https://github.com/Kaiserdragon2/CycSync) — an earlier Android attempt
  aimed at the Cycplus M2
- [Garmin FIT SDK](https://github.com/garmin/fit-java-sdk) — FIT decoding

Not affiliated with Cycplus, XOSS, Garmin or Google.

## License

MIT — see [LICENSE](LICENSE).
