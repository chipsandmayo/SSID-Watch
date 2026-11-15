# SSID Watch

A GlassWire-style Wi-Fi data monitor for Android that also records **which Wi-Fi network (SSID)** the data was used on.

## Download

**[⬇ Download the latest APK](../../releases/latest/download/SSIDWatch.apk)**

Open the file on your phone and allow "Install unknown apps" for your browser when asked. Every push to `main` builds a new APK automatically (see the Actions tab) and publishes it as a release, and newer builds install over older ones without losing data.

## Features

- **Day view**: step back and forth through days with the arrows (or tap the date to pick one). Shows total download/upload, an hourly chart, usage per network, and usage per app.
- **Per-network filter**: tap a network chip to see only that SSID's usage, its hourly chart and the apps that used data on it.
- **Per-app breakdown**: tap an app to see how its data splits across your Wi-Fi networks.
- **History**: 7 days, 30 days, 90 days or all time, with a daily bar chart (tap a bar to open that day), network totals, top apps and a list of every day.
- **Live notification** showing the current network and today's usage.
- Starts automatically after a reboot. All data stays on the phone in a local database.

## Permissions (and why)

| Permission | Why |
|---|---|
| Usage access | Android's per-app network counters (`NetworkStatsManager`) are only readable with this. |
| Location (all the time) | Android hides the Wi-Fi name (SSID) from apps that don't have location permission. The app never reads your location. |
| Location services on | Same reason: SSIDs are hidden from all apps while location is switched off. |
| Notifications | The always-on tracking notification. |
| Battery optimisation off | Keeps some phones (especially Samsung and Xiaomi) from killing the tracker. |

## How the per-SSID tracking works

Android records how much Wi-Fi data each app used, but not which Wi-Fi network it was on. SSID Watch runs a small foreground service that:

1. watches Wi-Fi connect, disconnect and network changes, and
2. snapshots every app's Wi-Fi counters on each change and every 5 minutes (every 30 seconds while the app is open).

The growth between two snapshots goes to the network that was connected in between. Counters are read cumulatively from a fixed anchor on Android's bucket boundaries, so usage isn't spread across networks by Android's 2-hour buckets.

**History before install:** on first launch the app imports Android's last ~30 days of per-app Wi-Fi usage and labels it *Before tracking*, because Android doesn't know which network that data was used on. Per-network tracking starts from installation.

## Building it yourself

- CI: `.github/workflows/build.yml` (JDK 17, Gradle 8.10.2, AGP 8.7, Kotlin 2.0, Jetpack Compose).
- Locally: open the folder in Android Studio and run, or use `gradle assembleRelease`.

The signing key `app/ssidwatch.jks` is committed so that every CI build can update the installed app in place. It's fine for personal use. If you distribute the app, replace it with your own key and store it in GitHub secrets.
