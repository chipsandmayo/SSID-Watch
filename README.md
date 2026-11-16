# SSID Watch

A Wi-Fi data monitor for Android that records how much data each app uses and **which Wi-Fi network (SSID)** it was used on.

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

**History before install:** the app imports Android's own per-app Wi-Fi history (as far back as the phone keeps it, usually about 3 months), so any past day shows totals, an hourly chart and per-app usage. Android never recorded the Wi-Fi name for that data, so it appears as *Network not recorded*. Network names are known from installation onwards. Tracking runs all the time once permissions are granted.

## Building it yourself

- CI: `.github/workflows/build.yml` (JDK 17, Gradle 8.10.2, AGP 8.7, Kotlin 2.0, Jetpack Compose).
- Locally: open the folder in Android Studio and run, or use `gradle assembleRelease`.

Release builds are signed in CI with a private key held in GitHub secrets (`SIGNING_KEYSTORE_B64`, `SIGNING_PASSWORD`). The older key in `app/ssidwatch.jks` is public and kept only so installed copies can move to the private key using Android's APK key rotation (Android 9+). After a phone installs a rotated build, APKs signed only with the old key can no longer replace it. If the secrets are missing, CI falls back to the old key and warns.

## Privacy

All usage history, Wi-Fi names, live-graph recordings and captured packets stay on the phone. The app only contacts ipinfo.io (provider and approximate location on the Connection tab), api.macvendors.com (router maker, from the first half of its hardware address) and GitHub (updates). The first two can be switched off in Settings. Location permission is used only because Android requires it to read the Wi-Fi name.
