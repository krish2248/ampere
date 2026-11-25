# SESSION.md - Ampere project state

> Read this first every session. Update it last, then commit and push.
> `03-battery-app-SPEC.md` is the original spec. Where it disagrees with this file, this file wins (see "Changed from spec").

## 1. What this is

Ampere: battery health, live charging watts, time to full, records, ranks and analytics for Android. Kotlin + Compose +
Room + DataStore. Version **1.0.0 (versionCode 2)**. Repo: https://github.com/krish2248/ampere, branch `main`.

Five tabs: **Live, Health, Stats, Arena, Phone**. Sub-pages: Settings, Diagnostics, Sessions, Session, Achievements,
Ranks, Daily goals, World ranking, Profile. Plus onboarding, background service, alerts, home widget, Quick Settings tile.

## 2. Status

### Done
- Real capacity vs original (charge counter, coulomb counting fallback, weighted median, confidence label, trend)
- Live V / mA / W, charger offer (V x A), phone rating, charge class, C rate, throttling, heat, warnings
- Time to 80% / 100% at the present speed; learns this phone's own charge curve; "what if" table per charger wattage
- Phone recognition: 25,285 models (Google Play catalogue) + 327 published charging specs
- Charge records: 8 standard stretches + any custom stretch (count, average, best), live splits vs personal best
- 18 ranks, country flag + nickname, VP points, 46 achievements, 5 daily goals with streaks
- World ranking of 327 phones (offline). Live player leaderboard: client done, **needs a backend, see section 6**
- Stats: charts 24 h / 7 d / 30 d / all, charge map, hours, sources, grades, drain, sessions with explained grades
- Timer + charge target, alerts (target, hot, low, slow/unstable, throttle, achievements)
- Export: PDF report, rank card picture, 2 CSVs
- Light + dark theme, Celsius/Fahrenheit, delete all data, diagnostics
- Store assets in `store/`, `PRIVACY.md`, signing config, baseline profile rules

### Verified (25 Nov 2026)
- 31 unit tests pass; lint 0 errors (11 warnings); `bundleRelease` builds a 4.7 MB unsigned .aab; R8 APK 3.5 MB
  (all re-run in session 4 on commit 36dff6d, before the fixes in section 3)
- Emulator API 36 and API 29: launch, all tabs, no crash
- 4,000-event monkey run on the R8 build: no crash, no ANR
- Background service, ongoing notification, charge-target and heat alerts fired correctly
- PDF, card and both CSVs opened and checked. Light theme and 1.4x font checked
- Leaderboard client checked against a local mock server (join, list, leave), NOT against real Supabase
- R8 release APK about 3.5 MB. Frame times on par with Android's own Settings app on the same emulator

### NOT done / NOT verified
- **Never run on a real phone.** Current unit/sign, charge counter, cycle count and charger offer must be checked on the S24
- `supabase.sql` has not been run on a real database
- No upload key yet, so the .aab that builds is unsigned
- Boot-restart of tracking, widget and tile were written but not exercised on a device
- "0.5 to 1% battery a day" in the UI is an estimate, not measured. Measure it or soften the text
- Device specs were entered from memory of published figures: spot-check `tools/specs.txt`
- TalkBack pass, landscape and tablet not checked

## 3. Next steps (resume here)

### Fixes found in session 4's code review (tick when done)
- [x] Engine.close(): coverage must be coveredMs / (endTs - startTs), not / durationMs. durationMs skips time the app was
      killed, so coulomb counting can read capacity too low
- [x] Capacity.fromCharge: if the counter estimate is within 0.4% of design and there is no coulomb figure to check it
      against, reject it (a gauge that mirrors design shows a false "Measured 100%"). Add a test
- [x] Remember Build.MODEL; if it changes (cloud restore to a new phone) clear capacity estimates
- [x] Settings: "Battery was replaced" row that clears estimates (dao.clearEstimates exists)
- [x] BootReceiver: reschedule a pending countdown (timerAt)
- [x] Onboarding: steps 0 and 1 overflow in landscape
- [x] TalkBack: Toggle rows should use toggleable; Ruler needs progress semantics and a setProgress action
- [x] Replace the unmeasured "0.5 to 1% battery a day" text (SettingsScreen twice)

### Then
1. Emulator: widget, tile, reboot restart, landscape, TalkBack semantics
2. Rebuild tests + lint + .aab, commit, push
3. Owner: install the preview APK on the S24, compare Live with a USB meter, read Phone > Raw diagnostics
4. Owner: record 3 charges of 25%+, check Health becomes "Measured"; measure 24 h background drain
5. Owner: decide on the live leaderboard (section 6), then publish (section 8)

## 4. Build

JDK 17, Android SDK 36. Java on this PC could not reach services.gradle.org before; Gradle 8.14.3 is already unpacked:

```powershell
$g = (Resolve-Path "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\*\gradle-8.14.3\bin\gradle.bat").Path
& $g :app:assembleDebug            # with demo tools
& $g :app:assemblePreview          # release-optimised, debug-signed: use this on your phone
& $g :app:testDebugUnitTest :app:lintRelease
& $g :app:bundleRelease            # app/build/outputs/bundle/release/app-release.aab
adb install -r app\build\outputs\apk\preview\app-preview.apk
```

Low on memory (another build or emulator running kills the daemon)? Stop the emulator and use:
`--no-daemon --max-workers=2 "-Dorg.gradle.parallel=false" "-Pkotlin.compiler.execution.strategy=in-process" "-Dorg.gradle.jvmargs=-Xmx2200m"`

Versions: Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, KSP 2.2.21-2.0.4, Compose BOM 2025.12.00, Room 2.8.4,
compile/target 36, min 29. If the Android SDK is not found, put `sdk.dir=...` in `local.properties`.

