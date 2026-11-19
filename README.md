# Yearwall

[![Build APK](https://github.com/jehan593/yearwall/actions/workflows/build-apk.yml/badge.svg)](https://github.com/jehan593/yearwall/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/jehan593/yearwall)](https://github.com/jehan593/yearwall/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

The Android port of my [Termux yearwall system](https://github.com/jehan593/termux-config): a daily
"year progress" wallpaper ΓÇö a Nord dot grid with one red dot for today, plus a `NN% of YYYY` /
`day/total` header. Same look, same habits, zero Termux.

> **FYI:** This project is fully vibe coded for my personal use and taste. It's shared publicly in case it's useful to you. Feel free to fork it and adapt it to your needs.

## Features

- **The same render, no Termux** ΓÇö the wallpaper is drawn natively with the same colors, layout
  and Martian Mono header: elapsed days filled, remaining days hollow, today red.
- **Lock screen by default** ΓÇö like `termux-wallpaper -l`. Home screen or both is a one-tap choice.
- **Set-and-forget updates** ΓÇö an exact alarm fires at midnight and works while the phone is
  off-screen and asleep; boot, clock, timezone and app-open catch-ups cover anything missed.
  *Huawei/EMUI: set App launch ΓåÆ Yearwall ΓåÆ Manage manually so the overnight alarm is honored.*
- **Remove puts back the default** ΓÇö Remove sets the same default wallpaper the shell setup
  used (a bundled copy of `termux-config`'s `data/wallpaper/wallpaper.png`), on the screens
  yearwall touched. No leftover lock-in: uninstalling stops everything instantly.
- **Status you can read** ΓÇö every part of the automation reports what it did and why.
- **Nord themed UI** ΓÇö same palette and Martian Mono type as Linker/OwnApps/Noter.

## Install

Download the latest APK from [Releases](https://github.com/jehan593/yearwall/releases/latest). Open
the app, review the status list, and press **Apply now** to set today's wallpaper. That's it.

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Requires an Android SDK path in `local.properties` (`sdk.dir=...`).

## Tech stack

Kotlin, Jetpack Compose, Material 3, WorkManager, DataStore.

## License

MIT ΓÇö see [`LICENSE`](LICENSE). The bundled Martian Mono font is licensed separately under the
SIL Open Font License 1.1 ΓÇö see
[`app/licenses/MARTIAN_MONO_LICENSE.txt`](app/licenses/MARTIAN_MONO_LICENSE.txt).