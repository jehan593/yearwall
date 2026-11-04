# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Yearwall: the standalone Android port of the Termux yearwall system from `../termux-config` — a daily
"year progress" wallpaper (Nord dot grid, one red dot for today, `NN% of YYYY` header, separator line)
applied to home **and** lock screen, kept fresh by three overlapping triggers. Package
`com.yearwall.app`, minSdk 26, Jetpack Compose UI in the sibling-apps' Nord + Martian Mono identity
(see `../linker`, `../ownapps`, `../noter`, `../tooler`). No Termux anywhere in the picture: the
magick pipeline is a Canvas render and the `termux-wallpaper` call is `WallpaperManager`.

The original shell system is three hardcoded mechanisms around one generator script:

| `termux-config` piece | Yearwall equivalent (`automation/`) |
| --- | --- |
| `tools/yearwall.sh` → writes `yearwall_update.sh` | `YearwallWorker` + `YearwallEngine.apply()` |
| magick `1080x2400 xc:...` pipeline | `WallpaperGenerator.generate()` (`wallpaper/`) |
| `termux-wallpaper -f … -l` (lock screen — the termux-api source selects exactly one flag: `lockscreen ? FLAG_LOCK : FLAG_SYSTEM`) | `WallpaperApplier.apply()`, `FLAG_LOCK` by default; app adds `FLAG_SYSTEM` ("home") / both as a selectable target the shell never had |
| `crontab "0 0 * * *"` | unique work `yearwall_midnight` — one-time job armed for the next *local* midnight that re-arms itself at the end of every run (12h periodic can't track midnight; self-chaining one-time can) |
| `~/.termux/boot/50-yearwall.sh` | manifest receiver `BootReceiver` on `BOOT_COMPLETED` |
| `etc/profile.d/yearwall-catchup.sh` | `MainActivity.onResume()` → `Scheduler.ensure()`, which the engine's staleness check turns into a no-op when the day is current |
| dep-checker / printfc status | `StatusChecker.collect()` → the Status list on the main screen |
| `rm` + `_remove_yearwall_setup` | `YearwallEngine.remove()` |

## Commands

```sh
./gradlew assembleRelease    # build app/build/outputs/apk/release/app-release.apk (R8-minified, resource-shrunk — what CI ships)
./gradlew assembleDebug      # build app/build/outputs/apk/debug/app-debug.apk (local iteration only)
./gradlew build              # full build incl. lint/checks
```

- `local.properties` needs `sdk.dir` pointing at an Android SDK (Windows: forward slashes + escaped
  drive colon, e.g. `sdk.dir=C\:/Users/jehan/android-sdk` — a bare `C:\...` fails Gradle property
  parsing with a cryptic "filename syntax is incorrect" error).
- Build the APK and hand it off rather than installing to an emulator/adb yourself — the user tests
  on their own device.
- `versionCode`/`versionName` are overridable via `-PappVersionCode=`/`-PappVersionName=` — CI
  (`.github/workflows/build-apk.yml`) passes `github.run_number` so every push gets a strictly
  increasing versionCode (required for update checkers like Obtainium) and its own tag/release.
  `app/debug.keystore` is a committed keystore so CI and local builds always sign with the same key;
  the release build type reuses that same pinned key so in-place updates via Obtainium keep working.
- Release is minified (`isMinifyEnabled`), resource-shrunk (`isShrinkResources`), and ABI-filtered to
  `arm64-v8a`/`armeabi-v7a` — same lightest-possible-APK pass as linker/ownapps/tooler. The Martian
  Mono weights bundled (regular, medium) are the same Latin subsets the sibling apps ship; the
  wallpaper's painted header loads `R.font.martian_mono_regular` directly, so the render reads in the
  same typeface as the app UI.

## Architecture

Manual design, no DI framework, no Room: the app is one stateful pipeline plus thin plumbing.

**Data** (`data/YearwallStore.kt`) — a single DataStore, the shell system's filesystem state in
pref form: `last_applied_epoch_day` (the boot script's `date -r` staleness check), the two design
knobs (`columns`, `content_y_percent` — grid columns at the script's default 21, and the whole-
content Y anchor as a percent of height, default 29 ≈ the script's 700/2400), whether the pre-
yearwall wallpaper has been archived (home & lock separately), the verbatim `backup_note` state,
and `last_error` for the status list. No Room — none of that is relational; tooler's "no database
for scalar prefs" rule applies directly.

**Render** (`wallpaper/WallpaperGenerator.kt`) — one `Canvas` frame built at the system's own
desired wallpaper size (`WallpaperManager.desiredMinimumWidth/Height`, 1080x2400 only as the
fallback when the system refuses to report) — nothing is hard-coded in pixels. Every metric is a
ratio of the render derived from the shell pipeline's constants at its 1080x2400 reference: cell =
0.8167·width/columns (dot radius, ring stroke and row pitch all derive from the cell;
0.215/0.05/1.12 ratios), header 0.0324·width, separator thickness 0.00926·width at 8.8%→89.6% of
width. Two knobs only (`columns` = width/density, `contentYPercent` = the whole composite's Y
anchor — header hangs one text-size above the separator, the grid hangs one row pitch below it, so
Y moves header + line + grid together and there is no way to shift the line without the grid).
The short trailing row is ALWAYS centered under the grid — the termux render centered each
text line via ImageMagick gravity, so that is the original look, not an option. The script's
fake-bold (3× redraw at 0/1/2 px offsets since magick has no font-weight) is `Paint.isFakeBoldText`.
Leap years come from `LocalDate.lengthOfYear()` (the script's `date -d "...12-31"` trick) and the
shell's `10#` octal guard has no analogue — `java.time` doesn't have that trap.

**Apply** (`wallpaper/WallpaperApplier.kt`) — `setBitmap` per targeted flag with an explicit
full-frame crop rect (`isWallpaperSupported`/`isSetWallpaperAllowed` checked first — the
dep-checker equivalent; the per-flag try/catch is deliberate because stock skins often lock the
lock screen only). The original wallpaper is snapshotted **once, per targeted screen, before that
screen's first apply** and restored by Remove. What counts as "not archivable": ANY wall the app
itself ever painted there (today's render, yesterday's, a reinstalled-earlier one) — detected via
two invisible marker pixels the generator stamps into every render (`WallpaperGenerator.MARKER_A
/B`, corner pixels outside the Nord palette, checked with `isAppRender`), so "was this set by
yearwall?" is O(1) and day-instance-proof — archiving an app-made wall would make Remove "restore"
a yearwall render, defeating the whole feature. A snapshot that the system can't hand back
(getDrawable/peekDrawable both null/throw — some skins) does NOT flip the handled flag: the next
apply retries and the `backup_note` DataStore value spells out exactly what happened in the status
row ("system reported no wallpaper readable…", "wall is one of yearwall's own renders — not
archived", …) — a silently skipped archive must never be confusable with a broken one.

**Trigger layers** (`automation/`) — three unique WorkManager names:
- `yearwall_midnight` — self-rescheduling one-time job (+10 s past 00:00 so the day has flipped
  before `LocalDate.now()` runs); armed with `ExistingWorkPolicy.KEEP` on the app-open path and
  `REPLACE` when a receiver re-bases the clock/timezone (the pending delay was computed against the
  old clock and is otherwise silently wrong).
- `yearwall_catchup` — expedited-style immediate run, REPLACE-collapsed; fired on every app open and
  from `BootReceiver`.
- `yearwall_daily_fallback` — 24h periodic KEEP insurance. WorkManager chains can be dropped by
  aggressive cleaners; the engine's staleness check makes an extra same-day run a cheap no-op, so it
  can't double-generate. (The shell script never needed this layer — crontab was already crash-proof
  within its own lifetime.)

The **worker re-arms the midnight chain itself** at the end of every run (which is why the worker runs
the re-arm, not `Scheduler` fire-and-forget); `runAttemptCount < 3` retries transient apply failures,
after which the reason lives in `last_error`. That's the port of the script's
`|| { echo "Failed to apply wallpaper."; exit 1; }` handling and the status command reading the
aftermath.

**Identical entry funnel** — midnight worker, catch-up worker, fallback periodic, and the Apply-now
button all call `YearwallEngine.apply()`, which gates on `lastAppliedEpochDay == today.toEpochDay()`
when not forced (the boot script's staleness test), renders, saves the PNG to `filesDir` (the script's
`yearwall_generated.png`), applies the targeted screens (lock by default, per the target pref),
and stamps the day only when every targeted screen took.

## Theme

`ui/theme/` is the shared sibling-repo setup verbatim: the canonical 16-color Nord palette, full
M3 dark+light schemes (every role set explicitly — the container tiers stay within nord0–2, never
touch nord3/outline, so borders stay visible), Shapes at 8/12/16/28 dp, Martian Mono typography.
Status dots: green = nord14, red = nord11 — same semantic mapping as the shell's printfc colors.

## Fonts

Martian Mono ships under `res/font/` exactly like linker/ownapps/noter; license at
`app/licenses/MARTIAN_MONO_LICENSE.txt` (SIL OFL 1.1) — kept out of `res/` because Android's resource
merger rejects non-xml/ttf files there.