Demo data (debug build only):
```
adb shell am broadcast -n dev.ampere.battery/.DemoReceiver --es do seed      # 64 days of history
adb shell am broadcast -n dev.ampere.battery/.DemoReceiver --es do charge --ei level 46 --ei step 31
adb shell am broadcast -n dev.ampere.battery/.DemoReceiver --es do battery   # or: idle, s24, real
```

## 5. Code map

`app/src/main/java/dev/ampere/battery/`
- `AmpereApp.kt` container, derived state (stats, health, game), awards
- `hw/` battery + device reading. `domain/` pure logic: Calc, Stats, Game (tested in `app/src/test`)
- `monitor/Engine.kt` the one sampling loop + session tracking. `MonitorService.kt` service, alerts, boot, timer, tile
- `data/` Room, DataStore, Board (leaderboard). `export/Share.kt` PDF, card, CSV
- `ui/theme`, `ui/component` (charts are hand-drawn), `ui/screens`, `ui/Root.kt` navigation
- `app/src/tools/` debug-only demo receiver. `tools/` scripts: device tables, resources, store art

Device tables: edit `tools/specs.txt`, then `python tools/build_assets.py supported_devices.csv`
(csv from https://storage.googleapis.com/play_public/supported_devices.csv).

## 6. Live worldwide leaderboard (optional, about 10 minutes)
1. Create a free project at supabase.com
2. SQL editor: paste and run `backend/supabase.sql`
3. Project settings > API: copy the URL and the anon (publishable) key
4. In `local.properties` (git-ignored) add `ampere.boardUrl=https://xxxx.supabase.co` and `ampere.boardKey=...`
5. Rebuild. World ranking gains a Players tab and the app gains the internet permission

Without this the app has no internet permission and shows only the phone ranking; nothing is faked.
Scores come from the phone, so a determined user can forge one; the SQL only rejects impossible numbers.
Reports hide an entry after five. Free Supabase projects pause when unused.

## 7. Changed from spec
- target 36 (Play requirement), newer toolchain, WorkManager dropped, 5 tabs instead of 4
- Optional, opt-in network for the leaderboard only
- Original capacity: published spec first, then firmware, gauge, resource; manual entry overrides all
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` removed (Play restricts it); settings screen is opened instead
- Ranks use original names and badges rather than Valve's, to avoid a trademark strike

## 8. Publishing on Google Play, step by step

1. **Developer account**: https://play.google.com/console, one-time 25 USD, identity verification. New personal accounts
   must run a closed test with at least 12 testers for 14 days in a row before production access.
2. **Upload key** (once; back it up, never commit it). In the repo root:
   ```powershell
   keytool -genkeypair -v -keystore ampere-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Create `keystore.properties` (git-ignored):
   ```
   storeFile=ampere-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
3. **Finish section 3**, then build `:app:bundleRelease`. Raise `versionCode` for every later upload.
4. **Create app**: name "Ampere: Battery Health & Watts", English, App, Free. Accept Play App Signing.
   The package `dev.ampere.battery` is permanent after the first upload; change it first if you own a domain.
5. **App content**:
   - Privacy policy: https://github.com/krish2248/ampere/blob/main/PRIVACY.md
   - Ads: none. App access: all available without login. Target audience: 13+ (simplest)
   - Content rating: fill in the questionnaire. Declare user interaction only if you ship the live board
   - Data safety, no board: "does not collect or share any data"
   - Data safety, with board: collects nickname (User IDs), app activity (scores) and device model; optional, encrypted
     in transit, deletable in the app. Describe the report and hide moderation
   - **Foreground service, special use**: "User-enabled continuous battery, charging and temperature telemetry that
     records charge sessions and alerts with the app closed. Event-driven jobs cannot time a charge per percent."
     Upload a short screen video: Settings > Background tracking on, notification appears, plug in
   - Government, financial, health apps: No
6. **Store listing**: copy from `store/listing.md`; upload `store/icon-512.png`, `store/feature-1024x500.png` and
   `store/screenshots/*` (1080x2160, demo data on an emulator; replace with your own phone's if you can).
7. **Testing > Closed testing**: upload the .aab, add testers' emails, send the opt-in link, wait the 14 days if required.
   Check Pre-launch report and Android vitals.
8. **Apply for production**, answer the testing questions, roll out to 20%, then 100%. First review often takes days.
9. Afterwards: reply to reviews, watch vitals, raise `targetSdk` every August.

## 9. Gotchas
- adb only finds emulators on ports 5554 to 5584. Another project's emulator may be running: always use `adb -s`
- Play screenshots may not be taller than 2:1 (`adb shell wm size 1080x2160`)
- Long shell heredocs fail in Claude Code on this PC; write files with the Write tool
- White nav buttons in light mode on the API 36 AOSP emulator are an image bug (Settings shows it too)
- `DeviceDb.norm()` and `tools/build_assets.py norm()` must stay identical

## 10. Session log
- **25 Nov 2026 (session 4)**: verified release bundle, R8, tests and lint on 36dff6d; reviewed the code and listed the
  fixes in section 3. All eight are fixed and unit-tested (31 pass); emulator checks come next.
- **25 Nov 2026 (session 3)**: skeleton became the full 1.0.0 app. Tests caught device-name collisions (CMF Phone 1 /
  Nothing Phone (1), Pixel 4a / 4a 5G); emulator testing caught bogus records after level jumps, 2.2 MB slow PDFs and
  exports cancelled by closing the sheet. All fixed. Stopped early on request; see "NOT done" above.
- **25 Aug 2026 (session 2)**: first green build of the skeleton.
- **23 Aug 2026 (session 1)**: repo, spec, Gradle skeleton.
